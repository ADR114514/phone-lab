"""生成 data/demo 合成演示数据（SYNTHETIC DEMO DATA）。

!!! 本脚本产出的一切都是**合成数据**，仅用于让 analysis/ 功耗与声振管线端到端跑通、
    验证 CSV 契约与图表流程；它们不是真机实测数据，不能写进课程报告的结果部分。
    每一份 CSV/JSON 都带 synthetic 标记或 note 说明。

两个数据集的合成思路：
  1) power   : 按 4 工况顺序模拟 30 min x 4 的电量/温度/热节流过程，功率水平取
               待机 0.60 W / 短视频约 2.5 W / 导航拍摄约 4.4 W / 游戏约 5.8 W，
               电量从 100% 连续下降到约 61%；GAME 后段出现 thermal_status 上升 +
               CPU 频率下降的热节流，用于验证节流检测。
  2) vibro   : 5 工况 x 3 训练组 + 3 盲测组，用正弦/谐波 + 噪声合成：
               正常 = 基频 + 强谐波；失衡 = 1x 增大 + 边带；遮挡 = 高频衰减 + 宽带噪声；
               吹风机 = 高频宽带 + 电机音。WAV 为 3 s / 22050 Hz / 16-bit PCM（控制仓库体积）。

用法（在仓库根目录）：
    python data/demo/generate_demo.py
    python data/demo/generate_demo.py --out data/demo --seed 20261118
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import wave
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[2]
ANALYSIS_DIR = REPO_ROOT / "analysis"
if str(ANALYSIS_DIR) not in sys.path:
    sys.path.insert(0, str(ANALYSIS_DIR))

from vibro import features as vf  # noqa: E402  声振特征（与 App 对齐的同一套实现）
from vibro import model as vm  # noqa: E402  z-score + kNN

SYNTHETIC_TAG = "SYNTHETIC_DEMO"
DISCLAIMER = ("本目录全部数据由 data/demo/generate_demo.py 合成，仅用于验证分析管线，"
              "不是真机实测数据；课程报告中的结果必须使用 data/raw 下的真实采集数据。")
TZ = timezone(timedelta(hours=8))
POWER_START = datetime(2026, 11, 18, 13, 0, 0, tzinfo=TZ)
VIBRO_START = datetime(2026, 11, 18, 15, 0, 0, tzinfo=TZ)


# ------------------------------------------------------------------ 小工具

def ensure_dir(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_csv(path: Path, header: list[str], rows: list[list]) -> None:
    ensure_dir(path.parent)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        writer.writerows(rows)


def write_json(path: Path, payload: dict) -> None:
    ensure_dir(path.parent)
    with path.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def iso_local(moment: datetime) -> str:
    return moment.isoformat(timespec="seconds")


def iso_utc(moment: datetime) -> str:
    return moment.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def num(value, digits: int = 3, empty_nan: bool = True) -> str:
    """把数值格式化成 CSV 字段；NaN -> 空字段（数据契约要求）。"""
    if value is None:
        return ""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    if not math.isfinite(number):
        return "" if empty_nan else "0"
    if digits <= 0:
        return str(int(round(number)))
    return f"{number:.{digits}f}"


def int_field(value) -> str:
    if value is None:
        return ""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    if not math.isfinite(number):
        return ""
    return str(int(round(number)))


def moving_average(values: np.ndarray, window: int) -> np.ndarray:
    if window <= 1:
        return values.astype(float).copy()
    kernel = np.ones(window, dtype=float) / window
    return np.convolve(values, kernel, mode="same")


def fir_lowpass(values: np.ndarray, sample_rate: int, cutoff_hz: float, taps: int = 101) -> np.ndarray:
    fc = min(0.99, max(0.001, cutoff_hz / (sample_rate / 2.0)))
    index = np.arange(taps, dtype=float) - (taps - 1) / 2.0
    kernel = np.sinc(2.0 * fc * index) * np.hamming(taps)
    kernel = kernel / np.sum(kernel)
    return np.convolve(values, kernel, mode="same")


def normalize_rms(values: np.ndarray, target_rms: float) -> np.ndarray:
    current = float(np.sqrt(np.mean(values * values))) if values.size else 0.0
    if current <= 0.0:
        return values
    return values * (target_rms / current)


def write_wav(path: Path, samples: np.ndarray, sample_rate: int) -> None:
    """写 16-bit 单声道 PCM WAV（与 core.signal.WavWriter 输出格式一致）。"""
    ensure_dir(path.parent)
    scaled = np.clip(samples, -0.999, 0.999)
    pcm = (scaled * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(sample_rate)
        handle.writeframes(pcm.tobytes())


# ------------------------------------------------------------------ 01 课题02 功耗

POWER_CAPACITY_MAH = 4355.0
POWER_NOMINAL_V = 3.70  # 锂离子额定电压；瞬时电压读数见 battery_voltage_mv
POWER_CAPACITY_WH = POWER_CAPACITY_MAH * POWER_NOMINAL_V / 1000.0

POWER_CONDITIONS = [
    {
        "id": "IDLE", "label": "待机", "block": "A", "base_w": 0.60, "drift": 0.02,
        "noise": 0.04, "screen_on": 0, "brightness": 0, "cpu_freq": 780,
        "cpu_freq_max": 1100, "net_rx": 3.0, "net_tx": 1.2, "fps": None,
        "cam_nav": 0, "temp_start": 28.8, "temp_rise_per_min": 0.075,
        "cpu_extra": 5.5, "lux": 4.0, "foreground": "com.android.systemui",
        "note": "演示数据：锁屏息屏待机，未充电",
    },
    {
        "id": "VIDEO", "label": "短视频", "block": "B", "base_w": 2.50, "drift": 0.06,
        "noise": 0.05, "screen_on": 1, "brightness": 50, "cpu_freq": 1480,
        "cpu_freq_max": 2100, "net_rx": 880.0, "net_tx": 46.0, "fps": 30.0,
        "cam_nav": 0, "temp_start": 31.6, "temp_rise_per_min": 0.115,
        "cpu_extra": 6.0, "lux": 210.0, "foreground": "com.example.demo.video",
        "note": "演示数据：同一短视频 App 连续播放，亮度 50%，音量 50%",
    },
    {
        "id": "NAVCAM", "label": "导航拍摄", "block": "C", "base_w": 4.50, "drift": 0.05,
        "noise": 0.05, "screen_on": 1, "brightness": 60, "cpu_freq": 1860,
        "cpu_freq_max": 2400, "net_rx": 620.0, "net_tx": 1250.0, "fps": None,
        "cam_nav": 1, "temp_start": 33.5, "temp_rise_per_min": 0.10,
        "cpu_extra": 6.5, "lux": 175.0, "foreground": "com.example.demo.navcam",
        "note": "演示数据：导航 15 min + 1080p 录像 15 min，定位与相机常开",
    },
    {
        "id": "GAME", "label": "高帧率游戏", "block": "D", "base_w": 5.90, "drift": 0.10,
        "noise": 0.06, "screen_on": 1, "brightness": 55, "cpu_freq": 2280,
        "cpu_freq_max": 2800, "net_rx": 260.0, "net_tx": 38.0, "fps": 75.0,
        "cam_nav": 0, "temp_start": 34.0, "temp_rise_per_min": 0.25,
        "cpu_extra": 8.5, "lux": 150.0, "foreground": "com.example.demo.game",
        "note": "演示数据：90/120fps 模式游戏，画质高，后段出现热节流",
    },
]

POWER_SESSION_HEADER = [
    "session_id", "condition_id", "condition_label", "sample_index", "elapsed_sec",
    "timestamp_iso", "timestamp_utc", "battery_level_pct", "battery_voltage_mv",
    "battery_temp_c", "current_now_ua", "charge_counter_uah", "energy_counter_nwh",
    "power_w", "thermal_status", "cpu_temp_c", "cpu_freq_mhz_avg", "cpu_freq_mhz_max",
    "battery_cycle_count", "battery_health", "charging", "screen_on",
    "screen_brightness_pct", "ambient_lux", "net_type", "net_rx_kbps", "net_tx_kbps",
    "mem_available_mb", "mem_total_mb", "fps", "foreground_pkg", "note",
]
POWER_CAMPAIGN_HEADER = ["campaign_id", "block"] + POWER_SESSION_HEADER[1:]
POWER_SUMMARY_HEADER = [
    "condition_id", "condition_label", "duration_min", "start_level_pct", "end_level_pct",
    "level_drop_pct", "avg_power_w", "peak_power_w", "energy_wh", "avg_current_ma",
    "temp_start_c", "temp_end_c", "temp_rise_c", "thermal_max", "thermal_rise_events",
    "avg_cpu_freq_mhz", "avg_screen_brightness_pct", "net_rx_mb", "net_tx_mb", "notes",
]
POWER_RAW_HEADER = [
    "elapsed_sec", "timestamp_iso", "battery_level_pct", "current_now_ua", "voltage_mv",
    "battery_temp_c", "thermal_status", "cpu_temp_c", "cpu_freq_mhz_avg", "screen_on",
    "fps", "net_rx_kbps", "net_tx_kbps",
]

POWER_DEVICE = {
    "model": "Pixel 7", "manufacturer": "Google", "brand": "google",
    "device": "panther", "androidRelease": "14", "sdkInt": 34,
    "socManufacturer": "Google", "socModel": "Tensor G2",
    "abi": "arm64-v8a", "supportedAbis": "arm64-v8a;armeabi-v7a",
    "screenWidthPx": 1080, "screenHeightPx": 2400, "densityDpi": 420,
    "refreshRateHz": 90.0, "batteryCapacityMah": int(POWER_CAPACITY_MAH),
    "locale": "zh-CN", "timeZone": "Asia/Shanghai",
}


def thermal_status_for(cpu_temp: float) -> int:
    if cpu_temp < 40.0:
        return 0
    if cpu_temp < 45.0:
        return 1
    if cpu_temp < 50.0:
        return 2
    if cpu_temp < 55.0:
        return 3
    if cpu_temp < 60.0:
        return 4
    return 5


def simulate_power_condition(condition: dict, rng: np.random.Generator, state: dict) -> list[dict]:
    """按 6 个采样点模拟一个工况；state 在 4 个工况之间连续演进。

    采样点覆盖 0/5/.../25 min（数据契约的 6 行）；最后一个采样点之后仍有 5 min
    未采样区间，按末点功率计入电量，使下一工况起始电量与上一工况结束值衔接。
    """
    session_id = condition["session_id"]
    start_moment = state["moment"]
    elapsed_list = [300 * index for index in range(6)]
    if condition["id"] == "VIDEO":
        elapsed_list[4] = 1330  # 演示：一次迟到样本（后台休眠导致采样延迟 130 s）
    powers = []
    for index in range(6):
        power = condition["base_w"] * (1.0 + condition["drift"] * index / 5.0)
        power *= 1.0 + rng.normal(0.0, condition["noise"])
        if condition["id"] == "GAME" and index >= 4:
            power *= 0.97  # 后段热节流导致功耗略降
        powers.append(power)
    levels = [state["level"]]
    cumulative = [state["energy_wh"]]
    for index in range(1, 6):
        interval_s = max(1.0, float(elapsed_list[index] - elapsed_list[index - 1]))
        interval_wh = (powers[index - 1] + powers[index]) / 2.0 * interval_s / 3600.0
        cumulative.append(cumulative[-1] + interval_wh)
        levels.append(max(0.0, levels[-1] - interval_wh / POWER_CAPACITY_WH * 100.0))
    samples = []
    for index, elapsed in enumerate(elapsed_list):
        level = levels[index]
        voltage = int(round(3400.0 + 950.0 * (max(level, 0.0) / 100.0) ** 0.75))
        power = powers[index]
        current_ua = int(round(power * 1e9 / voltage))
        battery_temp = condition["temp_start"] + condition["temp_rise_per_min"] * (elapsed / 60.0)
        battery_temp += rng.normal(0.0, 0.12)
        cpu_temp = battery_temp + condition["cpu_extra"] + 1.1 * power + rng.normal(0.0, 0.25)
        thermal = thermal_status_for(cpu_temp)
        cpu_freq = condition["cpu_freq"] * (1.0 + rng.normal(0.0, 0.06))
        if thermal >= 3:  # 热状态达到 3 起触发降频（Pixel thermal status 语义）
            cpu_freq = min(cpu_freq, condition["cpu_freq"] * 0.80)
        if condition["id"] == "GAME" and index >= 3:
            cpu_freq = min(cpu_freq, condition["cpu_freq"] * (0.92 - 0.05 * (index - 3)))
        fps = condition["fps"]
        if fps is not None:
            fps = fps * (1.0 - 0.10 * (index / 5.0)) if condition["id"] == "GAME" else fps
            fps += rng.normal(0.0, 0.6)
        note = condition["note"]
        if condition["id"] == "VIDEO" and index == 4:
            note = "演示数据：迟到样本 late_sample（后台休眠，实际间隔 430 s）"
        sample = {
            "session_id": session_id,
            "condition_id": condition["id"],
            "condition_label": condition["label"],
            "sample_index": index + 1,
            "elapsed_sec": elapsed,
            "timestamp_iso": iso_local(start_moment + timedelta(seconds=elapsed)),
            "timestamp_utc": iso_utc(start_moment + timedelta(seconds=elapsed)),
            "battery_level_pct": round(level, 1),
            "battery_voltage_mv": voltage,
            "battery_temp_c": round(battery_temp, 1),
            "current_now_ua": -current_ua,
            "charge_counter_uah": int(round(level / 100.0 * POWER_CAPACITY_MAH * 1000)),
            "energy_counter_nwh": int(round(cumulative[index] * 1e9)),
            "power_w": round(abs(voltage * current_ua) / 1e9, 3),
            "thermal_status": thermal,
            "cpu_temp_c": round(cpu_temp, 1),
            "cpu_freq_mhz_avg": int(round(cpu_freq)),
            "cpu_freq_mhz_max": int(round(min(condition["cpu_freq_max"], cpu_freq * 1.18))),
            "battery_cycle_count": 128,
            "battery_health": 1,
            "charging": 0,
            "screen_on": condition["screen_on"],
            "screen_brightness_pct": (max(0, int(round(condition["brightness"]
                                                       + rng.normal(0.0, 3.0))))
                                      if condition["screen_on"] else 0),
            "ambient_lux": round(condition["lux"] + rng.normal(0.0, 2.0), 1),
            "net_type": "WIFI",
            "net_rx_kbps": round(max(0.0, condition["net_rx"] * (1.0 + rng.normal(0.0, 0.12))), 1),
            "net_tx_kbps": round(max(0.0, condition["net_tx"] * (1.0 + rng.normal(0.0, 0.12))), 1),
            "mem_available_mb": int(round(3100 - 90 * condition["cam_nav"] + rng.normal(0.0, 60.0))),
            "mem_total_mb": 7800,
            "fps": round(fps, 1) if fps is not None else "",
            "foreground_pkg": condition["foreground"],
            "note": note,
        }
        samples.append(sample)
    # 末点之后再推进 5 min 未采样区间（30 min 工况的收尾），保证工况衔接与时长一致
    tail_wh = powers[-1] * 300.0 / 3600.0
    state["energy_wh"] = cumulative[-1] + tail_wh
    state["level"] = max(0.0, levels[-1] - tail_wh / POWER_CAPACITY_WH * 100.0)
    state["moment"] = start_moment + timedelta(minutes=40)  # 30 min 工况 + 10 min 降温
    return samples


def generate_power_demo(outdir: Path, rng: np.random.Generator) -> None:
    campaign_id = "PC-20261118-001"
    session_dir = ensure_dir(outdir / "20261118_PC-20261118-001")
    state = {"level": 100.0, "energy_wh": 0.0, "moment": POWER_START}
    all_rows: list[dict] = []
    condition_rows: dict[str, list[dict]] = {}
    for index, base_condition in enumerate(POWER_CONDITIONS):
        condition = dict(base_condition)
        condition["session_id"] = f"PT-20261118-{index + 1:03d}"
        rows = simulate_power_condition(condition, rng, state)
        condition_rows[condition["id"]] = rows
        all_rows.extend(rows)
        # session_<condition>.csv（6 行）
        session_path = session_dir / f"session_{condition['id']}.csv"
        # 数值列按数据契约精度格式化（样例里是 float/int，直接写会带全精度）
        formatted = [_format_power_row(sample, POWER_SESSION_HEADER) for sample in rows]
        write_csv(session_path, POWER_SESSION_HEADER, formatted)
        # raw_<condition>.csv（每 5 s 一行，30 min = 360 行，可选高频序列）
        write_power_raw(session_dir / f"raw_{condition['id']}.csv", rows, condition, rng)

    # campaign_<id>.csv：24 行
    campaign_rows = []
    for condition in POWER_CONDITIONS:
        for sample in condition_rows[condition["id"]]:
            row = _format_power_row(sample, POWER_CAMPAIGN_HEADER)
            row[0] = campaign_id
            row[1] = condition["block"]
            campaign_rows.append(row)
    write_csv(session_dir / f"campaign_{campaign_id}.csv", POWER_CAMPAIGN_HEADER, campaign_rows)

    # campaign_summary_<id>.csv：每工况 1 行
    summary_rows = []
    for condition in POWER_CONDITIONS:
        rows = condition_rows[condition["id"]]
        summary_rows.append(_summary_row(rows, condition, campaign_id))
    write_csv(session_dir / f"campaign_summary_{campaign_id}.csv", POWER_SUMMARY_HEADER, summary_rows)

    manifest = {
        "schema": "phonelab.manifest/1",
        "project": "02-power-thermal",
        "app": "PowerTrace",
        "appVersion": "1.0.0-demo",
        "generatedAt": 1763438400000,
        "synthetic": True,
        "dataSource": SYNTHETIC_TAG,
        "disclaimer": DISCLAIMER,
        "device": POWER_DEVICE,
        "campaign": {
            "campaignId": campaign_id,
            "startedAt": iso_local(POWER_START),
            "conditions": [item["id"] for item in POWER_CONDITIONS],
            "order": "A:IDLE B:VIDEO C:NAVCAM D:GAME",
            "sampleIntervalSec": 300,
            "samplesPerCondition": 6,
            "rawSeriesEnabled": True,
            "screenBrightnessPct": 50,
            "charging": False,
        },
    }
    write_json(session_dir / "manifest.json", manifest)
    log_lines = [
        "2026-11-18T13:00:00+08:00 INFO  campaign started PC-20261118-001 (SYNTHETIC DEMO DATA)",
        "2026-11-18T13:00:00+08:00 INFO  block A IDLE 6 samples expected",
        "2026-11-18T14:08:45+08:00 WARN  block B VIDEO sample 5 late by 45 s (late_sample)",
        "2026-11-18T16:50:00+08:00 INFO  campaign finished, 24/24 samples, synthetic demo",
    ]
    (session_dir / "session.log").write_text("\n".join(log_lines) + "\n", encoding="utf-8")
    print(f"[power] 写出 {session_dir.relative_to(outdir)}：4 session + campaign(24 行) + summary + raw")


def _format_power_row(sample: dict, header: list[str]) -> list:
    """按数据字典精度格式化一行：功率 3 位、温度 1 位、电流/电压整数、速率 1 位。"""
    formatted = []
    for column in header:
        value = sample.get(column, "")
        if column in ("power_w",):
            formatted.append(num(value, 3))
        elif column in ("battery_temp_c", "cpu_temp_c", "ambient_lux", "mem_available_mb"):
            formatted.append(num(value, 1 if column != "mem_available_mb" else 0))
        elif column in ("net_rx_kbps", "net_tx_kbps", "fps"):
            formatted.append(num(value, 1))
        elif column in ("battery_level_pct",):
            formatted.append(num(value, 1))
        elif column in ("current_now_ua", "battery_voltage_mv", "thermal_status", "sample_index",
                        "elapsed_sec", "screen_on", "screen_brightness_pct", "mem_total_mb",
                        "charge_counter_uah", "energy_counter_nwh", "battery_cycle_count",
                        "battery_health", "charging", "cpu_freq_mhz_avg", "cpu_freq_mhz_max"):
            formatted.append(int_field(value))
        else:
            formatted.append("" if value is None else str(value))
    return formatted


def _summary_row(rows: list[dict], condition: dict, campaign_id: str) -> list:
    elapsed = np.array([row["elapsed_sec"] for row in rows], dtype=float)
    power = np.array([row["power_w"] for row in rows], dtype=float)
    current_ma = np.array([abs(row["current_now_ua"]) for row in rows], dtype=float) / 1000.0
    rx = np.array([row["net_rx_kbps"] for row in rows], dtype=float)
    tx = np.array([row["net_tx_kbps"] for row in rows], dtype=float)
    durations_h = (elapsed[-1] - elapsed[0]) / 3600.0
    energy_wh = float(np.trapz(power, elapsed) / 3600.0) if hasattr(np, "trapz") else float(np.trapezoid(power, elapsed) / 3600.0)
    start_level = rows[0]["battery_level_pct"]
    end_level = rows[-1]["battery_level_pct"]
    thermal = [row["thermal_status"] for row in rows]
    rise_events = sum(1 for i in range(1, len(thermal)) if thermal[i] > thermal[i - 1])
    avg_interval_h = durations_h if durations_h > 0 else 0.5
    return [
        condition["id"], condition["label"], 30,
        num(start_level, 1), num(end_level, 1), num(start_level - end_level, 1),
        num(float(np.mean(power)), 3), num(float(np.max(power)), 3), num(energy_wh, 3),
        num(float(np.mean(current_ma)), 1),
        num(rows[0]["battery_temp_c"], 1), num(rows[-1]["battery_temp_c"], 1),
        num(rows[-1]["battery_temp_c"] - rows[0]["battery_temp_c"], 1),
        int_field(max(thermal)), str(rise_events),
        num(float(np.mean([row["cpu_freq_mhz_avg"] for row in rows])), 0),
        num(float(np.mean([row["screen_brightness_pct"] for row in rows])), 0),
        num(float(np.mean(rx)) * avg_interval_h * 3600.0 / 8.0 / 1024.0, 2),
        num(float(np.mean(tx)) * avg_interval_h * 3600.0 / 8.0 / 1024.0, 2),
        condition["note"],
    ]


def write_power_raw(path: Path, rows: list[dict], condition: dict, rng: np.random.Generator) -> None:
    """由 6 个采样点线性插值成 5 s 高频序列（演示 App 的"原始 5 s 序列"开关）。"""
    source_elapsed = np.array([row["elapsed_sec"] for row in rows], dtype=float)
    grid = np.arange(0, 1800 + 1, 5, dtype=float)
    out = []
    for elapsed in grid:
        index = int(np.clip(np.searchsorted(source_elapsed, elapsed) - 1, 0, len(rows) - 2))
        span = max(1e-6, source_elapsed[index + 1] - source_elapsed[index])
        ratio = float(np.clip((elapsed - source_elapsed[index]) / span, 0.0, 1.0))
        left, right = rows[index], rows[index + 1]
        power = left["power_w"] * (1 - ratio) + right["power_w"] * ratio
        power *= 1.0 + rng.normal(0.0, 0.02)
        voltage = int(round(left["battery_voltage_mv"] * (1 - ratio) + right["battery_voltage_mv"] * ratio))
        current = int(round(power * 1e9 / voltage))
        level = left["battery_level_pct"] * (1 - ratio) + right["battery_level_pct"] * ratio
        battery_temp = left["battery_temp_c"] * (1 - ratio) + right["battery_temp_c"] * ratio
        cpu_temp = left["cpu_temp_c"] * (1 - ratio) + right["cpu_temp_c"] * ratio
        cpu_freq = left["cpu_freq_mhz_avg"] * (1 - ratio) + right["cpu_freq_mhz_avg"] * ratio
        thermal = right["thermal_status"] if ratio > 0.5 else left["thermal_status"]
        fps = left["fps"] if left["fps"] != "" else ""
        if fps != "":
            fps = float(fps) * (1 - ratio) + float(right["fps"]) * ratio
        rx = left["net_rx_kbps"] * (1 - ratio) + right["net_rx_kbps"] * ratio
        tx = left["net_tx_kbps"] * (1 - ratio) + right["net_tx_kbps"] * ratio
        out.append([
            int_field(elapsed), iso_local(POWER_START + timedelta(seconds=int(elapsed))),
            num(level, 1), int_field(-current), int_field(voltage), num(battery_temp, 1),
            int_field(thermal), num(cpu_temp, 1), int_field(cpu_freq), int_field(condition["screen_on"]),
            num(fps, 1) if fps != "" else "", num(rx, 1), num(tx, 1),
        ])
    write_csv(path, POWER_RAW_HEADER, out)


# ------------------------------------------------------------------ 02 项目三 声振

VIBRO_SESSION_ID = "VD-20261118-001"
VIBRO_SAMPLE_RATE = 22050
VIBRO_DURATION = 3.0
VIBRO_ACCEL_RATE = 200
VIBRO_BLIND_DURATION = 2.0

VIBRO_CONDITIONS = [
    {
        "id": "C1_NORMAL_LOW", "label": "风扇低档正常",
        "audio_f0": 42.0, "audio_dbfs": -30.0, "harm_decay": 1.05, "n_harm": 14,
        "sideband": 0.0, "lowpass_hz": 0.0, "broadband": 0.004, "hf_boost": 0.0,
        "hairdryer": False, "floor": 0.0010, "hiss": 0.0060,
        "accel_f": 24.0, "accel_amp": 1.05, "accel_broad": 0.10, "accel_sideband": 0.0,
        "placement": "手机平放于风扇底座正前方 30cm 桌面（演示数据）",
    },
    {
        "id": "C2_NORMAL_HIGH", "label": "风扇高档正常",
        "audio_f0": 66.0, "audio_dbfs": -24.0, "harm_decay": 1.15, "n_harm": 14,
        "sideband": 0.0, "lowpass_hz": 0.0, "broadband": 0.010, "hf_boost": 0.0,
        "hairdryer": False, "floor": 0.0014, "hiss": 0.0080,
        "accel_f": 33.0, "accel_amp": 1.85, "accel_broad": 0.16, "accel_sideband": 0.0,
        "placement": "手机平放于风扇底座正前方 30cm 桌面（演示数据）",
    },
    {
        "id": "C3_IMBALANCE", "label": "扇叶轻微失衡",
        "audio_f0": 42.0, "audio_dbfs": -28.6, "harm_decay": 1.10, "n_harm": 14,
        "sideband": 0.36, "lowpass_hz": 0.0, "broadband": 0.006, "hf_boost": 0.0,
        "hairdryer": False, "floor": 0.0012, "hiss": 0.0060,
        "accel_f": 24.0, "accel_amp": 1.98, "accel_broad": 0.12, "accel_sideband": 0.50,
        "placement": "手机平放于风扇底座正前方 30cm 桌面（演示数据，1 片扇叶贴 2 g 配重）",
    },
    {
        "id": "C4_BLOCKED", "label": "风道遮挡",
        "audio_f0": 42.0, "audio_dbfs": -26.0, "harm_decay": 1.35, "n_harm": 14,
        "sideband": 0.0, "lowpass_hz": 1500.0, "broadband": 0.030, "hf_boost": 0.0,
        "hairdryer": False, "floor": 0.0016, "hiss": 0.0110,
        "accel_f": 24.0, "accel_amp": 0.85, "accel_broad": 0.55, "accel_sideband": 0.0,
        "placement": "手机平放于风扇底座正前方 30cm 桌面（演示数据，进风口遮挡 1/2）",
    },
    {
        "id": "C5_HAIRDRYER", "label": "吹风机热风档",
        "audio_f0": 230.0, "audio_dbfs": -19.0, "harm_decay": 1.6, "n_harm": 10,
        "sideband": 0.0, "lowpass_hz": 0.0, "broadband": 0.050, "hf_boost": 0.075,
        "hairdryer": True, "floor": 0.0020, "hiss": 0.0350,
        "accel_f": 78.0, "accel_amp": 2.20, "accel_broad": 0.90, "accel_sideband": 0.0,
        "placement": "手机平放于吹风机正前方 30cm 桌面（演示数据，热风高速档）",
    },
]


def synth_vibro_audio(condition: dict, sample_rate: int, duration: float,
                      rng: np.random.Generator) -> np.ndarray:
    """合成音频：正弦/谐波 + 边带 + 低通/高通噪声，按目标 dBFS 定标。"""
    n = int(round(sample_rate * duration))
    t = np.arange(n, dtype=float) / sample_rate
    f0 = condition["audio_f0"] * (1.0 + rng.normal(0.0, 0.012))
    signal = np.zeros(n, dtype=float)
    for harmonic in range(1, condition["n_harm"] + 1):
        amplitude = harmonic ** (-condition["harm_decay"])
        phase = rng.uniform(0.0, 2.0 * math.pi)
        signal += amplitude * np.sin(2.0 * math.pi * harmonic * f0 * t + phase)
    if condition["sideband"] > 0.0:
        modulation = 1.7  # 失衡/轴承边带频率
        for harmonic in (1, 2):
            amp = condition["sideband"] * harmonic ** (-1.15)
            signal += amp * (np.sin(2.0 * math.pi * (harmonic * f0 - modulation) * t)
                             + 0.7 * np.sin(2.0 * math.pi * (harmonic * f0 + modulation) * t))
    if condition["lowpass_hz"] > 0.0:
        signal = fir_lowpass(signal, sample_rate, condition["lowpass_hz"])
    if condition["hairdryer"]:
        # 吹风机：电机音 + 高频宽带气流噪声
        tone = (0.55 * np.sin(2.0 * math.pi * 245.0 * t)
                + 0.30 * np.sin(2.0 * math.pi * 490.0 * t)
                + 0.15 * np.sin(2.0 * math.pi * 735.0 * t))
        signal = 0.35 * signal + tone
    white = rng.normal(0.0, 1.0, n)
    broadband = moving_average(white, int(sample_rate / 1800.0))  # 宽带的低频段成分
    signal = signal + condition["broadband"] * broadband / (np.std(broadband) + 1e-12)
    if condition["hf_boost"] > 0.0:
        high = white - moving_average(white, int(sample_rate / 2500.0))
        signal = signal + condition["hf_boost"] * high / (np.std(high) + 1e-12)
    # 真实录音总有宽带气流/环境底噪（高频），加一层小幅 hiiss 让抗噪曲线更接近真实
    if condition.get("hiss", 0.0) > 0.0:
        hiss = white - moving_average(white, int(sample_rate / 3000.0))
        signal = signal + condition["hiss"] * hiss / (np.std(hiss) + 1e-12)
    signal = normalize_rms(signal, 10.0 ** (condition["audio_dbfs"] / 20.0))
    signal = signal + rng.normal(0.0, condition["floor"], n)
    peak = float(np.max(np.abs(signal)))
    if peak > 0.92:
        signal = signal * (0.92 / peak)
    return signal


def synth_vibro_accel(condition: dict, sample_rate: int, duration: float,
                      rng: np.random.Generator) -> dict:
    """合成三轴加速度：1x/2x/3x 转频 + 边带 + 宽带；z 轴叠加 1g 重力。"""
    n = int(round(sample_rate * duration))
    t = np.arange(n, dtype=float) / sample_rate
    frequency = condition["accel_f"] * (1.0 + rng.normal(0.0, 0.015))
    amp = condition["accel_amp"]
    base = amp * (np.sin(2.0 * math.pi * frequency * t + rng.uniform(0.0, math.pi))
                  + 0.45 * np.sin(2.0 * math.pi * 2.0 * frequency * t + rng.uniform(0.0, math.pi))
                  + 0.22 * np.sin(2.0 * math.pi * 3.0 * frequency * t + rng.uniform(0.0, math.pi)))
    if condition["accel_sideband"] > 0.0:
        side = 0.9 * amp * (np.sin(2.0 * math.pi * (frequency - 0.8) * t)
                            + 0.8 * np.sin(2.0 * math.pi * (frequency + 0.8) * t))
        base = base + condition["accel_sideband"] * side
    noise_std = condition["accel_broad"]
    x = 0.6 * base + rng.normal(0.0, noise_std, n)
    y = 0.4 * base + rng.normal(0.0, noise_std, n)
    z = 9.80665 + 0.8 * base + rng.normal(0.0, noise_std, n)
    return {"x": x, "y": y, "z": z, "sample_rate_hz": float(sample_rate)}


def write_accel_csv(path: Path, data: dict, start_ns: int) -> None:
    fs = data["sample_rate_hz"]
    count = len(data["x"])
    rows = []
    for index in range(count):
        x = float(data["x"][index])
        y = float(data["y"][index])
        z = float(data["z"][index])
        magnitude = math.sqrt(x * x + y * y + z * z) / 9.80665
        rows.append([
            str(start_ns + int(round(index * 1e9 / fs))),
            num(index * 1000.0 / fs, 3),
            num(x, 4), num(y, 4), num(z, 4), num(magnitude, 6), num(fs, 1),
        ])
    write_csv(path, ["t_ns", "t_ms", "x", "y", "z", "mag_g", "sample_rate_hz"], rows)


def jitter_condition(condition: dict, rng: np.random.Generator, strength: float = 1.0) -> dict:
    """对工况参数加小的组间扰动，模拟真实采集的电压波动/档位差异/设备温漂。

    这样同一工况的不同组不再完全同分布，kNN 的 LOOCV 才不会出现不真实的 100%。
    """
    out = dict(condition)
    out["audio_f0"] = condition["audio_f0"] * (1.0 + rng.normal(0.0, 0.030 * strength))
    out["audio_dbfs"] = condition["audio_dbfs"] + rng.normal(0.0, 1.4 * strength)
    out["harm_decay"] = max(0.6, condition["harm_decay"] * (1.0 + rng.normal(0.0, 0.05 * strength)))
    out["sideband"] = max(0.0, condition["sideband"] * (1.0 + rng.normal(0.0, 0.30 * strength)))
    out["broadband"] = max(0.0, condition["broadband"] * (1.0 + rng.normal(0.0, 0.20 * strength)))
    out["hf_boost"] = max(0.0, condition["hf_boost"] * (1.0 + rng.normal(0.0, 0.20 * strength)))
    out["accel_f"] = condition["accel_f"] * (1.0 + rng.normal(0.0, 0.030 * strength))
    out["accel_amp"] = max(0.1, condition["accel_amp"] * (1.0 + rng.normal(0.0, 0.150 * strength)))
    out["accel_broad"] = max(0.0, condition["accel_broad"] * (1.0 + rng.normal(0.0, 0.20 * strength)))
    out["accel_sideband"] = max(0.0, condition["accel_sideband"] * (1.0 + rng.normal(0.0, 0.30 * strength)))
    return out


def generate_vibro_demo(outdir: Path, rng: np.random.Generator) -> None:
    session_dir = ensure_dir(outdir / "20261118_VD-20261118-001")
    condition_labels = {item["id"]: item["label"] for item in VIBRO_CONDITIONS}
    training_features: list[np.ndarray] = []
    training_labels: list[str] = []
    index_rows: list[list] = []
    recorded_base_ms = 1763449200000
    for condition in VIBRO_CONDITIONS:
        for group in range(1, 4):
            group_dir = ensure_dir(session_dir / condition["id"] / f"g{group:02d}")
            stem = f"g{group:02d}_{condition['id']}"
            jittered = jitter_condition(condition, rng)
            audio = synth_vibro_audio(jittered, VIBRO_SAMPLE_RATE, VIBRO_DURATION, rng)
            accel = synth_vibro_accel(jittered, VIBRO_ACCEL_RATE, VIBRO_DURATION, rng)
            wav_path = group_dir / f"{stem}_audio.wav"
            accel_path = group_dir / f"{stem}_accel.csv"
            write_wav(wav_path, audio, VIBRO_SAMPLE_RATE)
            write_accel_csv(accel_path, accel, recorded_base_ms * 1_000_000)
            # 与 App 一致：特征从落盘的 PCM 重新提取（16-bit 量化后的真实输入）
            features = vf.extract_from_files(wav_path, accel_path)
            labels = {
                "condition_id": condition["id"],
                "condition_label": condition["label"],
                "group_index": group,
                "file_stem": stem,
                "recorded_at_ms": recorded_base_ms,
                "duration_s": f"{VIBRO_DURATION:.1f}",
            }
            vf.write_features_csv(group_dir / f"{stem}_features.csv",
                                  vf.feature_row(features, labels))
            meta = {
                "schema": "phonelab.vibro/1",
                "session_id": VIBRO_SESSION_ID,
                "condition_id": condition["id"],
                "condition_label": condition["label"],
                "group_index": group,
                "recorded_at_ms": recorded_base_ms,
                "duration_s": VIBRO_DURATION,
                "synthetic": True,
                "dataSource": SYNTHETIC_TAG,
                "audio": {
                    "sample_rate": VIBRO_SAMPLE_RATE, "channels": 1, "bits": 16,
                    "source": "UNPROCESSED", "file": f"{stem}_audio.wav",
                    "rms_dbfs": round(float(features["audio_rms_dbfs"]), 2),
                    "peak_dbfs": round(float(features["audio_peak_dbfs"]), 2),
                },
                "accel": {
                    "sample_rate_hz": int(VIBRO_ACCEL_RATE),
                    "file": f"{stem}_accel.csv",
                    "rms_ms2": round(float(features["accel_rms"]), 3),
                    "peak_ms2": round(float(features["accel_peak"]), 3),
                    "samples": int(round(VIBRO_ACCEL_RATE * VIBRO_DURATION)),
                },
                "placement": condition["placement"],
                "device_distance_cm": 30,
                "noise_condition": "quiet",
                "noise_spl_db": None,
                "operator": "synthetic",
                "notes": "合成演示数据，仅供分析管线验证",
            }
            write_json(group_dir / f"{stem}_meta.json", meta)
            training_features.append(np.asarray([features[name] for name in vf.NUMERIC_COLUMNS]))
            training_labels.append(condition["id"])
            recorded_base_ms += 20000
        index_rows.append([VIBRO_SESSION_ID, condition["id"], condition["label"],
                           "3", "3", "1"])

    write_csv(session_dir / "dataset_index.csv",
              ["session_id", "condition_id", "condition_label", "groups_collected",
               "target_groups", "complete"], index_rows)

    # 盲测组：每个工况 3 组，参数加扰动，采集时不显示标签（目录放在 blind/ 下，
    # 分析脚本按 session/<condition>/g*/ 递归扫描时不会把它们当训练样本）
    X_train = np.vstack(training_features)
    classifier = vm.KnnClassifier().fit(X_train, training_labels)
    blind_rows = []
    trial = 1
    for condition in VIBRO_CONDITIONS:
        for group in range(1, 4):
            blind_dir = ensure_dir(session_dir / "blind" / condition["id"] / f"b{group:02d}")
            stem = f"b{group:02d}_{condition['id']}"
            jittered = jitter_condition(condition, rng, strength=1.4)
            audio = synth_vibro_audio(jittered, VIBRO_SAMPLE_RATE, VIBRO_BLIND_DURATION, rng)
            accel = synth_vibro_accel(jittered, VIBRO_ACCEL_RATE, VIBRO_BLIND_DURATION, rng)
            wav_path = blind_dir / f"{stem}_audio.wav"
            accel_path = blind_dir / f"{stem}_accel.csv"
            write_wav(wav_path, audio, VIBRO_SAMPLE_RATE)
            write_accel_csv(accel_path, accel, recorded_base_ms * 1_000_000)
            features = vf.extract_from_files(wav_path, accel_path)
            vector = np.asarray([features[name] for name in vf.NUMERIC_COLUMNS])
            prediction = classifier.predict(vector)
            blind_rows.append([
                VIBRO_SESSION_ID, str(trial), condition["id"], prediction["label"],
                num(prediction["confidence"], 3),
                "1" if prediction["label"] == condition["id"] else "0",
                f"blind/{condition['id']}/b{group:02d}/{stem}_audio.wav",
                vm.DESCRIPTOR,
                iso_local(VIBRO_START + timedelta(minutes=trial)),
            ])
            recorded_base_ms += 20000
            trial += 1
    write_csv(session_dir / f"blind_test_{VIBRO_SESSION_ID}.csv",
              ["session_id", "trial_index", "condition_id_true", "condition_id_predicted",
               "predicted_confidence", "correct", "features_file", "model", "timestamp_iso"],
              blind_rows)
    manifest = {
        "schema": "phonelab.manifest/1",
        "project": "03-vibro-diag",
        "app": "VibroDiag",
        "appVersion": "1.0.0-demo",
        "generatedAt": 1763449200000,
        "synthetic": True,
        "dataSource": SYNTHETIC_TAG,
        "disclaimer": DISCLAIMER,
        "device": POWER_DEVICE,
        "session": {
            "sessionId": VIBRO_SESSION_ID,
            "startedAt": iso_local(VIBRO_START),
            "conditions": [item["id"] for item in VIBRO_CONDITIONS],
            "groupsPerCondition": 3,
            "targetGroupsPerCondition": 3,
            "durationSec": VIBRO_DURATION,
            "audioSampleRate": VIBRO_SAMPLE_RATE,
            "audioBitsPerSample": 16,
            "accelSampleRateHz": VIBRO_ACCEL_RATE,
            "model": vm.DESCRIPTOR,
            "note": "演示数据：为控制仓库体积，每组只采 3 次、每次 3 s、22.05 kHz（真机为 15 次 x 15 s x 44.1 kHz）",
        },
    }
    write_json(session_dir / "manifest.json", manifest)
    (session_dir / "session.log").write_text(
        "2026-11-18T15:00:00+08:00 INFO  session started (SYNTHETIC DEMO DATA), 5 conditions x 3 groups\n"
        "2026-11-18T15:46:00+08:00 INFO  dataset complete 15/15, blind test 15 trials\n",
        encoding="utf-8")
    print(f"[vibro] 写出 {session_dir.relative_to(outdir)}：5x3 训练组 + 15 盲测组")


# ------------------------------------------------------------------ 入口

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成 data/demo 合成演示数据（synthetic）")
    parser.add_argument("--out", default=str(REPO_ROOT / "data" / "demo"),
                        help="输出根目录，默认 <repo>/data/demo")
    parser.add_argument("--seed", type=int, default=20261118, help="随机种子（可复现）")
    parser.add_argument("--only", choices=["power", "vibro"], default=None,
                        help="只生成某一类数据")
    args = parser.parse_args(argv)
    outdir = Path(args.out).resolve()
    ensure_dir(outdir)
    if args.only in (None, "power"):
        generate_power_demo(outdir / "power", np.random.default_rng(args.seed))
    if args.only in (None, "vibro"):
        generate_vibro_demo(outdir / "vibro", np.random.default_rng(args.seed))
    print(f"完成：合成演示数据已写入 {outdir}（synthetic，非真实实验数据）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
