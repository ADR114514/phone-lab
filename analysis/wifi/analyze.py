"""课题03 · 教学楼/宿舍无线信号盲区测绘 —— SignalMap 数据分析管线。

输入：SignalMap 导出目录（docs/DATA_DICTIONARY.md 第二节）
      survey_<sessionId>.csv（8 点位 x 3 次 = 24 行）、points.csv、scan_*_r*.csv、manifest.json
输出：<out>/tables/*.csv（点位指标、路径损耗拟合、材质衰减、多径相关性、邻道干扰、盲区分级）、
      <out>/figures/*.png、<out>/wifi_report.md

方法要点：
  * 每个点位 3 次重复：RSSI 取均值/标准差，延迟取均值与 P95，丢包取均值，给出稳定性评分；
  * 对数距离路径损耗模型 P(d) = P0 - 10 n log10(d/d0)：
      - 以基准点（默认 P01）的 RSSI 与距离为 (P0, d0)；
      - 只用无附加墙体（wall_material=none）的点位拟合路径损耗指数 n，避免把墙体损耗混进 n；
      - 每个点位的“等效附加衰减” = 自由空间预测 - 实测（正数表示墙体/多径额外损耗）；
  * 按墙体材质分组统计附加衰减（均值/中位数/箱线图），并给出与经验参考值的对照；
  * 多径：RSSI 标准差、延迟抖动与遮挡类型/附加衰减的相关性（Pearson + Spearman，numpy 实现）；
  * 邻道干扰：从 scan_*.csv 统计同信道 AP 数与最强同信道 RSSI，并评估其与延迟/丢包的关系；
  * 盲区分级：强/中/弱/盲区，并给出 AP 补点、信道规划等改善建议。

用法：
    python analysis/wifi/analyze.py --data data/raw/wifi/<session> --out analysis/out/wifi
    python analysis/wifi/analyze.py --data data/demo/wifi/20261118_WS-20261118-001 --out analysis/out/wifi
"""
from __future__ import annotations

import argparse
import json
import re
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
import matplotlib.pyplot as plt  # noqa: E402

MATERIAL_LABELS = {
    "none": "无墙体", "concrete": "混凝土", "brick": "砖墙", "gypsum": "石膏板",
    "glass": "玻璃", "metal": "金属", "mixed": "混合",
}
MATERIAL_REFERENCE_DB = {"concrete": 18.0, "metal": 25.0, "glass": 6.0, "mixed": 14.0,
                         "brick": 12.0, "gypsum": 4.0, "none": 0.0}
GRADE_ORDER = ["强", "中", "弱", "盲区"]
GRADE_COLORS = {"强": "#00A878", "中": "#F2C230", "弱": "#FF6B35", "盲区": "#E74C3C"}

SURVEY_COLUMNS = [
    "session_id", "point_id", "point_name", "point_type", "wall_material", "obstruction",
    "distance_m", "floor", "repeat_index", "timestamp_iso", "ssid", "bssid", "rssi_dbm",
    "frequency_mhz", "channel", "latency_avg_ms", "latency_min_ms", "latency_max_ms",
    "latency_median_ms", "latency_p95_ms", "jitter_ms", "loss_pct", "tcp_connect_ms",
    "http_ttfb_ms", "http_total_ms", "ap_count", "strongest_neighbor_rssi",
    "neighbor_channels",
]
NUMERIC_SURVEY_COLUMNS = [
    "distance_m", "floor", "repeat_index", "rssi_dbm", "link_speed_mbps", "frequency_mhz",
    "channel", "latency_avg_ms", "latency_min_ms", "latency_max_ms", "latency_median_ms",
    "latency_p95_ms", "jitter_ms", "loss_pct", "tcp_connect_ms", "http_ttfb_ms",
    "http_total_ms", "down_kbps", "ap_count", "strongest_neighbor_rssi",
]
SCAN_COLUMNS = ["timestamp_iso", "bssid", "ssid", "frequency_mhz", "channel", "rssi_dbm",
                "link_speed_mbps", "capabilities"]
POINTS_COLUMNS = ["point_id", "point_name", "point_type", "wall_material", "obstruction",
                  "distance_m", "floor", "photo_file"]


# ---------------------------------------------------------------- 输入

def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="课题03 WiFi 盲区测绘分析")
    parser.add_argument("--data", default=None,
                        help="SignalMap 导出目录；缺省时自动在 data/demo/wifi 或 data/raw/wifi 下找最新会话")
    parser.add_argument("--out", default=None, help="输出目录，默认 analysis/out/wifi")
    parser.add_argument("--reference-point", default=None,
                        help="路径损耗参考点位（默认取 P01，其次取 wall_material=none 的点位）")
    parser.add_argument("--n-pathloss", type=float, default=None,
                        help="强制指定路径损耗指数 n（默认用无遮挡点位拟合）")
    args = parser.parse_args(argv)
    repo_root = ANALYSIS_DIR.parent
    if args.data is None:
        args.data = str(find_latest_session_dir([repo_root / "data" / "demo" / "wifi",
                                                 repo_root / "data" / "raw" / "wifi"]))
    if args.out is None:
        args.out = str(repo_root / "analysis" / "out" / "wifi")
    return args


def find_latest_session_dir(candidates: list[Path]) -> Path:
    for root in candidates:
        if not root.is_dir():
            continue
        sessions = [p for p in root.iterdir() if p.is_dir() and list(p.glob("survey_*.csv"))]
        if sessions:
            return sorted(sessions, key=lambda p: p.stat().st_mtime)[-1]
    raise FileNotFoundError("没有找到包含 survey_*.csv 的 SignalMap 导出目录，请用 --data 指定")


def load_wifi_dir(data_dir: Path) -> dict:
    if not data_dir.is_dir():
        raise FileNotFoundError(f"数据目录不存在: {data_dir}")
    survey_paths = sorted(data_dir.glob("survey_*.csv"))
    if not survey_paths:
        raise FileNotFoundError(f"缺少 survey_*.csv（目录: {data_dir}）")
    survey = io_utils.read_csvs(survey_paths, required=SURVEY_COLUMNS)
    survey = io_utils.coerce_numeric(survey, NUMERIC_SURVEY_COLUMNS)
    points = None
    points_path = data_dir / "points.csv"
    if points_path.is_file():
        points = io_utils.coerce_numeric(
            io_utils.read_csv(points_path, required=POINTS_COLUMNS, name="points.csv"),
            ["distance_m", "floor"])
    scans: dict[tuple[str, int], pd.DataFrame] = {}
    for path in sorted(data_dir.glob("scan_*.csv")):
        match = re.search(r"scan_.*?_([A-Za-z]\d+)_r(\d+)\.csv$", path.name)
        if not match:
            continue
        point_id, repeat = match.group(1), int(match.group(2))
        frame = io_utils.coerce_numeric(
            io_utils.read_csv(path, name=path.name),
            ["frequency_mhz", "channel", "rssi_dbm", "link_speed_mbps"])
        scans[(point_id, repeat)] = frame
    manifest = {}
    manifest_path = data_dir / "manifest.json"
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            manifest = {}
    return {"dir": data_dir, "survey": survey, "points": points, "scans": scans,
            "manifest": manifest}


def is_synthetic(data: dict) -> bool:
    if data["manifest"].get("synthetic") is True:
        return True
    return "demo" in {part.lower() for part in data["dir"].parts}


# ---------------------------------------------------------------- 校验

def validate_survey(data: dict) -> tuple[pd.DataFrame, list[str]]:
    survey = data["survey"].copy()
    point_ids = sorted(survey["point_id"].astype(str).unique())
    issues: list[str] = []
    rows = []
    if len(survey) != 24:
        issues.append(f"survey 行数 {len(survey)} != 24（8 点位 x 3 次）")
    if len(point_ids) != 8:
        issues.append(f"点位数量 {len(point_ids)} != 8")
    for point_id in point_ids:
        frame = survey[survey["point_id"].astype(str) == point_id]
        repeats = sorted(frame["repeat_index"].dropna().astype(int).unique().tolist())
        missing_repeats = [index for index in (1, 2, 3) if index not in repeats]
        scan_count = sum(1 for repeat in repeats if (point_id, repeat) in data["scans"])
        rssi = frame["rssi_dbm"].to_numpy(dtype=float)
        invalid_rssi = int(np.sum(~np.isfinite(rssi) | (rssi > -25) | (rssi < -95)))
        rows.append({
            "point_id": point_id,
            "repeats": len(repeats),
            "missing_repeats": ";".join(str(i) for i in missing_repeats) if missing_repeats else "",
            "scan_files": scan_count,
            "invalid_rssi": invalid_rssi,
            "latency_missing": int(frame["latency_avg_ms"].isna().sum()),
            "loss_missing": int(frame["loss_pct"].isna().sum()),
        })
        if missing_repeats:
            issues.append(f"{point_id}: 缺失重复次数 {missing_repeats}")
        if scan_count < len(repeats):
            issues.append(f"{point_id}: scan 明细不齐（{scan_count}/{len(repeats)}）")
        if invalid_rssi:
            issues.append(f"{point_id}: {invalid_rssi} 行 RSSI 超出 -95~-25 dBm 合理区间")
    quality = pd.DataFrame(rows)
    bssids = survey["bssid"].dropna().astype(str).tolist()
    raw_mac = [b for b in bssids if re.fullmatch(r"[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}", b)]
    if raw_mac:
        issues.append(f"检测到 {len(raw_mac)} 行未哈希的 BSSID（公开仓库前应按 SOP 哈希脱敏）")
    return quality, issues


# ---------------------------------------------------------------- 点位指标

def build_point_metrics(data: dict) -> pd.DataFrame:
    survey = data["survey"].copy()
    survey["point_id"] = survey["point_id"].astype(str)
    points = data["points"]
    rows = []
    for point_id in sorted(survey["point_id"].unique()):
        frame = survey[survey["point_id"] == point_id]
        rssi = frame["rssi_dbm"].to_numpy(dtype=float)
        latency = frame["latency_avg_ms"].to_numpy(dtype=float)
        p95 = frame["latency_p95_ms"].to_numpy(dtype=float)
        jitter = frame["jitter_ms"].to_numpy(dtype=float)
        loss = frame["loss_pct"].to_numpy(dtype=float)
        rssi_mean = float(np.nanmean(rssi))
        rssi_std = float(np.nanstd(rssi, ddof=1)) if rssi.size > 1 else 0.0
        latency_mean = float(np.nanmean(latency))
        latency_p95 = float(np.nanmean(p95))
        metadata = frame.iloc[0]
        material = str(metadata.get("wall_material") or "").strip().lower()
        point_type = str(metadata.get("point_type") or "").strip().lower()
        distance = metadata.get("distance_m")
        if not np.isfinite(distance) and points is not None:
            match = points[points["point_id"].astype(str) == point_id]
            if len(match):
                distance = match["distance_m"].iloc[0]
        stability = float(max(0.0, 100.0 * (1.0 - min(1.0, abs(rssi_std) / max(1.0, abs(rssi_mean))))))
        loss_mean = float(np.nanmean(loss))
        rows.append({
            "point_id": point_id,
            "point_name": metadata.get("point_name", ""),
            "point_type": point_type,
            "wall_material": material or "unknown",
            "obstruction": metadata.get("obstruction", ""),
            "distance_m": float(distance) if np.isfinite(distance) else float("nan"),
            "rssi_mean_dbm": rssi_mean,
            "rssi_std_db": rssi_std,
            "rssi_min_dbm": float(np.nanmin(rssi)),
            "rssi_max_dbm": float(np.nanmax(rssi)),
            "latency_mean_ms": latency_mean,
            "latency_p95_ms": latency_p95,
            "jitter_mean_ms": float(np.nanmean(jitter)),
            "loss_mean_pct": loss_mean,
            "stability_pct": stability,
            "ap_count_mean": float(np.nanmean(frame["ap_count"].to_numpy(dtype=float))),
            "strongest_neighbor_rssi": float(np.nanmean(frame["strongest_neighbor_rssi"].to_numpy(dtype=float))),
            "repeats": int(len(frame)),
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------- 路径损耗与材质衰减

def build_pathloss(point_metrics: pd.DataFrame, reference_point: str | None = None,
                   fixed_n: float | None = None) -> tuple[dict, pd.DataFrame]:
    frame = point_metrics.copy()
    frame["point_id"] = frame["point_id"].astype(str)
    reference = None
    if reference_point and reference_point in set(frame["point_id"]):
        reference = frame[frame["point_id"] == reference_point].iloc[0]
    else:
        candidates = frame[(frame["wall_material"] == "none") & frame["distance_m"].notna()]
        if len(candidates):
            reference = candidates.sort_values("distance_m").iloc[0]
        else:
            reference = frame.sort_values("distance_m").iloc[0]
    d0 = float(reference["distance_m"])
    p0 = float(reference["rssi_mean_dbm"])
    fit = {"reference_point": str(reference["point_id"]), "d0_m": d0, "p0_dbm": p0,
           "n": float("nan"), "n_source": "未拟合", "r2_free_space": float("nan"),
           "n_points": 0}
    none_points = frame[(frame["wall_material"] == "none") & frame["distance_m"].notna()
                        & (frame["distance_m"] > 0)]
    n = float(fixed_n) if fixed_n is not None else float("nan")
    if fixed_n is not None:
        fit["n_source"] = "命令行指定"
        fit["n"] = n
    elif len(none_points) >= 2:
        model = stats_utils.log_distance_pathloss(
            none_points["distance_m"].to_numpy(dtype=float),
            none_points["rssi_mean_dbm"].to_numpy(dtype=float),
            reference_distance=d0)
        if np.isfinite(model["n"]) and model["n"] > 0.5:
            n = float(model["n"])
            fit["n"] = n
            fit["n_source"] = f"无遮挡点位最小二乘拟合（{int(model['n_points'])} 点）"
            fit["r2_free_space"] = float(model["r2"])
            fit["n_points"] = int(model["n_points"])
        else:
            n = 2.0
            fit["n"] = 2.0
            fit["n_source"] = "拟合失败，回退自由空间 n=2.0"
    else:
        n = 2.0
        fit["n"] = 2.0
        fit["n_source"] = "无遮挡点位不足 2 个，回退自由空间 n=2.0"
    frame["rssi_pred_free_space_dbm"] = p0 - 10.0 * n * np.log10(
        frame["distance_m"].to_numpy(dtype=float) / d0)
    frame["excess_attenuation_db"] = frame["rssi_pred_free_space_dbm"] - frame["rssi_mean_dbm"]
    frame["material_label"] = frame["wall_material"].map(
        lambda value: MATERIAL_LABELS.get(str(value), str(value)))
    fit["mean_excess_db"] = float(np.nanmean(frame["excess_attenuation_db"]))
    fit["max_excess_db"] = float(np.nanmax(frame["excess_attenuation_db"]))
    fit["r2_all"] = stats_utils.r_squared(
        frame["rssi_mean_dbm"].to_numpy(dtype=float),
        frame["rssi_pred_free_space_dbm"].to_numpy(dtype=float))
    return fit, frame


def build_material_attenuation(point_loss: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for material, group in point_loss.groupby("wall_material"):
        values = group["excess_attenuation_db"].to_numpy(dtype=float)
        values = values[np.isfinite(values)]
        reference = MATERIAL_REFERENCE_DB.get(str(material), float("nan"))
        mean = float(np.mean(values)) if values.size else float("nan")
        rows.append({
            "wall_material": str(material),
            "material_label": MATERIAL_LABELS.get(str(material), str(material)),
            "points": int(values.size),
            "point_ids": ";".join(group["point_id"].astype(str).tolist()),
            "excess_mean_db": mean,
            "excess_median_db": float(np.median(values)) if values.size else float("nan"),
            "excess_std_db": float(np.std(values, ddof=1)) if values.size > 1 else 0.0,
            "excess_min_db": float(np.min(values)) if values.size else float("nan"),
            "excess_max_db": float(np.max(values)) if values.size else float("nan"),
            "reference_db": reference,
            "diff_from_reference_db": mean - reference if np.isfinite(reference) else float("nan"),
        })
    material_order = ["none", "glass", "gypsum", "brick", "concrete", "mixed", "metal"]
    frame = pd.DataFrame(rows)
    frame["_order"] = frame["wall_material"].map(
        lambda value: material_order.index(value) if value in material_order else len(material_order))
    return frame.sort_values("_order").drop(columns="_order").reset_index(drop=True)


# ---------------------------------------------------------------- 邻道干扰

def build_interference(data: dict, point_metrics: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    survey = data["survey"].copy()
    survey["point_id"] = survey["point_id"].astype(str)
    per_repeat = []
    channel_counts: dict[int, list[float]] = {}
    for _, row in survey.iterrows():
        point_id = str(row["point_id"])
        repeat = int(row["repeat_index"]) if np.isfinite(row["repeat_index"]) else 0
        serving_channel = row.get("channel")
        scan = data["scans"].get((point_id, repeat))
        if scan is None or not np.isfinite(serving_channel):
            continue
        channels = pd.to_numeric(scan["channel"], errors="coerce").to_numpy(dtype=float)
        rssi = pd.to_numeric(scan["rssi_dbm"], errors="coerce").to_numpy(dtype=float)
        bssid = scan["bssid"].astype(str).to_numpy()
        same_mask = (channels == float(serving_channel)) & (bssid != str(row.get("bssid")))
        adjacent_mask = (np.abs(channels - float(serving_channel)) <= 4) & (channels != float(serving_channel))
        same_rssi = rssi[same_mask]
        per_repeat.append({
            "point_id": point_id,
            "repeat_index": repeat,
            "serving_channel": float(serving_channel),
            "same_channel_ap_count": int(same_mask.sum()),
            "same_channel_max_rssi": float(np.max(same_rssi)) if same_rssi.size else float("nan"),
            "adjacent_channel_ap_count": int(adjacent_mask.sum()),
            "scan_ap_count": int(np.isfinite(rssi).sum()),
            "scan_max_rssi": float(np.nanmax(rssi)) if np.isfinite(rssi).any() else float("nan"),
        })
        for channel, value in zip(channels, rssi):
            if np.isfinite(channel) and np.isfinite(value):
                channel_counts.setdefault(int(channel), []).append(float(value))
    repeat_frame = pd.DataFrame(per_repeat)
    if len(repeat_frame):
        grouped = repeat_frame.groupby("point_id").agg(
            same_channel_ap_mean=("same_channel_ap_count", "mean"),
            same_channel_ap_max=("same_channel_ap_count", "max"),
            same_channel_rssi_mean=("same_channel_max_rssi", "mean"),
            adjacent_channel_ap_mean=("adjacent_channel_ap_count", "mean"),
            scan_ap_mean=("scan_ap_count", "mean"),
            scan_max_rssi_mean=("scan_max_rssi", "mean"),
        ).reset_index()
    else:
        grouped = pd.DataFrame(columns=["point_id", "same_channel_ap_mean",
                                        "same_channel_ap_max", "same_channel_rssi_mean",
                                        "adjacent_channel_ap_mean", "scan_ap_mean",
                                        "scan_max_rssi_mean"])
    interference = grouped.merge(
        point_metrics[["point_id", "wall_material", "latency_mean_ms", "latency_p95_ms",
                       "loss_mean_pct", "rssi_mean_dbm"]], on="point_id", how="left")
    channel_rows = []
    for channel, values in sorted(channel_counts.items()):
        channel_rows.append({
            "channel": channel,
            "ap_count": len(values),
            "mean_rssi_dbm": float(np.mean(values)),
            "max_rssi_dbm": float(np.max(values)),
        })
    occupancy = pd.DataFrame(channel_rows)
    return interference, occupancy


# ---------------------------------------------------------------- 多径与相关性

def build_multipath(point_loss: pd.DataFrame, interference: pd.DataFrame) -> pd.DataFrame:
    merged = point_loss.merge(interference, on="point_id", how="left", suffixes=("", "_int"))
    type_rank = {"corridor": 0.0, "outdoor": 0.5, "classroom": 1.0, "lab": 2.0,
                 "stairwell": 2.5, "corner": 3.0, "dorm": 3.5, "elevator": 4.0}
    merged["obstruction_rank"] = merged["point_type"].map(
        lambda value: type_rank.get(str(value), 1.5)).astype(float)
    pairs = {
        ("RSSI 标准差", "附加衰减"): (merged["rssi_std_db"], merged["excess_attenuation_db"]),
        ("RSSI 标准差", "延迟抖动"): (merged["rssi_std_db"], merged["jitter_mean_ms"]),
        ("RSSI 标准差", "遮挡复杂度编码"): (merged["rssi_std_db"], merged["obstruction_rank"]),
        ("延迟抖动", "附加衰减"): (merged["jitter_mean_ms"], merged["excess_attenuation_db"]),
        ("延迟 P95", "附加衰减"): (merged["latency_p95_ms"], merged["excess_attenuation_db"]),
        ("RSSI 均值", "延迟均值"): (merged["rssi_mean_dbm"], merged["latency_mean_ms"]),
        ("RSSI 均值", "丢包率"): (merged["rssi_mean_dbm"], merged["loss_mean_pct"]),
        ("同信道 AP 数", "延迟均值"): (merged.get("same_channel_ap_mean"), merged["latency_mean_ms"]),
        ("最强同信道 RSSI", "延迟均值"): (merged.get("same_channel_rssi_mean"), merged["latency_mean_ms"]),
    }
    rows = []
    for (name_a, name_b), (values_a, values_b) in pairs.items():
        if values_a is None:
            continue
        rows.append({
            "指标A": name_a,
            "指标B": name_b,
            "Pearson r": stats_utils.pearson(values_a, values_b),
            "Spearman rho": stats_utils.spearman(values_a, values_b),
            "样本数": int(len(merged)),
            "解读": _correlation_reading(stats_utils.pearson(values_a, values_b)),
        })
    return pd.DataFrame(rows)


def _correlation_reading(value: float) -> str:
    if not np.isfinite(value):
        return "样本不足或方差为零，无法计算"
    magnitude = abs(value)
    if magnitude >= 0.8:
        strength = "强"
    elif magnitude >= 0.5:
        strength = "中等"
    elif magnitude >= 0.3:
        strength = "弱"
    else:
        strength = "几乎无"
    direction = "正" if value > 0 else "负"
    return f"{strength}{direction}相关（r={value:.2f}）"


# ---------------------------------------------------------------- 盲区分级

def grade_point(row: pd.Series) -> dict:
    rssi = float(row["rssi_mean_dbm"])
    loss = float(row["loss_mean_pct"])
    latency = float(row["latency_p95_ms"])
    material = str(row["wall_material"])
    if rssi >= -60.0 and loss < 1.0 and latency < 30.0:
        grade = "强"
    elif rssi >= -70.0 and loss < 3.0 and latency < 80.0:
        grade = "中"
    elif rssi >= -80.0 and loss < 6.0:
        grade = "弱"
    else:
        grade = "盲区"
    if rssi < -80.0:
        main_issue = "RSSI 低于 -80 dBm（覆盖不足）"
    elif loss >= 6.0:
        main_issue = "丢包率过高（重传/干扰）"
    elif latency >= 80.0:
        main_issue = "延迟 P95 过高（多径/拥塞）"
    elif material in ("metal", "concrete") and rssi < -70.0:
        main_issue = "墙体/金属遮挡导致穿透损耗大"
    else:
        main_issue = "指标基本可用"
    if grade == "强":
        priority = "P3"
        suggestion = "无需处理；可作为其他点位的参考基准"
    elif grade == "中":
        priority = "P2"
        suggestion = "保持观察；若该区域有高码率业务，优先考虑启用 5G 频段"
    elif grade == "弱":
        priority = "P1"
        suggestion = "增加 AP 补点或调整现有 AP 天线方向；该点位优先切 5G"
    else:
        priority = "P0"
        suggestion = "立即补点（走廊/门口附近新增 AP 或室分天线）并做信道规划"
    return {"blind_zone_grade": grade, "main_issue": main_issue,
            "suggestion": suggestion, "priority": priority}


def build_blind_zone(point_metrics: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for _, row in point_metrics.iterrows():
        graded = grade_point(row)
        rows.append({
            "point_id": row["point_id"],
            "point_name": row["point_name"],
            "wall_material": row["wall_material"],
            "rssi_mean_dbm": row["rssi_mean_dbm"],
            "rssi_std_db": row["rssi_std_db"],
            "latency_mean_ms": row["latency_mean_ms"],
            "latency_p95_ms": row["latency_p95_ms"],
            "loss_mean_pct": row["loss_mean_pct"],
            "stability_pct": row["stability_pct"],
            **graded,
        })
    order = {grade: index for index, grade in enumerate(GRADE_ORDER)}
    frame = pd.DataFrame(rows)
    frame["_order"] = frame["blind_zone_grade"].map(order)
    return frame.sort_values(["_order", "rssi_mean_dbm"]).drop(columns="_order").reset_index(drop=True)


# ---------------------------------------------------------------- 图表

def make_figures(data: dict, point_metrics: pd.DataFrame, point_loss: pd.DataFrame,
                 material: pd.DataFrame, blind: pd.DataFrame, interference: pd.DataFrame,
                 occupancy: pd.DataFrame, figure_dir: Path) -> list[str]:
    io_utils.ensure_dir(figure_dir)
    names: list[str] = []
    grades = dict(zip(blind["point_id"], blind["blind_zone_grade"]))
    point_ids = list(point_metrics["point_id"])

    # 1) RSSI 柱状图（均值 + 标准差，按盲区分级着色）
    fig, ax = plt.subplots(figsize=(9.0, 4.2))
    means = point_metrics["rssi_mean_dbm"].to_numpy(dtype=float)
    stds = point_metrics["rssi_std_db"].to_numpy(dtype=float)
    colors = [GRADE_COLORS.get(grades.get(point_id, "中"), "#7F8C8D") for point_id in point_ids]
    bars = ax.bar(point_ids, means, yerr=stds, capsize=4, color=colors, width=0.62)
    for bar, value, grade in zip(bars, means, [grades.get(p, "") for p in point_ids]):
        ax.annotate(f"{value:.0f} dBm ({grade})", (bar.get_x() + bar.get_width() / 2, value - 3),
                    ha="center", va="top", fontsize=8, color="white")
    for threshold, label in [(-60.0, "-60 强/中"), (-70.0, "-70 中/弱"), (-80.0, "-80 弱/盲区")]:
        ax.axhline(threshold, color="#555555", linestyle="--", linewidth=0.9)
        ax.annotate(label, xy=(0.01, threshold), xycoords=("axes fraction", "data"),
                    fontsize=8, color="#555555", va="bottom")
    ax.set_ylabel("RSSI / dBm")
    ax.set_title("8 点位 RSSI 均值与重复测量标准差（颜色 = 盲区分级）")
    ax.set_ylim(min(-95.0, float(np.nanmin(means)) - 8.0), -30.0)
    names.append(Path(plot_utils.save(fig, figure_dir / "rssi_bar.png")).name)

    # 2) 延迟分布（每点位 3 次重复的箱线图）
    survey = data["survey"].copy()
    survey["point_id"] = survey["point_id"].astype(str)
    series = [survey.loc[survey["point_id"] == point_id, "latency_avg_ms"].dropna().to_numpy(dtype=float)
              for point_id in point_ids]
    fig, ax = plt.subplots(figsize=(9.0, 4.2))
    ax.boxplot(series, positions=range(len(point_ids)), widths=0.5, patch_artist=True,
               boxprops={"facecolor": "#DCE6FF", "color": "#2E5BFF"},
               medianprops={"color": "#E74C3C"}, whiskerprops={"color": "#2E5BFF"},
               capprops={"color": "#2E5BFF"})
    ax.set_xticks(range(len(point_ids)))
    ax.set_xticklabels([f"{pid}\n{name[:6]}" for pid, name in
                        zip(point_ids, point_metrics["point_name"])], fontsize=8)
    ax.set_yscale("log")
    ax.set_ylabel("平均 RTT / ms（对数轴）")
    ax.set_title("各点位延迟分布（3 次重复）")
    for index, (pid, loss) in enumerate(zip(point_ids,
                                           point_metrics["loss_mean_pct"].to_numpy(dtype=float))):
        ax.annotate(f"丢包 {loss:.1f}%", (index, 3.0), ha="center", fontsize=7, color="#555555")
    names.append(Path(plot_utils.save(fig, figure_dir / "latency_dist.png")).name)

    # 3) 材质附加衰减箱线图
    material_order = [m for m in material["wall_material"].tolist()]
    groups = []
    labels = []
    for material_name in material_order:
        values = point_loss.loc[point_loss["wall_material"] == material_name,
                                "excess_attenuation_db"].to_numpy(dtype=float)
        values = values[np.isfinite(values)]
        if values.size:
            groups.append(values)
            labels.append(f"{MATERIAL_LABELS.get(material_name, material_name)}\n(n={values.size})")
    fig, ax = plt.subplots(figsize=(8.6, 4.2))
    if groups:
        ax.boxplot(groups, positions=range(len(groups)), widths=0.5, patch_artist=True,
                   boxprops={"facecolor": "#FFF0E6", "color": "#FF6B35"},
                   medianprops={"color": "#2E5BFF"})
        ax.set_xticks(range(len(groups)))
        ax.set_xticklabels(labels, fontsize=8)
    for index, material_name in enumerate(material_order):
        reference = MATERIAL_REFERENCE_DB.get(material_name)
        if reference is None:
            continue
        ax.scatter([index], [reference], marker="_", s=420, color="#E74C3C", zorder=5,
                   label="经验参考值" if index == 0 else None)
    ax.set_ylabel("等效附加衰减 / dB")
    ax.set_title("按墙体材质分组的等效附加衰减（红横线 = 经验参考值）")
    if groups:
        ax.legend(loc="upper left")
    names.append(Path(plot_utils.save(fig, figure_dir / "attenuation_material_box.png")).name)

    # 4) 路径损耗拟合
    fig, ax = plt.subplots(figsize=(8.0, 4.4))
    for material_name, group in point_loss.groupby("wall_material"):
        ax.scatter(group["distance_m"], group["rssi_mean_dbm"], s=46,
                   label=MATERIAL_LABELS.get(str(material_name), str(material_name)))
    for _, row in point_loss.iterrows():
        ax.annotate(str(row["point_id"]), (row["distance_m"], row["rssi_mean_dbm"]),
                    xytext=(5, 4), textcoords="offset points", fontsize=8)
    distances = np.linspace(max(1.0, float(point_loss["distance_m"].min())),
                            float(point_loss["distance_m"].max()) * 1.1, 100)
    p0 = float(point_loss["rssi_pred_free_space_dbm"].iloc[0])
    # 用拟合出的 n 重画自由空间曲线：由 p0 与 d0 反推
    none_rows = point_loss[point_loss["wall_material"] == "none"]
    if len(none_rows):
        d0 = float(none_rows["distance_m"].min())
        p0 = float(none_rows.loc[none_rows["distance_m"] == d0, "rssi_mean_dbm"].iloc[0])
        n = _infer_n(point_loss, d0, p0)
        ax.plot(distances, p0 - 10.0 * n * np.log10(distances / d0), color="#555555",
                linewidth=1.2, linestyle="--", label=f"自由空间模型 n={n:.2f}")
    ax.set_xscale("log")
    ax.set_xlabel("到 AP 距离 / m（对数轴）")
    ax.set_ylabel("RSSI / dBm")
    ax.set_title("RSSI-距离关系与对数距离路径损耗模型")
    ax.legend(fontsize=8)
    names.append(Path(plot_utils.save(fig, figure_dir / "pathloss_fit.png")).name)

    # 5) 盲区地图（横向条形 + 分级颜色）
    fig, ax = plt.subplots(figsize=(9.0, 4.2))
    ordered = blind.sort_values("rssi_mean_dbm")
    colors = [GRADE_COLORS.get(grade, "#7F8C8D") for grade in ordered["blind_zone_grade"]]
    bars = ax.barh(ordered["point_id"], ordered["rssi_mean_dbm"], color=colors, height=0.6)
    for bar, value, grade in zip(bars, ordered["rssi_mean_dbm"], ordered["blind_zone_grade"]):
        ax.annotate(f"{value:.0f} dBm · {grade}",
                    (bar.get_width(), bar.get_y() + bar.get_height() / 2),
                    xytext=(4, 0), textcoords="offset points", va="center", fontsize=8)
    for threshold in (-60.0, -70.0, -80.0):
        ax.axvline(threshold, color="#555555", linestyle="--", linewidth=0.9)
    ax.set_xlabel("RSSI 均值 / dBm")
    ax.set_title("盲区分级地图（强 / 中 / 弱 / 盲区）")
    names.append(Path(plot_utils.save(fig, figure_dir / "blind_zone_map.png")).name)

    # 6) 邻道干扰
    fig, axes = plt.subplots(1, 2, figsize=(9.6, 4.0))
    if len(interference):
        axes[0].bar(interference["point_id"], interference["same_channel_ap_mean"],
                    color="#8E44AD", width=0.6)
        axes[0].set_ylabel("同信道 AP 数均值")
        axes[0].set_title("各点位同信道 AP 数（干扰源）")
        scatter = axes[1].scatter(interference["same_channel_ap_mean"],
                                  interference["latency_mean_ms"],
                                  s=interference["loss_mean_pct"] * 8 + 24,
                                  c=interference["rssi_mean_dbm"], cmap="viridis_r")
        for _, row in interference.iterrows():
            axes[1].annotate(str(row["point_id"]),
                             (row["same_channel_ap_mean"], row["latency_mean_ms"]),
                             xytext=(4, 4), textcoords="offset points", fontsize=8)
        axes[1].set_xlabel("同信道 AP 数均值")
        axes[1].set_ylabel("延迟均值 / ms")
        axes[1].set_title("同信道干扰与延迟（点大小 = 丢包率）")
        fig.colorbar(scatter, ax=axes[1], label="RSSI / dBm")
    fig.tight_layout()
    names.append(Path(plot_utils.save(fig, figure_dir / "interference.png")).name)
    return names


def _infer_n(point_loss: pd.DataFrame, d0: float, p0: float) -> float:
    none_rows = point_loss[(point_loss["wall_material"] == "none") & (point_loss["distance_m"] > 0)]
    if len(none_rows) < 2:
        return 2.0
    x = -10.0 * np.log10(none_rows["distance_m"].to_numpy(dtype=float) / d0)
    y = none_rows["rssi_mean_dbm"].to_numpy(dtype=float) - p0
    denominator = float(np.sum(x * x))
    if denominator < 1e-12:
        return 2.0
    value = float(np.sum(x * y) / denominator)
    return value if value > 0.5 else 2.0


# ---------------------------------------------------------------- 报告

def build_improvement_suggestions(blind: pd.DataFrame, material: pd.DataFrame,
                                  interference: pd.DataFrame, fit: dict,
                                  point_loss: pd.DataFrame) -> list[str]:
    lines: list[str] = []
    weak = blind[blind["blind_zone_grade"].isin(["弱", "盲区"])]
    if len(weak):
        for _, row in weak.iterrows():
            lines.append(
                f"- **{row['point_id']} {row['point_name']}**（{MATERIAL_LABELS.get(row['wall_material'], row['wall_material'])}，"
                f"RSSI {row['rssi_mean_dbm']:.0f} dBm，P95 {row['latency_p95_ms']:.0f} ms，丢包 {row['loss_mean_pct']:.1f}%）："
                f"{row['main_issue']}；建议：{row['suggestion']}。")
    else:
        lines.append("- 本批 8 个点位均未落入弱/盲区，补点需求低；仍建议定期复测。")
    concrete = material[material["wall_material"] == "concrete"]
    metal = material[material["wall_material"] == "metal"]
    glass = material[material["wall_material"] == "glass"]
    if len(concrete) and np.isfinite(concrete["excess_mean_db"].iloc[0]):
        lines.append(
            f"- 混凝土：实测等效附加衰减 {concrete['excess_mean_db'].iloc[0]:.1f} dB"
            f"（经验参考 {MATERIAL_REFERENCE_DB['concrete']:.0f} dB）；穿墙场景应优先把 AP 放到走廊侧。")
    if len(metal) and np.isfinite(metal["excess_mean_db"].iloc[0]):
        lines.append(
            f"- 金属：实测 {metal['excess_mean_db'].iloc[0]:.1f} dB（经验 {MATERIAL_REFERENCE_DB['metal']:.0f} dB）；"
            "电梯口/金属门内基本无法穿透，建议用走廊侧 AP + 泄漏电缆/室分覆盖门口区域。")
    if len(glass) and np.isfinite(glass["excess_mean_db"].iloc[0]):
        lines.append(
            f"- 玻璃：实测 {glass['excess_mean_db'].iloc[0]:.1f} dB（经验 {MATERIAL_REFERENCE_DB['glass']:.0f} dB）；"
            "玻璃幕墙对 2.4G 损耗小，但窗外反射会带来多径抖动，靠近窗边建议锁 5G 频段。")
    if len(interference):
        mean_same = float(np.nanmean(interference["same_channel_ap_mean"]))
        max_same = int(np.nanmax(interference["same_channel_ap_max"])) if "same_channel_ap_max" in interference else 0
        strongest = float(np.nanmean(interference["same_channel_rssi_mean"])) if "same_channel_rssi_mean" in interference else float("nan")
        lines.append(
            f"- 信道规划：各点位同信道 AP 数均值 {mean_same:.1f}（最多 {max_same} 个），"
            f"最强同信道 RSSI 均值 {strongest:.0f} dBm；2.4G 应严格使用 1/6/11 三信道并降低发射功率，"
            "高密度区域（实验室/宿舍）优先 5G 频段与 80 MHz 以下带宽以减少重叠。")
    lines.append(
        f"- 路径损耗模型：n = {fit['n']:.2f}（{fit['n_source']}），"
        f"自由空间拟合 R² = {fit['r2_free_space']:.3f}"
        if np.isfinite(fit["r2_free_space"]) else
        f"- 路径损耗模型：n = {fit['n']:.2f}（{fit['n_source']}），无遮挡点位不足以评估 R²。")
    lines.append(
        "- 补点原则：优先在“转角后/金属门内/楼梯间”等非视距位置补点；"
        "新增 AP 与最弱点位距离控制在覆盖半径内，并用 5G 回传减少 2.4G 同频干扰。")
    return lines


def build_report(data: dict, quality: pd.DataFrame, issues: list[str], point_metrics: pd.DataFrame,
                 point_loss: pd.DataFrame, fit: dict, material: pd.DataFrame,
                 multipath: pd.DataFrame, interference: pd.DataFrame, occupancy: pd.DataFrame,
                 blind: pd.DataFrame, figure_names: list[str], synthetic: bool) -> str:
    survey = data["survey"]
    session = str(survey["session_id"].iloc[0]) if len(survey) else "未知"
    lines: list[str] = []
    lines.append("# 课题03 · 教学楼/宿舍无线信号盲区测绘 —— 分析报告")
    lines.append("")
    lines.append("> 由 analysis/wifi/analyze.py 自动生成；方法口径与局限见文末。")
    if synthetic:
        lines.append(">")
        lines.append("> **本报告使用的是 data/demo 合成演示数据（SYNTHETIC），仅用于验证分析管线，"
                     "不能作为课程报告的真实测量结论。** 换成 data/raw 下的真机数据后重跑即可。")
    lines.append("")
    lines.append("## 摘要")
    lines.append("")
    counts = blind["blind_zone_grade"].value_counts().to_dict()
    lines.append(
        f"本次测绘共 {len(point_metrics)} 个点位 × 3 次重复，得到 {len(survey)} 行有效记录。"
        f"盲区分级：强 {counts.get('强', 0)} 个、中 {counts.get('中', 0)} 个、"
        f"弱 {counts.get('弱', 0)} 个、盲区 {counts.get('盲区', 0)} 个。"
        f"以 {fit['reference_point']}（{fit['d0_m']:.1f} m，{fit['p0_dbm']:.1f} dBm）为参考，"
        f"拟合路径损耗指数 n = {fit['n']:.2f}；混凝土等效附加衰减 "
        f"{_material_value(material, 'concrete'):.1f} dB、金属 {_material_value(material, 'metal'):.1f} dB、"
        f"玻璃 {_material_value(material, 'glass'):.1f} dB，与经验值同量级。"
        "最差点位见第 5 节分级表与改善建议。")
    lines.append("")
    lines.append(f"**会话**：{session}；**样本目录**：{data['dir'].as_posix()}；"
                 f"**设备**：{data['manifest'].get('device', {}).get('model', '未提供 manifest')}。")
    lines.append("")
    lines.append("## 1. 点位设计与测量方法")
    lines.append("")
    lines.append(md_utils.md_table(
        ["点位", "名称", "类型", "墙体材质", "遮挡描述", "到 AP 距离 m", "楼层", "现场照片"],
        [[row["point_id"], row["point_name"], row["point_type"],
          MATERIAL_LABELS.get(row["wall_material"], row["wall_material"]),
          row["obstruction"], md_utils.fmt_number(row["distance_m"], 1),
          md_utils.fmt_number(row["floor"], 0) if "floor" in point_metrics else "—",
          "photos/" if row.get("photo_file") else "（占位）"]
         for _, row in point_metrics.iterrows()]))
    lines.append("")
    lines.append("- 测量口径：每点位 3 次重复、间隔 ≥30 s；每次 20 次 TCP RTT（223.5.5.5:443，超时 1000 ms）、"
                 "5 次 HTTP GET（TTFB/总耗时）、5 次网关 TCP 建连；App 保持前台、屏幕常亮。")
    lines.append("- 同一点位 3 次重复的位置、朝向保持一致（地面标记），同一时段完成 8 个点位以减少网络负载漂移。")
    lines.append("- 定位开关必须打开（Android 12+ 读取 RSSI/扫描结果的前提）；扫描受限时记录 note。")
    lines.append("- 照片占位：真机实验请按 SOP 拍摄墙体与 AP 方向照片，命名 photos/<pointId>_<stamp>.jpg。")
    lines.append("")
    lines.append("## 2. 数据完整性与质量")
    lines.append("")
    lines.append(md_utils.md_table(
        ["点位", "重复次数", "缺失重复", "scan 明细", "非法 RSSI 行", "延迟缺失", "丢包缺失"],
        [[row["point_id"], row["repeats"], row["missing_repeats"] or "无",
          row["scan_files"], row["invalid_rssi"], row["latency_missing"], row["loss_missing"]]
         for _, row in quality.iterrows()]))
    lines.append("")
    if issues:
        for issue in issues:
            lines.append(f"- ⚠️ {issue}")
    else:
        lines.append("- ✅ 24 行齐全（8 点位 × 3 次），每点位均有 scan 明细，RSSI 均在合理区间。")
    lines.append("")
    lines.append("## 3. 点位测量结果")
    lines.append("")
    lines.append(md_utils.md_table(
        ["点位", "RSSI 均值 dBm", "RSSI 标准差 dB", "延迟均值 ms", "延迟 P95 ms", "抖动 ms",
         "丢包 %", "稳定性 %"],
        [[row["point_id"], md_utils.fmt_number(row["rssi_mean_dbm"], 1),
          md_utils.fmt_number(row["rssi_std_db"], 2),
          md_utils.fmt_number(row["latency_mean_ms"], 1),
          md_utils.fmt_number(row["latency_p95_ms"], 1),
          md_utils.fmt_number(row["jitter_mean_ms"], 1),
          md_utils.fmt_number(row["loss_mean_pct"], 2),
          md_utils.fmt_number(row["stability_pct"], 1)] for _, row in point_metrics.iterrows()]))
    lines.append("")
    lines.append("稳定性评分 = 100 × (1 - min(1, RSSI 标准差 / |RSSI 均值|))，用于衡量同一位置重复测量的可复现性。")
    lines.append("")
    for name, caption in [("rssi_bar.png", "8 点位 RSSI 均值与标准差"),
                          ("latency_dist.png", "各点位延迟分布")]:
        lines.append(md_utils.md_figure(f"figures/{name}", caption))
        lines.append("")
    lines.append("## 4. 墙体材质与多径衰减分析")
    lines.append("")
    lines.append(f"自由空间参考：{fit['reference_point']} 距离 {fit['d0_m']:.1f} m、RSSI {fit['p0_dbm']:.1f} dBm；"
                 f"路径损耗指数 n = {fit['n']:.2f}（{fit['n_source']}）。"
                 + (f"无遮挡点位拟合 R² = {fit['r2_free_space']:.3f}；" if np.isfinite(fit["r2_free_space"]) else "")
                 + "每个点位的等效附加衰减 = 自由空间预测 - 实测。")
    lines.append("")
    lines.append(md_utils.md_table(
        ["材质", "点位数", "点位", "均值 dB", "中位数 dB", "标准差 dB", "经验参考 dB", "与参考差 dB"],
        [[row["material_label"], row["points"], row["point_ids"],
          md_utils.fmt_number(row["excess_mean_db"], 1),
          md_utils.fmt_number(row["excess_median_db"], 1),
          md_utils.fmt_number(row["excess_std_db"], 1),
          md_utils.fmt_number(row["reference_db"], 0),
          md_utils.fmt_number(row["diff_from_reference_db"], 1)] for _, row in material.iterrows()]))
    lines.append("")
    lines.append("- 结论框架：混凝土约 -18 dB、金属约 -25 dB、玻璃约 -6 dB、混合材质约 -14 dB；"
                 "实测均值与经验值的偏差反映墙体厚度、含水率、入射角度与多径叠加，"
                 "写入报告时应结合实际点位描述解释偏差。")
    lines.append("")
    for name, caption in [("pathloss_fit.png", "RSSI-距离关系与路径损耗拟合"),
                          ("attenuation_material_box.png", "按材质分组的等效附加衰减")]:
        lines.append(md_utils.md_figure(f"figures/{name}", caption))
        lines.append("")
    lines.append("### 4.2 多径与相关性")
    lines.append("")
    lines.append(md_utils.md_table(
        ["指标 A", "指标 B", "Pearson r", "Spearman rho", "解读"],
        [[row["指标A"], row["指标B"], md_utils.fmt_number(row["Pearson r"], 2),
          md_utils.fmt_number(row["Spearman rho"], 2), row["解读"]]
         for _, row in multipath.iterrows()]))
    lines.append("")
    lines.append("多径判读：RSSI 标准差与延迟抖动越大，说明非视距/强反射越明显；"
                 "金属与转角点位的抖动通常显著高于视距点位，符合多径衰落规律。")
    lines.append("")
    lines.append("## 5. 邻道干扰与盲区分级")
    lines.append("")
    lines.append(md_utils.md_table(
        ["点位", "同信道 AP 数均值", "最多", "最强同信道 RSSI dBm", "邻信道 AP 数均值", "延迟均值 ms", "丢包 %"],
        [[row["point_id"], md_utils.fmt_number(row.get("same_channel_ap_mean"), 1),
          md_utils.fmt_number(row.get("same_channel_ap_max"), 0),
          md_utils.fmt_number(row.get("same_channel_rssi_mean"), 1),
          md_utils.fmt_number(row.get("adjacent_channel_ap_mean"), 1),
          md_utils.fmt_number(row.get("latency_mean_ms"), 1),
          md_utils.fmt_number(row.get("loss_mean_pct"), 2)] for _, row in interference.iterrows()]))
    lines.append("")
    lines.append(md_utils.md_table(
        ["点位", "材质", "RSSI dBm", "延迟 P95 ms", "丢包 %", "分级", "主要问题", "建议", "优先级"],
        [[row["point_id"], MATERIAL_LABELS.get(row["wall_material"], row["wall_material"]),
          md_utils.fmt_number(row["rssi_mean_dbm"], 1),
          md_utils.fmt_number(row["latency_p95_ms"], 1),
          md_utils.fmt_number(row["loss_mean_pct"], 2), row["blind_zone_grade"],
          row["main_issue"], row["suggestion"], row["priority"]]
         for _, row in blind.iterrows()]))
    lines.append("")
    lines.append(md_utils.md_figure("figures/blind_zone_map.png", "盲区分级地图"))
    lines.append("")
    lines.append(md_utils.md_figure("figures/interference.png", "同信道干扰与延迟"))
    lines.append("")
    lines.append("## 6. 盲区改善建议")
    lines.append("")
    lines.extend(build_improvement_suggestions(blind, material, interference, fit, point_loss))
    lines.append("")
    lines.append("## 7. 局限与改进")
    lines.append("")
    lines.append("1. **样本量**：每点 3 次重复只能给出粗粒度标准差；要区分多径波动与测量噪声，建议每点 ≥10 次。")
    lines.append("2. **单 AP 关联**：RSSI 只反映关联 AP，不反映邻区整体覆盖；盲区判定应结合扫描明细与 AP 布点图。")
    lines.append("3. **距离估计**：distance_m 是估算值（平面图量取），误差直接进入路径损耗模型；"
                 "若能拿到 AP 精确坐标，应改用射线追踪或实测标定。")
    lines.append("4. **n 的标定**：本分析只用无遮挡点位拟合 n。若整栋楼几乎没有视距点位，n 会被墙体损耗污染，"
                 "应在报告里说明或使用同楼层走廊长度做标定。")
    lines.append("5. **干扰指标**：scan 结果受 Android 扫描节流影响可能不完整；同信道 AP 数只能作为干扰代理，"
                 "不能替代频谱分析仪。")
    lines.append("6. **时间漂移**：RSSI 与网络负载随时间变化，8 个点位应尽量在同一时段完成，并记录测试时间。")
    lines.append("")
    lines.append("## 8. 复现方式")
    lines.append("")
    lines.append("~~~powershell")
    lines.append("python analysis/wifi/analyze.py --data data/raw/wifi/<session> --out analysis/out/wifi")
    lines.append("~~~")
    return "\n".join(lines) + "\n"


def _material_value(material: pd.DataFrame, name: str) -> float:
    match = material[material["wall_material"] == name]
    if not len(match):
        return float("nan")
    return float(match["excess_mean_db"].iloc[0])


# ---------------------------------------------------------------- 入口

def run(data_dir: Path, out_dir: Path, reference_point: str | None = None,
        fixed_n: float | None = None) -> dict:
    data = load_wifi_dir(data_dir)
    synthetic = is_synthetic(data)
    quality, issues = validate_survey(data)
    point_metrics = build_point_metrics(data)
    fit, point_loss = build_pathloss(point_metrics, reference_point, fixed_n)
    material = build_material_attenuation(point_loss)
    interference, occupancy = build_interference(data, point_metrics)
    multipath = build_multipath(point_loss, interference)
    blind = build_blind_zone(point_metrics)
    figure_dir = io_utils.ensure_dir(out_dir / "figures")
    table_dir = io_utils.ensure_dir(out_dir / "tables")
    figure_names = make_figures(data, point_metrics, point_loss, material, blind,
                                interference, occupancy, figure_dir)
    io_utils.write_csv(quality, table_dir / "survey_quality.csv")
    io_utils.write_csv(point_metrics, table_dir / "point_metrics.csv")
    io_utils.write_csv(point_loss.drop(columns=["point_name", "point_type", "obstruction"]),
                       table_dir / "pathloss_points.csv")
    io_utils.write_csv(pd.DataFrame([fit]), table_dir / "pathloss_fit.csv")
    io_utils.write_csv(material, table_dir / "material_attenuation.csv")
    io_utils.write_csv(multipath, table_dir / "multipath_correlation.csv")
    io_utils.write_csv(interference, table_dir / "interference.csv")
    io_utils.write_csv(occupancy, table_dir / "channel_occupancy.csv")
    io_utils.write_csv(blind, table_dir / "blind_zone_grades.csv")
    report = build_report(data, quality, issues, point_metrics, point_loss, fit, material,
                          multipath, interference, occupancy, blind, figure_names, synthetic)
    md_utils.write_text(out_dir / "wifi_report.md", report)
    return {
        "data_dir": str(data_dir), "out_dir": str(out_dir), "synthetic": synthetic,
        "points": int(len(point_metrics)), "rows": int(len(data["survey"])),
        "issues": issues, "figures": figure_names,
        "n": float(fit["n"]), "n_source": fit["n_source"],
        "grades": blind["blind_zone_grade"].value_counts().to_dict(),
        "material_db": {row["wall_material"]: float(row["excess_mean_db"])
                        for _, row in material.iterrows()},
        "worst_points": blind.head(2)["point_id"].tolist(),
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    data_dir = Path(args.data).resolve()
    out_dir = Path(args.out).resolve()
    print(f"[wifi] 读取 {data_dir}")
    summary = run(data_dir, out_dir, args.reference_point, args.n_pathloss)
    print(f"[wifi] 输出 {out_dir}：{len(summary['figures'])} 张图、9 张表、wifi_report.md")
    print(f"[wifi] 点位数={summary['points']}，行数={summary['rows']}，"
          f"n={summary['n']:.2f}（{summary['n_source']}）")
    print("[wifi] 盲区分级: " + ", ".join(f"{k}={v}" for k, v in summary["grades"].items()))
    print("[wifi] 材质附加衰减(dB): " + ", ".join(
        f"{k}={v:.1f}" for k, v in summary["material_db"].items()))
    if summary["synthetic"]:
        print("[wifi] 注意：输入是合成演示数据（synthetic），结论仅用于验证管线")
    if summary["issues"]:
        print(f"[wifi] 数据质量问题 {len(summary['issues'])} 条（已写入报告第 2 节）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
