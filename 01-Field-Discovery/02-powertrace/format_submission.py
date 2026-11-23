"""Format the existing PowerTrace PC-20261123-002 measurements for submission.

Run analyze_campaign.py first. Only use observed timestamps and the audited
non-overlapping power windows. Blank power cells are unavailable, not 0 W.
"""
from __future__ import annotations

import csv
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
BATCH = 'PC-20261123-002'
LABELS = {'IDLE':'待机','VIDEO':'短视频','NAVCAM':'导航／拍摄','GAME':'高帧率游戏'}
REMARKS = {
    'IDLE':'首个采样点亮屏，后五点记录为熄屏；只确认采样时的状态',
    'VIDEO':'两次暂停；末点为充电，有效功率仅覆盖第2至5点，网络曾变化',
    'NAVCAM':'中途暂停；仅第2至6点有有效功率窗口，起始温度偏高',
    'GAME':'首段暂停且电量回升；第2至6点迟到；前台记录不能确认游戏持续运行',
}


def read(path):
    with path.open(encoding='utf-8-sig', newline='') as f:
        return list(csv.DictReader(f))


def write(path, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def key(c,s):
    return (c,int(s))


def dec(text, n):
    return format(float(text), f'.{n}f') if text else ''


def state(value, yes, no):
    return yes if value == '1' else no if value == '0' else ''


def main():
    source = read(HERE/'raw_data'/f'campaign_{BATCH}.csv')
    timing = {key(r['condition'],r['sample_index']):r for r in read(HERE/'results'/'sample_timing.csv')}
    windows = {key(r['condition'],r['end_sample']):r for r in read(HERE/'results'/'observed_power_windows.csv')}
    audit = {r['condition']:r for r in read(HERE/'results'/'condition_audit.csv')}
    assert len(source) == len(timing) == 24 and len(windows) == 16 and set(audit) == set(LABELS)
    tables = []
    by_condition = {c:[] for c in LABELS}
    for r in source:
        c = r['condition_id']
        i = int(r['sample_index'])
        t = timing[c,i]
        w = windows.get((c,i))
        assert r['timestamp_iso'] == t['timestamp_iso']
        assert r['power_w'] == t['power_w_saved'] and bool(r['power_w']) == (w is not None)
        rows = by_condition[c]
        previous = rows[-1] if rows else None
        assert i == len(rows)+1 and r['campaign_id'] == BATCH
        rows.append(r)
        notes = []
        if r['charging'] == '1': notes.append('本点充电；不计入放电功率')
        if c == 'IDLE' and r['screen_on'] == '1': notes.append('待机首点亮屏')
        if previous:
            k = timing[c,i-1]
            if t['cumulative_pause_s'] != k['cumulative_pause_s']: notes.append('与上个采样点之间有暂停')
            if int(r['battery_level_pct']) > int(previous['battery_level_pct']):
                notes.append('电量回升 '+previous['battery_level_pct']+'→'+r['battery_level_pct']+'%')
            if r['net_type'] != previous['net_type']: notes.append('网络类型变化')
        if t['late_note_s']: notes.append('迟到 '+t['late_note_s']+' 秒（按App日志）')
        if c == 'GAME' and r['foreground_pkg'] == 'com.mobilelab.powertrace': notes.append('采样时前台为PowerTrace')
        if not w and not notes: notes.append('首个采样点；暂无差分窗口' if i == 1 else '暂无有效功率窗口')
        stamp = datetime.fromisoformat(r['timestamp_iso'])
        assert stamp.utcoffset().total_seconds() == 8*3600
        tables.append({
            '批次':BATCH, '组别':r['block'], '工况':LABELS[c], '点序号':i,
            '计划分钟':int(r['elapsed_sec'])//60, '实测时间（北京时间）':stamp.isoformat(sep=' ',timespec='milliseconds').removesuffix('+08:00'),
            '距本工况首点（分钟）':dec(t['actual_from_first_min'],3), '距上一点（秒）':dec(t['actual_gap_s'],3),
            '电量（%）':r['battery_level_pct'], '电池电压（mV）':r['battery_voltage_mv'],
            '电池温度（°C）':r['battery_temp_c'], 'CPU温度（°C）':r['cpu_temp_c'],
            'CPU平均频率读数（MHz）':r['cpu_freq_mhz_avg'], '系统热状态等级':r['thermal_status'],
            '采样时充电':state(r['charging'],'是','否'), '采样时屏幕':state(r['screen_on'],'亮屏','熄屏'),
            '系统亮度设置（%）':r['screen_brightness_pct'], '网络':{'WIFI':'Wi-Fi','CELLULAR':'蜂窝'}.get(r['net_type'],r['net_type']),
            '剩余电荷计（µAh）':r['charge_counter_uah'], '功率窗口（起点→止点）':f"{w['start_sample']}→{i}" if w else '',
            '有效窗口时长（秒）':dec(w['duration_s'],3) if w else '', '窗口平均功率（W，App原值）':r['power_w'],
            '窗口电池侧能量（Wh，复算）':dec(w['energy_wh'],6) if w else '', '测量说明':'；'.join(notes),
        })
    assert all(len(rows)==6 for rows in by_condition.values())
    summary = []
    for c,label in LABELS.items():
        a = audit[c]
        rows = by_condition[c]
        active_windows = [w for (cond,_),w in windows.items() if cond==c]
        first_window = rows[int(active_windows[0]['start_sample'])-1]
        last_window = rows[int(active_windows[-1]['end_sample'])-1]
        summary.append({
            '工况':label, '采样条数':a['samples'], '首末实测跨度（分钟）':f"{float(a['first_last_s'])/60:.3f}",
            '有效放电窗口数':a['observed_windows'], '有效窗口累计（分钟）':dec(a['observed_min'],3),
            '窗口加权平均功率（W）':dec(a['avg_power_w'],3), '窗口平均功率最大值（W，非瞬时峰值）':dec(a['max_window_w'],3),
            '有效窗口电池侧能量（Wh）':dec(a['energy_wh'],3),
            '有效窗口起始电量（%）':first_window['battery_level_pct'], '有效窗口终止电量（%）':last_window['battery_level_pct'],
            '首点电池温度（°C）':a['battery_temp_first_c'], '末点电池温度（°C）':a['battery_temp_last_c'],
            '最高电池温度（°C）':a['battery_temp_max_c'], '有效窗口电池温度变化（°C）':a['window_temp_net_c'],
            '最高系统热状态等级':a['thermal_max'], '充电标记点数':a['charging_samples'], '迟到标记点数':a['late_samples'], '记录说明':REMARKS[c],
        })
    out = HERE/'submission'
    out.mkdir(exist_ok=True)
    write(out/'课题02_24条时序记录.csv', tables)
    write(out/'课题02_四工况汇总.csv', summary)
    # Read back, checking row order, exact raw SOC/temperature/power strings and the missing cells.
    check = read(out/'课题02_24条时序记录.csv')
    assert len(check) == 24 and sum(not r['窗口平均功率（W，App原值）'] for r in check) == 8
    for clean, raw in zip(check,source):
        assert clean['组别'] == raw['block'] and clean['点序号'] == raw['sample_index']
        assert clean['电量（%）'] == raw['battery_level_pct'] and clean['电池温度（°C）'] == raw['battery_temp_c']
        assert clean['窗口平均功率（W，App原值）'] == raw['power_w']
    assert len(read(out/'课题02_四工况汇总.csv')) == 4
    print('Formatted and verified: 24 original samples, 16 observed power windows, 4 summaries; no imputation.')


if __name__ == '__main__':
    main()
