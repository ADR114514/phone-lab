"""Reconstruct the observed charge-counter windows of the real PowerTrace export.

The CSV's elapsed_sec is a target slot, NOT an actual sampling timestamp.
The script never modifies raw_data or imputes an interval across pause/charge.
"""
from __future__ import annotations

import argparse
import csv
from datetime import datetime
from hashlib import file_digest
import json
import math
from pathlib import Path
import re
from statistics import mean

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

HERE = Path(__file__).resolve().parent
ORDER = ('IDLE', 'VIDEO', 'NAVCAM', 'GAME')
LABELS = dict(zip(ORDER, ('Standby', 'Short video', 'Navigation / camera', 'High-FPS game')))
COLORS = dict(zip(ORDER, ('#357284', '#425caa', '#9d5981', '#b05b24')))


def read(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f))


def write(path, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def ms(ts):
    t = datetime.fromisoformat(ts)
    assert t.utcoffset() is not None
    return round(t.timestamp() * 1_000)


def marker(note, pattern, default=None):
    m = re.search(pattern, note)
    return m.group(1) if m else default


def audit(source, out):
    for line in (source.parent / 'SHA256SUMS-raw.txt').read_text(encoding='ascii').splitlines():
        digest, name = line.split('  ', 1)
        with (source / Path(name).name).open('rb') as f:
            assert file_digest(f, 'sha256').hexdigest() == digest, f'Raw checksum mismatch: {name}'
    manifest = json.loads((source/'manifest.json').read_text(encoding='utf-8-sig'))
    batch = manifest['campaignId']
    combined = read(source / f'campaign_{batch}.csv')
    summaries = {r['condition_id']: r for r in read(source / f'campaign_summary_{batch}.csv')}
    assert len(combined) == 24 and len(summaries) == 4 and tuple(manifest['conditionOrder']) == ORDER
    progress = {r['conditionId']: r for r in manifest['conditions']}
    samples, windows, qa = [], [], []
    for c in ORDER:
        rows = read(source / f'session_{c}.csv')
        pr = progress[c]
        assert len(rows) == 6 and [int(r['sample_index']) for r in rows] == list(range(1, 7))
        assert [int(r['elapsed_sec']) for r in rows] == list(range(0, 1800, 300))
        assert len(rows[0]) == 32
        merged = [r for r in combined if r['condition_id'] == c]
        assert len(merged) == 6 and all(len(r) == 33 for r in merged)
        for r, s in zip(rows, merged):
            assert s['campaign_id'] == batch and s['block'] == pr['block']
            assert {k:v for k,v in r.items() if k != 'session_id'} == {k:v for k,v in s.items() if k not in ('campaign_id','block')}
        times = [ms(r['timestamp_iso']) for r in rows]
        assert times == [ms(r['timestamp_utc']) for r in rows] and all(a < b for a,b in zip(times,times[1:]))
        pauses = [int(marker(r['note'], r'power_paused_ms=(\d+)', 0)) for r in rows]
        soc = [int(r['battery_level_pct']) for r in rows]
        temp = [float(r['battery_temp_c']) for r in rows]
        by_time = {t:i for i,t in enumerate(times)}
        covered, energy, local_windows = 0, 0.0, []
        for i, r in enumerate(rows):
            late = marker(r['note'], r'late_sample\(\+(\d+)s\)')
            samples.append(dict(condition=c, sample_index=i+1, timestamp_iso=r['timestamp_iso'],
                slot_min=int(r['elapsed_sec'])/60, actual_from_first_min=(times[i]-times[0])/60_000,
                actual_net_from_condition_start_min=(times[i]-pr['startMs']-pauses[i])/60_000,
                actual_gap_s=(times[i]-times[i-1])/1000 if i else '',
                cumulative_pause_s=pauses[i]/1000, late_note_s=int(late) if late else '',
                charging=int(r['charging']), screen_on=int(r['screen_on']),
                soc_pct=soc[i], battery_temp_c=temp[i], cpu_temp_c=r['cpu_temp_c'],
                cpu_freq_avg_mhz=r['cpu_freq_mhz_avg'], thermal_status=r['thermal_status'],
                foreground_pkg=r['foreground_pkg'], power_w_saved=r['power_w']))
            if not r['power_w']:
                continue
            assert marker(r['note'], r'power_source=([^; ]+)') == 'charge_counter'
            span = int(marker(r['note'], r'power_window_ms=(\d+)'))
            j = by_time[times[i]-span]
            assert j < i and all(r0['charging']=='0' for r0 in rows[j:i+1]) and len(set(pauses[j:i+1])) == 1
            assert not local_windows or times[j] >= local_windows[-1]['_end_ms']
            dq = int(rows[j]['charge_counter_uah']) - int(r['charge_counter_uah'])
            volts_mv = (int(rows[j]['battery_voltage_mv']) + int(r['battery_voltage_mv'])) // 2
            wh = dq * volts_mv / 1e9
            watts = wh * 3_600_000 / span
            assert dq > 0 and abs(watts-float(r['power_w'])) < 0.00051
            covered += span
            energy += wh
            window = dict(condition=c, start_sample=j+1, end_sample=i+1,
                start_time=rows[j]['timestamp_iso'], end_time=r['timestamp_iso'], duration_s=span/1000,
                from_first_min=(times[j]-times[0])/60_000, to_first_min=(times[i]-times[0])/60_000,
                delta_charge_uah=dq, avg_endpoint_voltage_mv=volts_mv, energy_wh=f'{wh:.9f}',
                power_w=f'{watts:.9f}', battery_temp_start_c=temp[j], battery_temp_end_c=temp[i])
            windows.append(window)
            local_windows.append({**window, '_end_ms':times[i]})
        saved = summaries[c]
        avg = energy * 3_600_000/covered
        assert abs(float(saved['avg_power_w'])-avg) < 0.001 and abs(float(saved['energy_wh'])-energy) < 0.001
        actual_gaps = [(b-a)/1000 for a,b in zip(times,times[1:])]
        qa.append(dict(condition=c, samples=len(rows), first=rows[0]['timestamp_iso'], last=rows[-1]['timestamp_iso'],
            first_last_s=(times[-1]-times[0])/1000, actual_net_condition_min=(pr['endMs']-pr['startMs']-pr['pausedTotalMs'])/60_000,
            pause_s=pr['pausedTotalMs']/1000, min_gap_s=min(actual_gaps), max_gap_s=max(actual_gaps),
            max_gap_error_s=max(abs(g-300) for g in actual_gaps), late_samples=sum('late_sample' in r['note'] for r in rows),
            charging_samples=sum(r['charging']=='1' for r in rows), observed_windows=len(local_windows),
            observed_min=covered/60_000, energy_wh=f'{energy:.9f}', avg_power_w=f'{avg:.9f}',
            max_window_w=max(float(r['power_w']) for r in local_windows), sample_soc_first=soc[0], sample_soc_last=soc[-1],
            battery_temp_first_c=temp[0], battery_temp_last_c=temp[-1], battery_temp_max_c=max(temp),
            window_temp_net_c=round(sum(r['battery_temp_end_c']-r['battery_temp_start_c'] for r in local_windows),3),
            thermal_max=max(int(r['thermal_status']) for r in rows),
            battery_rise_gaps=';'.join(f'{i}->{i+1}: {soc[i-1]}->{soc[i]}%' for i in range(1,6) if soc[i]>soc[i-1])))
    out.mkdir(parents=True, exist_ok=True)
    write(out / 'sample_timing.csv', samples)
    write(out / 'observed_power_windows.csv', windows)
    write(out / 'condition_audit.csv', qa)
    chart_signals(samples, out)
    chart_power(windows, qa, out)
    for row in qa:
        print(row['condition'], row['avg_power_w'], 'W;', row['observed_min'], 'min observed; late', row['late_samples'])


def chart_signals(samples, out):
    fig, plots = plt.subplots(2, 2, figsize=(11,7), layout='constrained')
    for c, ax in zip(ORDER, plots.flat):
        rs = [r for r in samples if r['condition']==c]
        x = [r['actual_from_first_min'] for r in rs]
        color = COLORS[c]
        soc = ax.twinx()
        for a,b,xa,xb in zip(rs,rs[1:],x,x[1:]):
            if a['charging'] or b['charging'] or a['cumulative_pause_s'] != b['cumulative_pause_s']:
                ax.axvspan(xa, xb, color='#aab5c1', alpha=.18)
                continue
            ax.plot([xa,xb], [a['battery_temp_c'],b['battery_temp_c']], color=color, lw=1.4)
            soc.plot([xa,xb], [a['soc_pct'],b['soc_pct']], color='#262c36', lw=1.3, ls='--')
        ax.scatter(x,[r['battery_temp_c'] for r in rs],color=color,zorder=3)
        soc.scatter(x,[r['soc_pct'] for r in rs],color='#262c36',marker='s',s=16)
        for r,t in zip(rs,x):
            if r['charging']:
                ax.axvline(t,color='#ad2224',ls=':',lw=1.5)
            if r['late_note_s'] != '':
                ax.scatter(t,r['battery_temp_c'],s=83,facecolors='none',edgecolors='#992c24',zorder=4)
        ax.set(xlabel='Actual minutes from first sample',ylabel='Battery temperature (°C)',title=LABELS[c],ylim=(24,42))
        soc.set(ylabel='Reported battery (%)',ylim=(0,37))
        ax.grid(alpha=.2)
    fig.legend(handles=[Line2D([],[],marker='o',color='#425caa',label='Temperature'),
        Line2D([],[],marker='s',ls='--',color='#262c36',label='Battery %'),
        Line2D([],[],ls=':',color='#ad2224',label='Charging at sample'),
        Line2D([],[],marker='o',mfc='none',ls='',color='#992c24',label='Late sample')],loc='outside lower center',ncol=4,fontsize=8)
    fig.suptitle('Observed temperature and reported battery level | shaded gaps: pause or charging')
    fig.savefig(out/'temperature_and_battery.png',dpi=155)
    plt.close(fig)


def chart_power(windows, qa, out):
    fig, (ax, bars) = plt.subplots(2,1,figsize=(10,7),layout='constrained')
    for c,row in zip(ORDER,qa):
        w = [s for s in windows if s['condition']==c]
        for i,s in enumerate(w):
            ax.hlines(float(s['power_w']),s['from_first_min'],s['to_first_min'],color=COLORS[c],lw=3,label=LABELS[c] if i==0 else None)
    ax.set(xlabel='Actual minutes from first sample in each condition',ylabel='Window-average power (W)',
        title='Charge-counter measurement windows only (gaps left empty)')
    ax.legend(ncol=2,fontsize=8); ax.grid(alpha=.20)
    x=list(range(4)); watts=[float(r['avg_power_w']) for r in qa]
    bars.bar(x,watts,color=[COLORS[c] for c in ORDER],width=.6)
    for k,(w,r) in enumerate(zip(watts,qa)):
        bars.text(k,w+.07,f"{w:.3f} W / {r['observed_min']:.1f} min",ha='center',va='bottom',fontsize=9)
    bars.set(xticks=x,xticklabels=[LABELS[c] for c in ORDER],ylabel='Observed mean power (W)',ylim=(0,4.6),
        title='Descriptive comparison, not four controlled 30-minute trials')
    bars.grid(axis='y',alpha=.20)
    fig.savefig(out/'power_windows.png',dpi=155)
    plt.close(fig)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,default=HERE/'raw_data')
    p.add_argument('--out',type=Path,default=HERE/'results')
    a=p.parse_args()
    audit(a.source,a.out)


if __name__=='__main__':
    main()
