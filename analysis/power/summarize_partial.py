"""课题02 · 不完整批次快速汇总（边采边看数据质量用）。

正式分析请用 analysis/power/analyze.py（要求 4 工况 × 6 点 = 24 行完整批次）。
本脚本只读取当前已存在的 session_<COND>.csv（1–4 个工况均可），输出：
  - tables/partial_condition_metrics.csv：各已采工况的平均功率/峰值/能量/掉电/温升/thermal/迟到数
  - partial_power_report.md：简明体检报告（缺哪些工况、每工况采了几个点、是否有充电/迟到样本）

用法：
    python analysis/power/summarize_partial.py --data data/raw/power/<session> --out analysis/out/power-partial
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ANALYSIS_DIR = HERE.parent
REPO_ROOT = ANALYSIS_DIR.parent
if str(ANALYSIS_DIR) not in sys.path:
    sys.path.insert(0, str(ANALYSIS_DIR))

from common import io_utils, md_utils  # noqa: E402

CONDITION_ORDER = ["IDLE", "VIDEO", "NAVCAM", "GAME"]
CONDITION_LABELS = {"IDLE": "待机", "VIDEO": "短视频", "NAVCAM": "导航拍摄", "GAME": "高帧率游戏"}
NUMERIC_COLUMNS = [
    "sample_index", "elapsed_sec", "battery_level_pct", "battery_voltage_mv", "battery_temp_c",
    "current_now_ua", "charge_counter_uah", "power_w", "thermal_status", "cpu_temp_c",
    "cpu_freq_mhz_avg", "screen_brightness_pct", "net_rx_kbps", "net_tx_kbps",
]
REQUIRED_COLUMNS = ["sample_index", "elapsed_sec", "battery_level_pct", "battery_temp_c", "power_w", "note"]


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="不完整批次功耗数据快速汇总")
    parser.add_argument("--data", required=True, help="PowerTrace 导出目录（允许只有部分工况）")
    parser.add_argument("--out", default=None, help="输出目录，默认 analysis/out/power-partial")
    return parser.parse_args(argv)


def load_sessions(data_dir: Path) -> dict[str, pd.DataFrame]:
    sessions: dict[str, pd.DataFrame] = {}
    for condition_id in CONDITION_ORDER:
        path = data_dir / f"session_{condition_id}.csv"
        if not path.is_file():
            continue
        frame = io_utils.read_csv(path, required=REQUIRED_COLUMNS, name=path.name)
        sessions[condition_id] = io_utils.coerce_numeric(frame, NUMERIC_COLUMNS)
    return sessions


def summarize(condition_id: str, frame: pd.DataFrame) -> dict:
    power = pd.to_numeric(frame.get("power_w"), errors="coerce")
    elapsed = pd.to_numeric(frame.get("elapsed_sec"), errors="coerce")
    battery = pd.to_numeric(frame.get("battery_level_pct"), errors="coerce")
    temp = pd.to_numeric(frame.get("battery_temp_c"), errors="coerce")
    thermal = pd.to_numeric(frame.get("thermal_status"), errors="coerce")
    notes = frame.get("note", pd.Series(dtype=str)).fillna("").astype(str)

    energy_wh = np.nan
    valid = power.notna() & elapsed.notna()
    if valid.sum() >= 2:
        energy_wh = float(np.trapezoid(power[valid], elapsed[valid]) / 3600.0)
    elif valid.sum() == 1:
        # 单点无法积分，按 5 min 窗口估算并标注（报告中说明）
        energy_wh = float(power[valid].iloc[0] * 300.0 / 3600.0)

    return {
        "condition_id": condition_id,
        "condition_label": CONDITION_LABELS.get(condition_id, condition_id),
        "rows": int(len(frame)),
        "sample_indices": ",".join(str(int(v)) for v in frame["sample_index"].dropna()),
        "elapsed_sec_range": (
            f"{int(elapsed.min())}–{int(elapsed.max())}" if elapsed.notna().any() else ""
        ),
        "avg_power_w": float(power.mean()) if power.notna().any() else np.nan,
        "peak_power_w": float(power.max()) if power.notna().any() else np.nan,
        "energy_wh": energy_wh,
        "battery_start_pct": float(battery.iloc[0]) if battery.notna().any() else np.nan,
        "battery_end_pct": float(battery.iloc[-1]) if battery.notna().any() else np.nan,
        "temp_start_c": float(temp.iloc[0]) if temp.notna().any() else np.nan,
        "temp_end_c": float(temp.iloc[-1]) if temp.notna().any() else np.nan,
        "thermal_max": float(thermal.max()) if thermal.notna().any() else np.nan,
        "late_samples": int(notes.str.contains("late_sample").sum()),
        "charging_samples": int(notes.str.contains("charging").sum()),
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    data_dir = Path(args.data).resolve()
    out_dir = Path(args.out or (REPO_ROOT / "analysis" / "out" / "power-partial")).resolve()
    if not data_dir.is_dir():
        print(f"[power-partial] 目录不存在: {data_dir}")
        return 1
    sessions = load_sessions(data_dir)
    if not sessions:
        print(f"[power-partial] 没有找到 session_<IDLE|VIDEO|NAVCAM|GAME>.csv: {data_dir}")
        return 1

    rows = [summarize(cid, frame) for cid, frame in sessions.items()]
    table = pd.DataFrame(rows)
    table_dir = io_utils.ensure_dir(out_dir / "tables")
    io_utils.write_csv(table, table_dir / "partial_condition_metrics.csv")

    lines = ["# 课题02 · 不完整批次快速体检", "", f"- 数据目录：`{data_dir}`", ""]
    lines.append("## 1. 采集完整性")
    lines.append("")
    for condition_id in CONDITION_ORDER:
        label = CONDITION_LABELS[condition_id]
        if condition_id in sessions:
            frame = sessions[condition_id]
            done = sorted(int(v) for v in frame["sample_index"].dropna())
            lines.append(f"- {condition_id}（{label}）：已采 {len(frame)}/6 点，sample_index={done}")
        else:
            lines.append(f"- {condition_id}（{label}）：**未采集**")
    lines.append("")
    lines.append("## 2. 已采工况指标（单点能量按 5 min 估算）")
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "点数", "平均功率 W", "峰值 W", "能量 Wh", "电量 %", "温度 °C", "thermal 峰值", "迟到点", "充电点"],
        [[row["condition_id"], row["rows"], md_utils.fmt_number(row["avg_power_w"], 3),
          md_utils.fmt_number(row["peak_power_w"], 3), md_utils.fmt_number(row["energy_wh"], 4),
          f"{md_utils.fmt_number(row['battery_start_pct'], 0)}→{md_utils.fmt_number(row['battery_end_pct'], 0)}",
          f"{md_utils.fmt_number(row['temp_start_c'], 1)}→{md_utils.fmt_number(row['temp_end_c'], 1)}",
          md_utils.fmt_number(row["thermal_max"], 0), row["late_samples"], row["charging_samples"]]
         for row in rows]))
    lines.append("")
    lines.append("## 3. 结论与建议")
    lines.append("")
    missing = [cid for cid in CONDITION_ORDER if cid not in sessions]
    if missing:
        lines.append(f"- 批次尚不完整，缺 {missing}；补采后才能运行 `analyze.py` 的 24 行完整分析。")
    if any(row["charging_samples"] for row in rows):
        lines.append("- 存在充电状态样本：该工况功耗数据不可用于结论，请拔线重采。")
    late = sum(row["late_samples"] for row in rows)
    if late:
        lines.append(f"- 存在 {late} 个迟到采样点（note 含 late_sample）：待机准点性受系统省电策略影响，"
                     "请关闭系统'睡眠待机优化'并允许后台活动后重采。")
    if not missing and not late:
        lines.append("- 数据完整且无迟到点，可运行 `analysis/power/analyze.py` 生成正式报告。")
    lines.append("")
    lines.append("> 本报告由 `analysis/power/summarize_partial.py` 生成；正式分析仍以 24 行完整批次为准。")
    md_utils.write_text(out_dir / "partial_power_report.md", "\n".join(lines) + "\n")

    print(f"[power-partial] 读取 {len(sessions)}/4 个工况，输出 {out_dir}")
    for row in rows:
        print(f"  {row['condition_id']}: {row['rows']} 点, avg={row['avg_power_w']:.3f} W, late={row['late_samples']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
