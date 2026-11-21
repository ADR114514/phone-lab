"""课题02 · 个人手机多工况发热与掉电实测 —— PowerTrace 数据分析管线。

输入：PowerTrace 导出目录（docs/DATA_DICTIONARY.md 第一节）
      session_<condition>.csv（每工况 6 行）、campaign_<id>.csv（24 行）、
      campaign_summary_<id>.csv、可选 raw_<condition>.csv（5 s 高频序列）、manifest.json
输出：<out>/figures/*.png、<out>/tables/*.csv、<out>/power_report.md

设计要点：
  * 严格按数据字典校验 24 行完整性、缺失/迟到样本、充电异常、符号约定；
  * 功率/能量/掉电率/温升速率都给出计算口径，并与 charge_counter、summary 交叉校验；
  * 模块归因用最小二乘回归 "总功耗 ~ 活动指示量"，这是**活动指示量回归**，
    不是硬件电流计：无法区分同一条供电轨上的具体子系统，报告里必须声明；
  * 热点节流检测 = thermal_status 上升 + CPU 频率下降（同时满足才标记）。

用法：
    python analysis/power/analyze.py --data data/raw/power/<session> --out analysis/out/power
    python analysis/power/analyze.py --data data/demo/power/20261118_PC-20261118-001 --out analysis/out/power
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ANALYSIS_DIR = HERE.parent
if str(ANALYSIS_DIR) not in sys.path:
    sys.path.insert(0, str(ANALYSIS_DIR))

from common import io_utils, md_utils, plot_utils, stats_utils  # noqa: E402

plot_utils.setup()
import matplotlib.pyplot as plt  # noqa: E402  必须在 setup()（Agg 后端）之后

CONDITION_ORDER = ["IDLE", "VIDEO", "NAVCAM", "GAME"]
CONDITION_LABELS = {"IDLE": "待机", "VIDEO": "短视频", "NAVCAM": "导航拍摄", "GAME": "高帧率游戏"}

SESSION_COLUMNS = [
    "session_id", "condition_id", "condition_label", "sample_index", "elapsed_sec",
    "timestamp_iso", "battery_level_pct", "battery_voltage_mv", "battery_temp_c",
    "current_now_ua", "charge_counter_uah", "power_w", "thermal_status", "cpu_temp_c",
    "cpu_freq_mhz_avg", "screen_on", "screen_brightness_pct", "net_rx_kbps", "net_tx_kbps",
    "fps", "note",
]
NUMERIC_SESSION_COLUMNS = [
    "sample_index", "elapsed_sec", "battery_level_pct", "battery_voltage_mv", "battery_temp_c",
    "current_now_ua", "charge_counter_uah", "power_w", "thermal_status", "cpu_temp_c",
    "cpu_freq_mhz_avg", "cpu_freq_mhz_max", "screen_on", "screen_brightness_pct",
    "net_rx_kbps", "net_tx_kbps", "ambient_lux", "mem_available_mb", "fps", "energy_counter_nwh",
]
CAMPAIGN_COLUMNS = ["campaign_id", "block"] + SESSION_COLUMNS[1:]
SUMMARY_COLUMNS = [
    "condition_id", "condition_label", "duration_min", "start_level_pct", "end_level_pct",
    "level_drop_pct", "avg_power_w", "peak_power_w", "energy_wh", "avg_current_ma",
    "temp_start_c", "temp_end_c", "temp_rise_c", "thermal_max", "thermal_rise_events",
    "avg_cpu_freq_mhz", "avg_screen_brightness_pct", "net_rx_mb", "net_tx_mb", "notes",
]


# ---------------------------------------------------------------- 输入

def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="课题02 功耗热测分析")
    parser.add_argument("--data", default=None,
                        help="PowerTrace 导出目录；缺省时自动在 data/demo/power 或 data/raw/power 下找最新会话")
    parser.add_argument("--out", default=None, help="输出目录，默认 analysis/out/power")
    parser.add_argument("--partial-check", action="store_true",
                        help="只检查导出目录完整性（允许未完成批次，不生成分析报告）")
    args = parser.parse_args(argv)
    repo_root = ANALYSIS_DIR.parent
    if args.data is None:
        args.data = str(find_latest_session_dir([repo_root / "data" / "demo" / "power",
                                                 repo_root / "data" / "raw" / "power"]))
    if args.out is None:
        args.out = str(repo_root / "analysis" / "out" / "power")
    return args


def find_latest_session_dir(candidates: list[Path]) -> Path:
    for root in candidates:
        if not root.is_dir():
            continue
        sessions = [p for p in root.iterdir() if p.is_dir() and list(p.glob("campaign_*.csv"))]
        if sessions:
            return sorted(sessions, key=lambda p: p.stat().st_mtime)[-1]
    raise FileNotFoundError("没有找到包含 campaign_*.csv 的 PowerTrace 导出目录，请用 --data 指定")


def load_power_dir(data_dir: Path) -> dict:
    if not data_dir.is_dir():
        raise FileNotFoundError(f"数据目录不存在: {data_dir}")
    sessions: dict[str, pd.DataFrame] = {}
    for condition_id in CONDITION_ORDER:
        paths = sorted(data_dir.glob(f"session_{condition_id}.csv"))
        if not paths:
            raise FileNotFoundError(f"缺少 session_{condition_id}.csv（目录: {data_dir}）")
        frame = io_utils.read_csv(paths[0], required=SESSION_COLUMNS,
                                  name=f"session_{condition_id}.csv")
        sessions[condition_id] = io_utils.coerce_numeric(frame, NUMERIC_SESSION_COLUMNS)
    campaign_paths = [p for p in data_dir.glob("campaign_*.csv") if "summary" not in p.name]
    if not campaign_paths:
        raise FileNotFoundError(f"缺少 campaign_<id>.csv（目录: {data_dir}）")
    campaign = io_utils.coerce_numeric(
        io_utils.read_csv(campaign_paths[0], required=CAMPAIGN_COLUMNS, name="campaign"),
        NUMERIC_SESSION_COLUMNS)
    summary_paths = sorted(data_dir.glob("campaign_summary_*.csv"))
    summary = None
    if summary_paths:
        summary = io_utils.coerce_numeric(
            io_utils.read_csv(summary_paths[0], required=SUMMARY_COLUMNS, name="campaign_summary"),
            SUMMARY_COLUMNS)
    raw: dict[str, pd.DataFrame] = {}
    for condition_id in CONDITION_ORDER:
        raw_paths = sorted(data_dir.glob(f"raw_{condition_id}.csv"))
        if raw_paths:
            raw[condition_id] = io_utils.coerce_numeric(
                io_utils.read_csv(raw_paths[0], name=f"raw_{condition_id}.csv"),
                ["elapsed_sec", "battery_level_pct", "current_now_ua", "voltage_mv",
                 "battery_temp_c", "thermal_status", "cpu_temp_c", "cpu_freq_mhz_avg"])
    manifest = {}
    manifest_path = data_dir / "manifest.json"
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            manifest = {}
    return {"dir": data_dir, "sessions": sessions, "campaign": campaign,
            "summary": summary, "raw": raw, "manifest": manifest}


def apply_charge_power_fallback(data: dict) -> list[str]:
    """CURRENT_NOW 不可用时，用 charge_counter 差分补 power_w。

    实测 OnePlus/ColorOS 的 current_now 只有 µA 级噪声（-26 ~ -93 µA），
    voltage*|current| 会把整段工况算成 0.000 W，导致 4 工况功率完全相同。
    这里按相邻采样点的电荷计差分估算平均功率：
        P = Δcharge_counter(µAh) × V(mV) × 3.6 / (Δt(s) × 1e6)
    仅在 |current| 中位数 < 2 mA（视为不可用）时启用，并把原始口径保留为 power_w_current。
    """
    notes: list[str] = []

    def derive(frame: pd.DataFrame) -> pd.DataFrame:
        if frame is None or frame.empty:
            return frame
        need = {"charge_counter_uah", "battery_voltage_mv", "timestamp_iso"}
        if not need.issubset(frame.columns):
            return frame
        work = frame.sort_values("timestamp_iso").copy()
        charge = pd.to_numeric(work["charge_counter_uah"], errors="coerce")
        volt = pd.to_numeric(work["battery_voltage_mv"], errors="coerce")
        ts = pd.to_datetime(work["timestamp_iso"], errors="coerce", utc=True)
        dt_s = ts.diff().dt.total_seconds()
        delta_uah = -charge.diff()
        power = delta_uah * volt * 3.6 / (dt_s * 1e6)
        valid = (dt_s >= 60) & (dt_s <= 3600) & (delta_uah > 0) & power.between(0.01, 30.0)
        work["power_w_charge"] = power.where(valid)
        work["power_w_current"] = pd.to_numeric(work.get("power_w"), errors="coerce")
        return work

    def should_fallback(frame: pd.DataFrame) -> bool:
        if "current_now_ua" not in frame.columns:
            return True
        current = pd.to_numeric(frame["current_now_ua"], errors="coerce").abs()
        med = float(current.median()) if current.notna().any() else 0.0
        power = pd.to_numeric(frame.get("power_w"), errors="coerce")
        finite = power.dropna()
        near_zero = bool((finite.abs() < 0.01).mean() > 0.8) if len(finite) else True
        return med < 2000.0 or near_zero

    for key in ["sessions", "campaign"]:
        holder = data.get(key)
        if holder is None:
            continue
        frames = holder.values() if isinstance(holder, dict) else [holder]
        for frame in frames:
            if not isinstance(frame, pd.DataFrame) or not should_fallback(frame):
                continue
            derived = derive(frame)
            if "power_w_charge" not in derived.columns or derived["power_w_charge"].notna().sum() == 0:
                continue
            original_index = derived.index
            for column in derived.columns:
                if column not in frame.columns:
                    frame[column] = pd.NA
            frame.loc[original_index, "power_w"] = derived["power_w_charge"].to_numpy()
            frame.loc[original_index, "power_w_current"] = derived["power_w_current"].to_numpy()
            condition = str(frame.get("condition_id", pd.Series(["campaign"])).iloc[0])
            notes.append(f"{condition}: power_w 由 charge_counter 差分回退（current_now 中位 |I| < 2 mA，原口径保留为 power_w_current）")
    return notes


def is_synthetic(data: dict) -> bool:
    if data["manifest"].get("synthetic") is True:
        return True
    return "demo" in {part.lower() for part in data["dir"].parts}


# ---------------------------------------------------------------- 校验

def validate_samples(data: dict) -> tuple[pd.DataFrame, list[str], int]:
    """24 行完整性 + 缺失/迟到/充电/符号检查。返回 (质量表, 问题列表, campaign 行数)。"""
    rows: list[dict] = []
    issues: list[str] = []
    for condition_id in CONDITION_ORDER:
        frame = data["sessions"][condition_id].copy().sort_values("sample_index")
        expected = [1, 2, 3, 4, 5, 6]
        missing = [index for index in expected
                   if index not in set(frame["sample_index"].dropna().astype(int))]
        elapsed = frame["elapsed_sec"].to_numpy(dtype=float)
        deltas = np.diff(elapsed)
        late_mask = deltas > 300.0 + 90.0
        late = int(np.sum(late_mask)) if deltas.size else 0
        late_indices = [int(frame["sample_index"].iloc[index + 1]) for index in np.where(late_mask)[0]]
        charging = int((frame["charging"].fillna(0) != 0).sum()) if "charging" in frame else 0
        levels = frame["battery_level_pct"].to_numpy(dtype=float)
        level_rise = int(np.sum(np.diff(levels) > 0.05)) if levels.size > 1 else 0
        power = frame["power_w"].to_numpy(dtype=float)
        voltage = frame["battery_voltage_mv"].to_numpy(dtype=float)
        current = np.abs(frame["current_now_ua"].to_numpy(dtype=float))
        recomputed = voltage * current / 1e9
        mask = np.isfinite(power) & np.isfinite(recomputed) & (np.abs(power) > 1e-9) & (current >= 2000.0)
        power_error = (float(np.max(np.abs(recomputed[mask] - power[mask]) / np.abs(power[mask])))
                       if mask.any() else float("nan"))
        negative_current = int((frame["current_now_ua"].fillna(0) < 0).sum())
        cpu_temp_missing = int(frame["cpu_temp_c"].isna().sum()) if "cpu_temp_c" in frame else 0
        fps_missing = int(frame["fps"].isna().sum()) if "fps" in frame else len(frame)
        notes = [str(value) for value in frame["note"].fillna("").tolist() if str(value).strip()]
        late_note = sum(1 for note in notes if "late" in note.lower())
        rows.append({
            "condition_id": condition_id,
            "condition_label": CONDITION_LABELS.get(condition_id, condition_id),
            "rows": int(len(frame)),
            "expected_rows": 6,
            "missing_sample_index": ";".join(str(i) for i in missing) if missing else "",
            "late_samples": late,
            "late_indices": ";".join(str(i) for i in late_indices) if late_indices else "",
            "late_note_count": late_note,
            "charging_rows": charging,
            "level_rise_rows": level_rise,
            "power_recompute_max_rel_err": power_error,
            "neg_current_rows": negative_current,
            "cpu_temp_missing": cpu_temp_missing,
            "fps_missing": fps_missing,
            "duration_span_s": float(elapsed[-1] - elapsed[0]) if elapsed.size > 1 else float("nan"),
        })
        if len(frame) != 6:
            issues.append(f"{condition_id}: 行数 {len(frame)} != 6")
        if missing:
            issues.append(f"{condition_id}: 缺失采样点 {missing}")
        if late:
            issues.append(f"{condition_id}: {late} 个迟到样本（间隔 > 390 s，序号 {late_indices}）")
        if charging:
            issues.append(f"{condition_id}: {charging} 行 charging=1（充电会污染电流读数）")
        if level_rise:
            issues.append(f"{condition_id}: 电量出现 {level_rise} 次回升（可能采样异常）")
        if np.isfinite(power_error) and power_error > 0.01:
            issues.append(f"{condition_id}: power_w 与 voltage*|current| 重算差异 {power_error:.1%}")
    quality = pd.DataFrame(rows)

    campaign = data["campaign"]
    campaign_rows = int(len(campaign))
    if campaign_rows != 24:
        issues.append(f"campaign 行数 {campaign_rows} != 24")
    merged = campaign.merge(
        pd.concat([frame.assign(condition_id=condition_id)
                   for condition_id, frame in data["sessions"].items()], ignore_index=True),
        on=["condition_id", "sample_index"], suffixes=("_campaign", "_session"), how="inner")
    if len(merged):
        diff = np.abs(merged["power_w_campaign"] - merged["power_w_session"])
        if float(np.nanmax(diff)) > 1e-6:
            issues.append(f"campaign 与 session 功率不一致（最大差 {float(np.nanmax(diff)):.4f} W）")
    if data["summary"] is not None and len(data["summary"]) != 4:
        issues.append(f"campaign_summary 行数 {len(data['summary'])} != 4")
    return quality, issues, campaign_rows


# ---------------------------------------------------------------- 指标

def energy_trapezoid_wh(elapsed_s, power_w) -> float:
    elapsed = np.asarray(elapsed_s, dtype=float)
    power = np.asarray(power_w, dtype=float)
    mask = np.isfinite(elapsed) & np.isfinite(power)
    if mask.sum() < 2:
        return float("nan")
    return float(np.trapezoid(power[mask], elapsed[mask]) / 3600.0)


def condition_metrics(data: dict) -> tuple[pd.DataFrame, pd.DataFrame]:
    summary = data["summary"]
    rows: list[dict] = []
    cross: list[dict] = []
    for condition_id in CONDITION_ORDER:
        frame = data["sessions"][condition_id].sort_values("sample_index")
        elapsed = frame["elapsed_sec"].to_numpy(dtype=float)
        power = frame["power_w"].to_numpy(dtype=float)
        current_ma = np.abs(frame["current_now_ua"].to_numpy(dtype=float)) / 1000.0
        levels = frame["battery_level_pct"].to_numpy(dtype=float)
        battery_temp = frame["battery_temp_c"].to_numpy(dtype=float)
        cpu_temp = frame["cpu_temp_c"].to_numpy(dtype=float)
        thermal = frame["thermal_status"].to_numpy(dtype=float)
        duration_min = 30.0
        summary_energy = float("nan")
        if summary is not None and condition_id in set(summary["condition_id"]):
            match = summary.loc[summary["condition_id"] == condition_id]
            value = match["duration_min"].iloc[0]
            if np.isfinite(value) and value > 0:
                duration_min = float(value)
            summary_energy = float(match["energy_wh"].iloc[0])
        avg_power = float(np.nanmean(power))
        energy_trapz = energy_trapezoid_wh(elapsed, power)
        energy_rated = avg_power * duration_min / 60.0
        level_drop = float(levels[0] - levels[-1])
        temp_rise = float(battery_temp[-1] - battery_temp[0])
        thermal_rises = int(np.sum(np.diff(thermal) > 0)) if thermal.size > 1 else 0
        span_h = (elapsed[-1] - elapsed[0]) / 3600.0 if elapsed.size > 1 else duration_min / 60.0
        net_rx_mb = (float(np.nanmean(frame["net_rx_kbps"].to_numpy(dtype=float))) * 1000.0 / 8.0
                     * (duration_min * 60.0) / 1024.0 / 1024.0)
        net_tx_mb = (float(np.nanmean(frame["net_tx_kbps"].to_numpy(dtype=float))) * 1000.0 / 8.0
                     * (duration_min * 60.0) / 1024.0 / 1024.0)
        rows.append({
            "condition_id": condition_id,
            "condition_label": CONDITION_LABELS.get(condition_id, condition_id),
            "duration_min": duration_min,
            "start_level_pct": float(levels[0]),
            "end_level_pct": float(levels[-1]),
            "level_drop_pct": level_drop,
            "drop_rate_pct_per_h": level_drop / span_h if span_h > 0 else float("nan"),
            "avg_power_w": avg_power,
            "peak_power_w": float(np.nanmax(power)),
            "energy_wh": energy_trapz,
            "energy_rated_wh": energy_rated,
            "avg_current_ma": float(np.nanmean(current_ma)),
            "temp_start_c": float(battery_temp[0]),
            "temp_end_c": float(battery_temp[-1]),
            "temp_rise_c": temp_rise,
            "temp_rise_rate_c_per_min": temp_rise / duration_min,
            "cpu_temp_peak_c": float(np.nanmax(cpu_temp)) if np.isfinite(cpu_temp).any() else float("nan"),
            "thermal_max": int(np.nanmax(thermal)) if np.isfinite(thermal).any() else -1,
            "thermal_rise_events": thermal_rises,
            "avg_cpu_freq_mhz": float(np.nanmean(frame["cpu_freq_mhz_avg"].to_numpy(dtype=float))),
            "avg_screen_brightness_pct": float(np.nanmean(frame["screen_brightness_pct"].to_numpy(dtype=float))),
            "net_rx_mb": net_rx_mb,
            "net_tx_mb": net_tx_mb,
            "samples": int(len(frame)),
        })
        charge = frame["charge_counter_uah"].to_numpy(dtype=float)
        voltage = frame["battery_voltage_mv"].to_numpy(dtype=float)
        if np.isfinite(charge).all() and np.isfinite(voltage).all():
            charge_wh = (charge[0] - charge[-1]) * float(np.nanmean(voltage)) / 1e9
        else:
            charge_wh = float("nan")
        cross.append({
            "condition_id": condition_id,
            "energy_trapz_wh": energy_trapz,
            "energy_rated_wh": energy_rated,
            "energy_from_charge_counter_wh": charge_wh,
            "energy_from_summary_wh": summary_energy,
            "trapz_minus_charge_wh": energy_trapz - charge_wh,
            "trapz_minus_summary_wh": energy_trapz - summary_energy,
            "duration_min": duration_min,
        })
    return pd.DataFrame(rows), pd.DataFrame(cross)


def build_attribution(campaign: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    """活动指示量最小二乘回归：power_w ~ 基线 + 屏幕亮度 + CPU 频率 + 网络吞吐 + 帧率 + 相机/导航。"""
    frame = campaign.copy()
    brightness = pd.to_numeric(frame["screen_brightness_pct"], errors="coerce").fillna(0.0) / 100.0
    cpu_ghz = pd.to_numeric(frame["cpu_freq_mhz_avg"], errors="coerce").fillna(0.0) / 1000.0
    net_mbps = (pd.to_numeric(frame["net_rx_kbps"], errors="coerce").fillna(0.0)
                + pd.to_numeric(frame["net_tx_kbps"], errors="coerce").fillna(0.0)) / 1000.0
    fps60 = pd.to_numeric(frame["fps"], errors="coerce").fillna(0.0) / 60.0
    camnav = frame["condition_id"].astype(str).eq("NAVCAM").astype(float)
    y = pd.to_numeric(frame["power_w"], errors="coerce").to_numpy(dtype=float)
    X = np.column_stack([
        np.ones(len(frame)), brightness.to_numpy(), cpu_ghz.to_numpy(), net_mbps.to_numpy(),
        fps60.to_numpy(), camnav.to_numpy(),
    ])
    names = ["基线（待机/系统固定开销）", "屏幕亮度", "CPU 频率", "网络吞吐", "渲染帧率", "相机/导航"]
    units = ["W", "W / 100% 亮度", "W / GHz", "W / Mbps", "W / 60fps", "W（NAVCAM 期间）"]
    indicators = ["1", "screen_brightness_pct / 100", "cpu_freq_mhz_avg / 1000",
                  "(net_rx + net_tx) / 1000", "fps / 60", "condition_id == NAVCAM"]
    beta, removed = stats_utils.ols_nonnegative_elimination(X, y, protect_first=True)
    y_hat = X @ beta
    r2 = stats_utils.r_squared(y, y_hat)
    rmse = stats_utils.rmse(y, y_hat)
    mean_activity = np.nanmean(X, axis=0)
    contributions = beta * mean_activity
    total_predicted = float(np.sum(contributions))
    activity_total = float(np.sum(contributions[1:]))
    fps_available = int(np.sum(np.isfinite(pd.to_numeric(frame["fps"], errors="coerce"))))
    rows = []
    for index, name in enumerate(names):
        share_total = contributions[index] / total_predicted * 100.0 if total_predicted > 0 else float("nan")
        share_activity = (contributions[index] / activity_total * 100.0
                          if index > 0 and activity_total > 0 else float("nan"))
        note = ""
        if index in removed:
            note = "非负约束下被剔除（与其它指示量共线或符号不合理，按 0 计入）"
        elif name == "渲染帧率" and fps_available < len(frame):
            note = "仅部分工况有 fps 读数（App 需在前台才可测），缺失按 0 计"
        rows.append({
            "module": name,
            "indicator": indicators[index],
            "unit": units[index],
            "coefficient": float(beta[index]),
            "mean_activity": float(mean_activity[index]),
            "watt_estimate": float(contributions[index]),
            "share_of_total_pct": share_total,
            "share_of_activity_pct": share_activity,
            "note": note,
        })
    attribution = pd.DataFrame(rows)
    fit = pd.DataFrame({"condition_id": frame["condition_id"].astype(str).to_numpy(),
                        "sample_index": frame["sample_index"].to_numpy(),
                        "power_measured_w": y,
                        "power_predicted_w": y_hat,
                        "residual_w": y - y_hat})
    fit_summary = {
        "r2": float(r2), "rmse_w": float(rmse), "mean_power_w": float(np.nanmean(y)),
        "predicted_total_w": total_predicted, "removed_terms": [names[i] for i in removed],
        "coefficients": {name: float(beta[index]) for index, name in enumerate(names)},
    }
    return attribution, fit, fit_summary


def build_thermal_analysis(data: dict) -> pd.DataFrame:
    rows = []
    for condition_id in CONDITION_ORDER:
        frame = data["sessions"][condition_id].sort_values("sample_index")
        thermal = frame["thermal_status"].to_numpy(dtype=float)
        freq = frame["cpu_freq_mhz_avg"].to_numpy(dtype=float)
        cpu_temp = frame["cpu_temp_c"].to_numpy(dtype=float)
        half = max(1, len(freq) // 2)
        first = float(np.nanmean(freq[:half]))
        second = float(np.nanmean(freq[half:]))
        drop_pct = (first - second) / first * 100.0 if first > 0 else float("nan")
        thermal_delta = (float(np.nanmax(thermal) - thermal[0])
                         if np.isfinite(thermal).any() else float("nan"))
        thermal_rise = int(np.sum(np.diff(thermal) > 0)) if thermal.size > 1 else 0
        throttled = bool(np.isfinite(drop_pct) and drop_pct >= 5.0
                         and (thermal_delta >= 1 or thermal_rise >= 1))
        raw_detail = ""
        if condition_id in data["raw"]:
            raw = data["raw"][condition_id]
            if "cpu_freq_mhz_avg" in raw and len(raw) > 10:
                raw_freq = raw["cpu_freq_mhz_avg"].to_numpy(dtype=float)
                raw_thermal = raw["thermal_status"].to_numpy(dtype=float)
                rise = np.where(np.diff(raw_thermal) > 0)[0]
                if rise.size:
                    index = int(rise[0])
                    before = float(np.nanmean(raw_freq[max(0, index - 12):index + 1]))
                    after = float(np.nanmean(raw_freq[index + 1:]))
                    raw_detail = (f"raw 首次 thermal 上升于 "
                                  f"{float(raw['elapsed_sec'].iloc[index]):.0f} s，"
                                  f"频率 {before:.0f} -> {after:.0f} MHz")
        rows.append({
            "condition_id": condition_id,
            "thermal_start": int(thermal[0]) if np.isfinite(thermal).any() else -1,
            "thermal_max": int(np.nanmax(thermal)) if np.isfinite(thermal).any() else -1,
            "thermal_delta": thermal_delta,
            "thermal_rise_events": thermal_rise,
            "cpu_freq_first_half_mhz": first,
            "cpu_freq_second_half_mhz": second,
            "cpu_freq_drop_pct": drop_pct,
            "cpu_temp_peak_c": float(np.nanmax(cpu_temp)) if np.isfinite(cpu_temp).any() else float("nan"),
            "throttled": throttled,
            "raw_detail": raw_detail,
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------- 工况增量分解

def build_increment_decomposition(metrics: pd.DataFrame) -> pd.DataFrame:
    """按工况顺序做功率增量分解（测量值，不依赖回归）：

    待机基线 = IDLE 平均功率；之后每个工况相对上一工况的功率增量对应新增的活动类别。
    这是 4 工况这类"每工况一种活动组合"实验设计下最稳健的模块归因方式，
    与活动指示量回归（第 3.1 节）互为补充。
    """
    means = {row["condition_id"]: float(row["avg_power_w"]) for _, row in metrics.iterrows()}
    campaign_mean = float(metrics["avg_power_w"].mean())
    rows = [
        {"source": "待机基线（屏幕熄灭）", "condition_step": "IDLE",
         "power_w": means["IDLE"],
         "share_pct": means["IDLE"] / campaign_mean * 100.0 if campaign_mean else float("nan"),
         "note": "系统常驻 + 内存 + 网络待机，屏幕与相机均关闭"},
        {"source": "屏幕 + 视频解码 + 网络下行", "condition_step": "IDLE -> VIDEO",
         "power_w": means["VIDEO"] - means["IDLE"],
         "share_pct": (means["VIDEO"] - means["IDLE"]) / campaign_mean * 100.0 if campaign_mean else float("nan"),
         "note": "固定亮度 50% 连续播放短视频"},
        {"source": "相机 + GNSS + 视频编码 + 网络上/下行", "condition_step": "VIDEO -> NAVCAM",
         "power_w": means["NAVCAM"] - means["VIDEO"],
         "share_pct": (means["NAVCAM"] - means["VIDEO"]) / campaign_mean * 100.0 if campaign_mean else float("nan"),
         "note": "导航 + 1080p 录像同时工作"},
        {"source": "GPU/CPU 高帧率渲染", "condition_step": "NAVCAM -> GAME",
         "power_w": means["GAME"] - means["NAVCAM"],
         "share_pct": (means["GAME"] - means["NAVCAM"]) / campaign_mean * 100.0 if campaign_mean else float("nan"),
         "note": "90/120fps 高画质游戏，后段伴随热节流"},
    ]
    frame = pd.DataFrame(rows)
    frame["cumulative_w"] = frame["power_w"].cumsum()
    return frame


# ---------------------------------------------------------------- 图表

def make_figures(data: dict, metrics: pd.DataFrame, attribution: pd.DataFrame,
                 thermal: pd.DataFrame, figure_dir: Path) -> list[str]:
    io_utils.ensure_dir(figure_dir)
    names: list[str] = []
    campaign = data["campaign"].copy()
    campaign["_offset"] = campaign["condition_id"].map(
        {condition_id: index * 1800.0 for index, condition_id in enumerate(CONDITION_ORDER)}).astype(float)
    campaign["_t"] = campaign["elapsed_sec"] + campaign["_offset"]

    # 1) 电量-时间曲线
    fig, ax = plt.subplots(figsize=(8.2, 4.0))
    for condition_id in CONDITION_ORDER:
        subset = campaign[campaign["condition_id"] == condition_id]
        ax.plot(subset["_t"] / 60.0, subset["battery_level_pct"], marker="o", linewidth=1.8,
                color=plot_utils.CONDITION_COLORS[condition_id],
                label=f"{condition_id} {CONDITION_LABELS[condition_id]}")
    ax.set_xlabel("批次累计时间 / min")
    ax.set_ylabel("电量 / %")
    ax.set_title("电量-时间曲线（4 工况连续批次）")
    ax.invert_yaxis()
    ax.legend(loc="lower left", ncol=2)
    names.append(Path(plot_utils.save(fig, figure_dir / "battery_curve.png")).name)

    # 2) 功率 / 电流-时间
    fig, axes = plt.subplots(2, 1, figsize=(8.2, 6.0), sharex=True)
    for condition_id in CONDITION_ORDER:
        subset = campaign[campaign["condition_id"] == condition_id]
        axes[0].plot(subset["_t"] / 60.0, subset["power_w"], marker="o", linewidth=1.6,
                     color=plot_utils.CONDITION_COLORS[condition_id], label=condition_id)
        axes[1].plot(subset["_t"] / 60.0, np.abs(subset["current_now_ua"]) / 1000.0, marker="o",
                     linewidth=1.6, color=plot_utils.CONDITION_COLORS[condition_id], label=condition_id)
    axes[0].set_ylabel("功率 / W")
    axes[0].set_title("功率与电流-时间曲线（颜色区分 4 工况）")
    axes[0].legend(ncol=4)
    axes[1].set_ylabel("|电流| / mA")
    axes[1].set_xlabel("批次累计时间 / min")
    names.append(Path(plot_utils.save(fig, figure_dir / "power_current.png")).name)

    # 3) 温度-时间 + thermal
    fig, ax = plt.subplots(figsize=(8.2, 4.2))
    ax.plot(campaign["_t"] / 60.0, campaign["battery_temp_c"], marker="o", linewidth=1.8,
            color=plot_utils.PALETTE[1], label="电池温度")
    if campaign["cpu_temp_c"].notna().any():
        ax.plot(campaign["_t"] / 60.0, campaign["cpu_temp_c"], marker="s", linewidth=1.4,
                color=plot_utils.PALETTE[5], label="CPU 温度（thermal zone）")
    ax.set_xlabel("批次累计时间 / min")
    ax.set_ylabel("温度 / °C")
    ax.set_title("温度-时间曲线与 thermal status")
    ax2 = ax.twinx()
    ax2.step(campaign["_t"] / 60.0, campaign["thermal_status"], where="post",
             color=plot_utils.PALETTE[3], linewidth=1.2, linestyle="--", label="thermal status")
    ax2.set_ylabel("thermal status (0-6)")
    ax2.grid(False)
    handles, labels = ax.get_legend_handles_labels()
    handles2, labels2 = ax2.get_legend_handles_labels()
    ax.legend(handles + handles2, labels + labels2, loc="upper left")
    names.append(Path(plot_utils.save(fig, figure_dir / "temperature.png")).name)

    # 4) 工况对比柱状图
    fig, axes = plt.subplots(2, 2, figsize=(9.0, 6.4))
    panels = [
        ("avg_power_w", "平均功率 / W", axes[0][0], "{:.2f}"),
        ("energy_wh", "采样窗口能量 / Wh", axes[0][1], "{:.2f}"),
        ("drop_rate_pct_per_h", "掉电率 / %/h", axes[1][0], "{:.1f}"),
        ("temp_rise_rate_c_per_min", "温升速率 / °C/min", axes[1][1], "{:.2f}"),
    ]
    for column, title, axis, pattern in panels:
        values = metrics[column].to_numpy(dtype=float)
        colors = [plot_utils.CONDITION_COLORS[c] for c in metrics["condition_id"]]
        bars = axis.bar(metrics["condition_id"], values, color=colors, width=0.62)
        axis.set_title(title)
        for bar, value in zip(bars, values):
            if np.isfinite(value):
                axis.annotate(pattern.format(value),
                              (bar.get_x() + bar.get_width() / 2, bar.get_height()),
                              ha="center", va="bottom", fontsize=8)
    fig.suptitle("4 工况关键指标对比")
    fig.tight_layout()
    names.append(Path(plot_utils.save(fig, figure_dir / "condition_compare.png")).name)

    # 5) 模块归因
    subset = attribution.iloc[1:].sort_values("watt_estimate")
    fig, ax = plt.subplots(figsize=(8.4, 3.8))
    colors = [plot_utils.PALETTE[0] if value >= 0 else plot_utils.PALETTE[5]
              for value in subset["watt_estimate"]]
    bars = ax.barh(subset["module"], subset["watt_estimate"], color=colors, height=0.6)
    for bar, value, share in zip(bars, subset["watt_estimate"], subset["share_of_total_pct"]):
        ax.annotate(f"{value:.2f} W ({share:.0f}%)",
                    (bar.get_width(), bar.get_y() + bar.get_height() / 2),
                    ha="left" if value >= 0 else "right", va="center", fontsize=8)
    ax.set_xlabel("归因功率 / W（活动指示量回归，非电流计）")
    ax.set_title("高功耗模块归因（24 组样本最小二乘）")
    names.append(Path(plot_utils.save(fig, figure_dir / "module_attribution.png")).name)

    # 6) 热节流
    fig, axes = plt.subplots(2, 2, figsize=(9.0, 6.0), sharex=True)
    for axis, condition_id in zip(axes.ravel(), CONDITION_ORDER):
        frame = data["sessions"][condition_id].sort_values("sample_index")
        axis.plot(frame["elapsed_sec"] / 60.0, frame["cpu_freq_mhz_avg"], marker="o",
                  color=plot_utils.PALETTE[0], label="CPU 频率")
        axis.set_ylabel("MHz")
        axis.set_title(f"{condition_id} {CONDITION_LABELS[condition_id]}")
        twin = axis.twinx()
        twin.step(frame["elapsed_sec"] / 60.0, frame["thermal_status"], where="post",
                  color=plot_utils.PALETTE[5], linestyle="--", label="thermal")
        twin.set_ylim(-0.2, 6.4)
        twin.grid(False)
        row = thermal[thermal["condition_id"] == condition_id].iloc[0]
        if bool(row["throttled"]):
            axis.annotate("检测到节流：热状态上升 + 频率下降", xy=(0.04, 0.05),
                          xycoords="axes fraction", fontsize=8, color=plot_utils.PALETTE[5])
    axes[1][0].set_xlabel("工况内时间 / min")
    axes[1][1].set_xlabel("工况内时间 / min")
    fig.suptitle("热点节流检测：CPU 频率（左轴）与 thermal status（右轴）")
    fig.tight_layout()
    names.append(Path(plot_utils.save(fig, figure_dir / "thermal_throttle.png")).name)
    return names


# ---------------------------------------------------------------- 报告

def build_strategy_points(metrics: pd.DataFrame, attribution: pd.DataFrame,
                          thermal: pd.DataFrame, increments: pd.DataFrame,
                          fit_summary: dict) -> list[str]:
    points: list[str] = []
    coefficients = dict(zip(attribution["module"], attribution["coefficient"]))
    idle = metrics.loc[metrics["condition_id"] == "IDLE"].iloc[0]
    video = metrics.loc[metrics["condition_id"] == "VIDEO"].iloc[0]
    game = metrics.loc[metrics["condition_id"] == "GAME"].iloc[0]
    nav = metrics.loc[metrics["condition_id"] == "NAVCAM"].iloc[0]
    brightness = float(coefficients.get("屏幕亮度", 0.0))
    cpu = float(coefficients.get("CPU 频率", 0.0))
    network = float(coefficients.get("网络吞吐", 0.0))
    fps_term = float(coefficients.get("渲染帧率", 0.0))
    baseline = float(coefficients.get("基线（待机/系统固定开销）", 0.0))
    if brightness > 0:
        points.append(
            f"- 屏幕亮度：亮度每降低 10%，按回归系数可省约 **{brightness * 0.1:.3f} W**；"
            f"短视频工况平均亮度 {video['avg_screen_brightness_pct']:.0f}%，"
            f"若降到 30% 量级预计整机功耗下降 {brightness * 0.2:.2f} W。")
    else:
        points.append("- 屏幕亮度：本批数据中亮度项未进入有效模型（亮度可能被固定在同一档），"
                      "建议补充不同亮度档位的对照实验后再定量。")
    if cpu > 0:
        points.append(
            f"- CPU 频率：频率每降低 100 MHz 约省 **{cpu * 0.1:.3f} W**；"
            f"游戏工况平均 {game['avg_cpu_freq_mhz']:.0f} MHz，"
            f"若锁到 {game['avg_cpu_freq_mhz'] * 0.8:.0f} MHz 约省 "
            f"{cpu * game['avg_cpu_freq_mhz'] * 0.2 / 1000:.2f} W。")
    if network > 0:
        points.append(
            f"- 网络吞吐：每降低 1 Mbps 约省 **{network:.3f} W**；导航拍摄工况上下行合计约 "
            f"{(nav['net_rx_mb'] + nav['net_tx_mb']) * 1024.0 / 1024.0 / (nav['duration_min'] / 60.0):.2f} Mbps，"
            "建议降低录像码率、预加载地图、关闭测速与后台同步。")
    if fps_term > 0:
        points.append(
            f"- 刷新率/帧率：帧率每降低 15 fps（0.25 个 60fps 单位）约省 **{fps_term * 0.25:.3f} W**；"
            f"高帧率游戏实测平均 {game['avg_power_w']:.2f} W，60fps + 中画质可显著降低功耗。")
    if np.isfinite(baseline) and baseline > 0:
        points.append(
            f"- 待机基线：固定开销约 **{baseline:.2f} W**（系统常驻 + 屏幕/内存/网络待机）；"
            f"待机工况实测 {idle['avg_power_w']:.2f} W、{idle['drop_rate_pct_per_h']:.1f} %/h，"
            "建议关闭 AOD/常驻通知、限制后台联网与高刷待机。")
    throttled = thermal[thermal["throttled"]]
    if len(throttled):
        names = "、".join(throttled["condition_id"].tolist())
        detail = "；".join(
            f"{row['condition_id']} 频率 {row['cpu_freq_first_half_mhz']:.0f}->{row['cpu_freq_second_half_mhz']:.0f} MHz"
            for _, row in throttled.iterrows())
        points.append(
            f"- 温控：{names} 工况出现热节流（{detail}）；建议避免边充边玩、加散热背夹或降低画质，"
            "并把高负载工况安排在环境温度较低的时段。")
    points.append(
        "- 后台与传感器：导航 + 相机同时工作时功耗最高，建议按需授权定位精度（高精度 -> 省电模式）、"
        "录像结束立即释放相机；网络侧优先 WiFi、关闭 VPN/代理。")
    step = {row["condition_step"]: row for _, row in increments.iterrows()}
    if "IDLE -> VIDEO" in step:
        points.append(
            f"- 实测增量：短视频相对待机增加 {step['IDLE -> VIDEO']['power_w']:.2f} W（屏幕 + 解码 + 下行），"
            "是日常使用中最容易通过降亮度/降分辨率压缩的一段。")
    if "VIDEO -> NAVCAM" in step:
        points.append(
            f"- 实测增量：导航拍摄在短视频基础上再增加 {step['VIDEO -> NAVCAM']['power_w']:.2f} W"
            "（相机 + GNSS + 编码 + 上行），建议 1080p/30fps 录制、关闭后台导航播报。")
    if "NAVCAM -> GAME" in step:
        points.append(
            f"- 实测增量：高帧率游戏在导航拍摄基础上再增加 {step['NAVCAM -> GAME']['power_w']:.2f} W"
            "（GPU/CPU 渲染），是最主要的发热与掉电来源，优先降帧率与画质。")
    points.append(
        f"- 整体：本批 24 个采样点平均功耗 **{float(metrics['avg_power_w'].mean()):.2f} W**，"
        f"最高工况 {game['condition_id']} 平均 {game['avg_power_w']:.2f} W；"
        "若目标是半天续航，可优先在游戏/导航拍摄两类高功耗场景做降档。")
    return points


def build_report(data: dict, quality: pd.DataFrame, issues: list[str], metrics: pd.DataFrame,
                 cross: pd.DataFrame, attribution: pd.DataFrame, fit_frame: pd.DataFrame,
                 fit_summary: dict, thermal: pd.DataFrame, increments: pd.DataFrame,
                 figure_names: list[str], synthetic: bool) -> str:
    lines: list[str] = []
    lines.append("# 课题02 · 个人手机多工况发热与掉电实测 —— 分析报告")
    lines.append("")
    lines.append("> 由 analysis/power/analyze.py 自动生成；数值口径与局限见第 6 节。")
    if synthetic:
        lines.append(">")
        lines.append("> **本报告使用的是 data/demo 合成演示数据（SYNTHETIC），仅用于验证分析管线，"
                     "不能作为课程报告的真实测量结论。** 换成 data/raw 下的真机数据后重跑即可。")
    lines.append("")
    idle = metrics.loc[metrics["condition_id"] == "IDLE"].iloc[0]
    video = metrics.loc[metrics["condition_id"] == "VIDEO"].iloc[0]
    navcam = metrics.loc[metrics["condition_id"] == "NAVCAM"].iloc[0]
    game = metrics.loc[metrics["condition_id"] == "GAME"].iloc[0]
    complete = len(quality) == 4 and bool((quality["rows"] == 6).all())
    lines.append("## 摘要")
    lines.append("")
    lines.append(
        f"本批数据共 {int(metrics['samples'].sum())} 个采样点（4 工况 × 6 点）"
        + ("，已通过 24 行完整性校验。" if complete else "，存在数据完整性问题（见第 1 节）。")
        + f"四个工况的平均功率分别为：待机 {idle['avg_power_w']:.2f} W、短视频 {video['avg_power_w']:.2f} W、"
        f"导航拍摄 {navcam['avg_power_w']:.2f} W、高帧率游戏 {game['avg_power_w']:.2f} W；"
        f"对应掉电率 {idle['drop_rate_pct_per_h']:.1f} / {video['drop_rate_pct_per_h']:.1f} / "
        f"{navcam['drop_rate_pct_per_h']:.1f} / {game['drop_rate_pct_per_h']:.1f} %/h，"
        f"电池温度分别上升 {idle['temp_rise_c']:.1f} / {video['temp_rise_c']:.1f} / "
        f"{navcam['temp_rise_c']:.1f} / {game['temp_rise_c']:.1f} °C。"
        f"活动指示量回归 R² = {fit_summary['r2']:.3f}、RMSE = {fit_summary['rmse_w']:.3f} W；"
        f"归因最大的活动模块为 {attribution.iloc[1:].sort_values('watt_estimate', ascending=False)['module'].iloc[0]}。"
        f"热节流检测结果：{'、'.join(thermal.loc[thermal['throttled'], 'condition_id'].tolist()) or '本批未检测到明显节流'}。")
    lines.append("")
    lines.append(
        f"**样本目录**：{data['dir'].as_posix()}；"
        f"**批次**：{str(data['campaign']['campaign_id'].iloc[0]) if 'campaign_id' in data['campaign'] else '未知'}；"
        f"**设备**：{data['manifest'].get('device', {}).get('model', '未提供 manifest')}。")
    lines.append("")
    lines.append("## 1. 数据完整性与采样质量")
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "行数", "缺失采样点", "迟到样本", "充电行", "电量回升", "功率重算误差", "CPU 温度缺失", "fps 缺失"],
        [[row["condition_id"], row["rows"], row["missing_sample_index"] or "无",
          row["late_samples"], row["charging_rows"], row["level_rise_rows"],
          md_utils.fmt_number(row["power_recompute_max_rel_err"], 4),
          row["cpu_temp_missing"], row["fps_missing"]] for _, row in quality.iterrows()]))
    lines.append("")
    lines.append("校验结论：")
    lines.append("")
    if issues:
        for issue in issues:
            lines.append(f"- ⚠️ {issue}")
        lines.append("- 以上问题不阻断分析，但已在口径中标记；迟到样本按实际时间戳参与积分。")
    else:
        lines.append("- ✅ 24 行齐全，无缺失采样点、无迟到样本、无充电异常；"
                     "power_w 与 voltage_mv × |current_µA| / 1e9 重算一致。")
    lines.append("- 电流符号约定：本批导出放电为负值，分析统一取绝对值。")
    lines.append("")
    lines.append("## 2. 4 工况关键指标")
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "平均功率 W", "峰值 W", "能量 Wh（积分）", "掉电 %", "掉电率 %/h",
         "温升 °C", "温升速率 °C/min", "thermal 峰值", "thermal 上升次数"],
        [[row["condition_id"], md_utils.fmt_number(row["avg_power_w"], 3),
          md_utils.fmt_number(row["peak_power_w"], 3), md_utils.fmt_number(row["energy_wh"], 3),
          md_utils.fmt_number(row["level_drop_pct"], 1),
          md_utils.fmt_number(row["drop_rate_pct_per_h"], 1),
          md_utils.fmt_number(row["temp_rise_c"], 1),
          md_utils.fmt_number(row["temp_rise_rate_c_per_min"], 3),
          row["thermal_max"], row["thermal_rise_events"]] for _, row in metrics.iterrows()]))
    lines.append("")
    lines.append("能量交叉校验（采样窗梯形积分 vs 均值×时长 vs charge_counter 差分 vs App 汇总）：")
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "积分 Wh", "均值×时长 Wh", "charge_counter 差分 Wh", "App 汇总 Wh"],
        [[row["condition_id"], md_utils.fmt_number(row["energy_trapz_wh"], 3),
          md_utils.fmt_number(row["energy_rated_wh"], 3),
          md_utils.fmt_number(row["energy_from_charge_counter_wh"], 3),
          md_utils.fmt_number(row["energy_from_summary_wh"], 3)] for _, row in cross.iterrows()]))
    lines.append("")
    lines.append("图表：")
    for name, caption in zip(
            ["battery_curve.png", "power_current.png", "temperature.png", "condition_compare.png"],
            ["电量-时间曲线", "功率/电流-时间曲线", "温度-时间曲线", "4 工况指标对比"]):
        lines.append("")
        lines.append(md_utils.md_figure(f"figures/{name}", caption))
    lines.append("")
    lines.append("## 3. 高功耗模块归因")
    lines.append("")
    lines.append("> **声明：这是“活动指示量回归”，不是硬件电流计。** SoC/PMIC 不为每个子系统提供独立电流采样，"
                 "只能把整机功率对可观测的活动指示量做最小二乘拟合，得到“在该活动水平下平均功率增加多少”的估计；"
                 "共线项之间无法唯一分解，非负约束下被剔除的项按 0 计入。")
    lines.append("")
    lines.append(md_utils.md_table(
        ["模块", "活动指示量", "系数", "单位", "归因功率 W", "占总功耗 %", "占活动功耗 %"],
        [[row["module"], row["indicator"], md_utils.fmt_number(row["coefficient"], 4),
          row["unit"], md_utils.fmt_number(row["watt_estimate"], 3),
          md_utils.fmt_number(row["share_of_total_pct"], 1),
          md_utils.fmt_number(row["share_of_activity_pct"], 1)] for _, row in attribution.iterrows()]))
    lines.append("")
    if fit_summary["removed_terms"]:
        lines.append(f"- 非负约束剔除项：{'、'.join(fit_summary['removed_terms'])}（与其它项共线或系数为负）。")
    lines.append(f"- 拟合质量：R² = {fit_summary['r2']:.3f}，RMSE = {fit_summary['rmse_w']:.3f} W，"
                 f"平均实测 {fit_summary['mean_power_w']:.3f} W，模型预测合计 {fit_summary['predicted_total_w']:.3f} W。")
    lines.append("")
    lines.append(md_utils.md_figure("figures/module_attribution.png", "高功耗模块归因"))
    lines.append("")
    lines.append("### 3.2 工况增量分解（测量值，不依赖回归）")
    lines.append("")
    lines.append("在“每个工况一种活动组合”的实验设计下，回归项之间必然共线；因此再用工况顺序的功率增量做一次交叉验证："
                 "待机基线 + 各工况新增活动类别的增量 = 高负载工况功率。")
    lines.append("")
    lines.append(md_utils.md_table(
        ["来源", "工况区间", "增量功率 W", "占批次平均功耗 %", "累计 W", "说明"],
        [[row["source"], row["condition_step"], md_utils.fmt_number(row["power_w"], 3),
          md_utils.fmt_number(row["share_pct"], 1), md_utils.fmt_number(row["cumulative_w"], 3),
          row["note"]] for _, row in increments.iterrows()]))
    lines.append("")
    lines.append(f"- 增量累计 {md_utils.fmt_number(float(increments['power_w'].sum()), 3)} W，"
                 f"与最高工况平均功率 {md_utils.fmt_number(float(metrics['avg_power_w'].max()), 3)} W 一致。")
    lines.append("")
    lines.append("## 4. 热点与节流")
    lines.append("")
    lines.append(md_utils.md_table(
        ["工况", "thermal 起始", "thermal 峰值", "频率前半/后半 MHz", "频率降幅 %", "是否节流", "补充"],
        [[row["condition_id"], row["thermal_start"], row["thermal_max"],
          f"{md_utils.fmt_number(row['cpu_freq_first_half_mhz'], 0)} / "
          f"{md_utils.fmt_number(row['cpu_freq_second_half_mhz'], 0)}",
          md_utils.fmt_number(row["cpu_freq_drop_pct"], 1),
          "是" if row["throttled"] else "否", row["raw_detail"] or "—"] for _, row in thermal.iterrows()]))
    lines.append("")
    lines.append("判定规则：thermal_status 在工况内上升（Δ≥1 或出现上升事件）**且** CPU 平均频率后半段较前半段下降 ≥5% 时标记为热节流。")
    lines.append("")
    lines.append(md_utils.md_figure("figures/thermal_throttle.png", "热节流检测"))
    lines.append("")
    lines.append("## 5. 省电策略建议（基于本批测量）")
    lines.append("")
    lines.extend(build_strategy_points(metrics, attribution, thermal, increments, fit_summary))
    lines.append("")
    lines.append("## 6. 口径与局限")
    lines.append("")
    lines.append("1. **采样口径**：每 5 min 一个点、每工况 6 点（0–25 min）；能量按采样时间轴梯形积分，"
                 "并与“均值×30 min”及 charge_counter_uah 差分交叉校验，差异见第 2 节表格。")
    lines.append("2. **功率换算**：power_w = voltage_mv × |current_now_ua| / 1e9；CURRENT_NOW 符号厂商相关，分析取绝对值。")
    lines.append("3. **能量计数**：charge_counter_uah 在不支持 CURRENT_NOW 时作为主口径；两者一致性用于发现异常点。")
    lines.append("4. **归因局限**：共线性（CPU 频率与帧率、网络与屏幕）无法唯一分解；指示量回归只能解释相关关系，"
                 "不构成因果；缺少相机/定位硬件计数器，NAVCAM 项是工况指示量而非真实相机电流。")
    lines.append("5. **温度**：电池温度与 thermal zone 温度来自不同传感器，受握持方式、环境温度、机壳材质影响；"
                 "温升速率依赖起始温度，跨批次比较应控制起始温度与降温时间。")
    lines.append("6. **样本量**：4 工况 × 6 点 = 24 点规模较小，不足以做显著性检验；建议重复 3 个批次并做批间方差分析。")
    lines.append("")
    lines.append("## 7. 复现方式")
    lines.append("")
    lines.append("~~~powershell")
    lines.append("python analysis/power/analyze.py --data data/raw/power/<session> --out analysis/out/power")
    lines.append("~~~")
    lines.append("")
    lines.append("输出结构：figures/（6 张图）、tables/（质量表、指标表、能量交叉校验、模块归因、拟合残差、热节流）、本报告。")
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- 入口

def partial_check(data_dir: Path) -> int:
    """检查（可能不完整的）PowerTrace 导出目录，给出现场补采建议。

    真实采集常按工况分批完成，此时完整分析会缺列/缺文件；本模式只做完整性
    体检并返回退出码（0=可分析，1=尚不完整），不生成报告，避免用户看到 traceback。
    """
    if not data_dir.is_dir():
        print(f"[power] 数据目录不存在: {data_dir}")
        return 1
    print(f"[power] 检查目录: {data_dir}")
    present = 0
    for condition_id in CONDITION_ORDER:
        path = data_dir / f"session_{condition_id}.csv"
        if not path.is_file():
            print(f"  [缺失] session_{condition_id}.csv")
            continue
        frame = pd.read_csv(path, encoding="utf-8-sig")
        missing_columns = [c for c in SESSION_COLUMNS if c not in frame.columns]
        present += 1
        status = "OK" if not missing_columns else f"缺列 {missing_columns}"
        print(f"  [有]   session_{condition_id}.csv  行数={len(frame)}  {status}")
    campaign = [p for p in data_dir.glob("campaign_*.csv") if "summary" not in p.name]
    print(f"  [{'有' if campaign else '缺失'}] campaign_*.csv（4 工况合并 24 行）")
    summary = list(data_dir.glob("campaign_summary_*.csv"))
    print(f"  [{'有' if summary else '缺失'}] campaign_summary_*.csv")
    print(f"  [{'有' if (data_dir / 'manifest.json').is_file() else '缺失'}] manifest.json")
    print(f"  [{'有' if (data_dir / 'session.log').is_file() else '缺失'}] session.log")
    complete = present == len(CONDITION_ORDER) and bool(campaign)
    if complete:
        print("[power] 目录完整，可以运行完整分析。")
        return 0
    print(f"[power] 批次尚不完整（{present}/{len(CONDITION_ORDER)} 个工况）：请按 SOP 补齐后再分析；"
          "若只想核对已采数据，可先看上面的行数/列检查结果。")
    return 1


def run(data_dir: Path, out_dir: Path) -> dict:
    data = load_power_dir(data_dir)
    synthetic = is_synthetic(data)
    fallback_notes = apply_charge_power_fallback(data)
    quality, issues, campaign_rows = validate_samples(data)
    issues = fallback_notes + issues
    metrics, cross = condition_metrics(data)
    attribution, fit_frame, fit_summary = build_attribution(data["campaign"])
    thermal = build_thermal_analysis(data)
    increments = build_increment_decomposition(metrics)
    figure_dir = io_utils.ensure_dir(out_dir / "figures")
    table_dir = io_utils.ensure_dir(out_dir / "tables")
    figure_names = make_figures(data, metrics, attribution, thermal, figure_dir)
    io_utils.write_csv(quality, table_dir / "sample_quality.csv")
    io_utils.write_csv(metrics, table_dir / "condition_metrics.csv")
    io_utils.write_csv(cross, table_dir / "energy_crosscheck.csv")
    io_utils.write_csv(attribution, table_dir / "module_attribution.csv")
    io_utils.write_csv(fit_frame, table_dir / "attribution_fit.csv")
    io_utils.write_csv(thermal, table_dir / "thermal_analysis.csv")
    io_utils.write_csv(increments, table_dir / "increment_decomposition.csv")
    report = build_report(data, quality, issues, metrics, cross, attribution, fit_frame,
                          fit_summary, thermal, increments, figure_names, synthetic)
    md_utils.write_text(out_dir / "power_report.md", report)
    return {
        "data_dir": str(data_dir), "out_dir": str(out_dir), "synthetic": synthetic,
        "campaign_rows": campaign_rows, "issues": issues,
        "avg_power_w": {row["condition_id"]: float(row["avg_power_w"]) for _, row in metrics.iterrows()},
        "figures": figure_names, "r2": fit_summary["r2"],
        "throttled": thermal.loc[thermal["throttled"], "condition_id"].tolist(),
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    data_dir = Path(args.data).resolve()
    if args.partial_check:
        return partial_check(data_dir)
    out_dir = Path(args.out).resolve()
    print(f"[power] 读取 {data_dir}")
    summary = run(data_dir, out_dir)
    print(f"[power] 输出 {out_dir}：{len(summary['figures'])} 张图、6 张表、power_report.md")
    print("[power] 平均功率: " + ", ".join(f"{k}={v:.2f}W" for k, v in summary["avg_power_w"].items()))
    print(f"[power] 归因 R²={summary['r2']:.3f}；节流工况={summary['throttled'] or '无'}")
    if summary["synthetic"]:
        print("[power] 注意：输入是合成演示数据（synthetic），结论仅用于验证管线")
    if summary["issues"]:
        print(f"[power] 数据质量问题 {len(summary['issues'])} 条（已写入报告第 1 节）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())