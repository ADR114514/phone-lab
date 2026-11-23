"""Reproduce the Topic 07 report using the original App export only."""
from __future__ import annotations

import csv
from datetime import datetime
from hashlib import file_digest
import json
import math
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
from matplotlib import font_manager
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parent
RAW = HERE / 'raw_data'
OUT = HERE / 'results'


def read_csv(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f))


def write_csv(name, data):
    with (OUT / name).open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(data[0]))
        writer.writeheader()
        writer.writerows(data)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def verify_archive():
    for row in (HERE / 'SHA256SUMS-raw.txt').read_text(encoding='ascii').splitlines():
        expected, rel = row.split('  ', 1)
        with (HERE / rel).open('rb') as f:
            assert file_digest(f, 'sha256').hexdigest() == expected, rel


def fmt(value, n=3):
    return f'{value:.{n}f}'


def main():
    verify_archive()
    meta = read_json(RAW / 'manifest.json')
    nameplate = meta['nameplate']
    summary = read_json(RAW / 'summary.json')
    app = read_csv(RAW / f"chargeaudit_{meta['sessionId']}.csv")
    assert len(app) == meta['points'] == 19
    assert [int(r['point_index']) for r in app] == list(range(len(app)))
    assert all(not r['tester_wh_cum'] for r in app)
    stamps = [datetime.fromisoformat(r['timestamp_iso']) for r in app]
    assert all(t.utcoffset().total_seconds() == 8 * 3600 for t in stamps)
    x = [(t - stamps[0]).total_seconds() / 60 for t in stamps]
    steps = [(b - a).total_seconds() for a, b in zip(stamps, stamps[1:])]
    assert all(s > 0 for s in steps)
    assert all(abs(s - (float(b['elapsed_min']) - float(a['elapsed_min'])) * 60) < .0015
               for a, b, s in zip(app, app[1:], steps))
    v = [float(r['usb_voltage_v']) for r in app]
    a = [float(r['usb_current_a']) for r in app]
    p = [float(r['usb_power_w']) for r in app]
    soc = [int(r['phone_battery_pct']) for r in app]
    assert all(math.isfinite(i) and i >= 0 for i in v + a + p)
    assert all(abs(u * i - w) < 1e-8 for u, i, w in zip(v, a, p))
    limit = float(nameplate['ratedOutputCurrentA'])
    bad = {i for i, amps in enumerate(a) if amps > limit}
    assert bad == {13}
    totals = {'all_wh': 0.0, 'used_wh': 0.0, 'used_s': 0.0}
    intervals, points = [], []
    for i, r in enumerate(app):
        points.append({'点号': i+1, 'App采样时间（北京时间）': r['timestamp_iso'],
                       '距首点（分钟）': fmt(x[i]), '距上点（秒）': fmt(steps[i-1]) if i else '',
                       '电压（V，App原值）': r['usb_voltage_v'], '电流（A，App原值）': r['usb_current_a'],
                       '功率（W，App原值）': r['usb_power_w'], '手机电量（%）': r['phone_battery_pct'],
                       '手机电池温度（°C）': r['phone_battery_temp_c'], 'App充电标记': r['charging'],
                       'App累计Wh': r['tester_wh_cum'],
                       '分析说明': '超过铭牌2 A，保留原值；不参与积分' if i in bad else ''})
        if not i:
            continue
        seconds = steps[i-1]
        amount = (p[i-1] + p[i]) * seconds / 7200
        totals['all_wh'] += amount
        skip = i in bad or i-1 in bad
        if not skip:
            totals['used_wh'] += amount
            totals['used_s'] += seconds
        intervals.append({'起点': i, '止点': i+1, '起点时间': app[i-1]['timestamp_iso'],
                          '止点时间': r['timestamp_iso'], '时长（秒）': fmt(seconds),
                          '计入部分能量': '否' if skip else '是',
                          '该段能量（Wh，只计保留段）': '' if skip else fmt(amount, 8),
                          '原因': '端点含超过铭牌电流的样本；不跨段补值' if skip else ''})
    assert abs(totals['all_wh'] - summary['e_out_wh']) < 1e-7
    assert sum(r['计入部分能量'] == '否' for r in intervals) == 2
    assert meta['endReason'] is None and not meta['fullDischargeConfirmed'] and app[-1]['charging'] == '1'
    metrics = {
        'session_id': meta['sessionId'], 'samples': len(app), 'intervals_used': len(intervals)-2,
        'recorded_minutes': x[-1], 'interval_minutes_min': min(steps)/60, 'interval_minutes_max': max(steps)/60,
        'minutes_used': totals['used_s']/60, 'minutes_excluded': (sum(steps)-totals['used_s'])/60,
        'app_raw_wh_not_usable': totals['all_wh'], 'estimated_wh_for_retained_intervals': totals['used_wh'],
        'average_w_for_retained_intervals': totals['used_wh'] * 3600 / totals['used_s'],
        'rated_cell_wh': nameplate['ratedEnergyWh'],
        'rated_output_wh_at_5v_2a': nameplate['ratedOutputMah'] * nameplate['ratedOutputV']/1000,
        'last_sample_still_charging': True, 'full_discharge_confirmed': False,
        'full_run_output_wh': None, 'output_to_nameplate_ratio': None, 'usb_round_trip_efficiency': None,
    }
    OUT.mkdir(exist_ok=True)
    write_csv('App实测时序_19点.csv', points)
    write_csv('App放电区间复算.csv', intervals)
    (OUT/'复算摘要.json').write_text(json.dumps(metrics, indent=2) + chr(10), encoding='utf-8')
    plot(x, v, a, p, soc, bad, limit)
    print(json.dumps(metrics, indent=2))


def plot(x, voltage, current, power, soc, bad, limit):
    font = Path('C:/Windows/Fonts/msyh.ttc')
    if font.is_file():
        font_manager.fontManager.addfont(str(font))
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'Noto Sans CJK SC', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    f, axes = plt.subplots(4, 1, sharex=True, figsize=(9, 8.5), layout='constrained')
    for ax, y, color, label in zip(axes, [voltage, current, power, soc],
                                   ['#305cab', '#067761', '#945021', '#6c488e'],
                                   ['USB 电压（V）', 'USB 电流（A）', 'USB 功率（W）', '手机电量（%）']):
        line = [math.nan if i in bad and ax in axes[1:3] else point for i, point in enumerate(y)]
        ax.plot(x, line, 'o-', markersize=3, color=color)
        ax.set_ylabel(label)
        ax.grid(alpha=.20)
        for i in bad:
            ax.axvspan(x[i-1], x[i+1], color='#b45d3d', alpha=.14)
    axes[1].axhline(limit, color='#777777', ls='--', lw=.8)
    for i in bad:
        axes[1].scatter(x[i], current[i], color='#b6342d', marker='x', zorder=5)
        axes[1].text(x[i]+4, current[i]-.35, 'App 原值 10.7 A', color='#80271f')
    axes[2].text(.04, .1, '原始 93.09 W 见时序表；该点两侧区间不积分', transform=axes[2].transAxes, fontsize=8)
    axes[-1].set_xlabel('距 App 首点的实际分钟数')
    f.suptitle('课题 07 · App 原始数值 | 着色为未计能量区间')
    f.savefig(OUT / 'App放电曲线.png', dpi=155)
    plt.close(f)


if __name__ == '__main__':
    main()
