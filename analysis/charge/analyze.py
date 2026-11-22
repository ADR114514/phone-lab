#!/usr/bin/env python3
"""课题07：充电宝/充电器标称容量与转换效率核验。

输入：每 10 min 一行的手工/仪表记录 CSV（模板见 data/templates/topic07/）。
输出：处理后的逐点能量表、汇总指标、4 张图、charge_report.md。

用法（仓库根目录）：
  python analysis/charge/analyze.py --csv data/raw/topic07/<session>.csv
  python analysis/charge/analyze.py --csv ... --meta data/templates/topic07/session_meta.json --out analysis/out/charge
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "analysis" / "common"))
import io_utils  # noqa: E402
import plot_utils  # noqa: E402


def load_meta(path: Path | None) -> dict:
    if path and path.is_file():
        try:
            return json.loads(path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            return {}
    return {}


def load_frame(csv_path: Path) -> pd.DataFrame:
    frame = pd.read_csv(csv_path, encoding="utf-8-sig")
    frame.columns = [c.strip() for c in frame.columns]
    required = ["usb_voltage_v", "usb_current_a", "phone_battery_pct"]
    missing = [c for c in required if c not in frame.columns]
    if missing:
        raise SystemExit("缺少必要列: " + ", ".join(missing))
    for column in ["point_index", "elapsed_min", "usb_voltage_v", "usb_current_a",
                   "usb_power_w", "phone_battery_pct", "phone_voltage_mv",
                   "phone_charge_counter_uah", "phone_battery_temp_c", "charging"]:
        if column in frame.columns:
            frame[column] = pd.to_numeric(frame[column], errors="coerce")
    if "timestamp_iso" in frame.columns:
        frame["_time"] = pd.to_datetime(frame["timestamp_iso"], errors="coerce", utc=True)
    else:
        frame["_time"] = pd.NaT
    if frame["_time"].isna().all():
        if "elapsed_min" not in frame.columns:
            raise SystemExit("timestamp_iso 与 elapsed_min 都不可用，无法确定时间轴")
        base = pd.Timestamp("2026-01-01T00:00:00Z")
        frame["_time"] = base + pd.to_timedelta(frame["elapsed_min"], unit="m")
    frame = frame.sort_values("_time").reset_index(drop=True)
    frame["elapsed_min"] = (frame["_time"] - frame["_time"].iloc[0]).dt.total_seconds() / 60.0
    if "usb_power_w" not in frame.columns or frame["usb_power_w"].isna().all():
        frame["usb_power_w"] = frame["usb_voltage_v"] * frame["usb_current_a"]
    else:
        computed = frame["usb_voltage_v"] * frame["usb_current_a"]
        frame["usb_power_w"] = frame["usb_power_w"].fillna(computed)
    return frame


def trapezoid_energy_wh(times_min: pd.Series, power_w: pd.Series) -> float:
    t = times_min.to_numpy(dtype=float)
    p = pd.to_numeric(power_w, errors="coerce").to_numpy(dtype=float)
    if len(t) < 2:
        return float("nan")
    total = 0.0
    for index in range(1, len(t)):
        dt_h = (t[index] - t[index - 1]) / 60.0
        if not np.isfinite(dt_h) or dt_h <= 0:
            continue
        p0, p1 = p[index - 1], p[index]
        if np.isfinite(p0) and np.isfinite(p1):
            total += (p0 + p1) / 2.0 * dt_h
        elif np.isfinite(p1):
            total += p1 * dt_h
    return total


def phone_energy_wh(frame: pd.DataFrame, capacity_mah: float, nominal_v: float) -> tuple[float, str]:
    if "phone_charge_counter_uah" in frame.columns:
        charge = frame["phone_charge_counter_uah"].dropna()
        if len(charge) >= 2 and charge.iloc[-1] < charge.iloc[0]:
            delta_uah = float(charge.iloc[0] - charge.iloc[-1])
            voltage_mv = float(pd.to_numeric(frame.get("phone_voltage_mv"), errors="coerce").dropna().mean()) if "phone_voltage_mv" in frame.columns and frame["phone_voltage_mv"].notna().any() else nominal_v * 1000.0
            return delta_uah * voltage_mv / 1e9, "charge_counter"
    pct = pd.to_numeric(frame["phone_battery_pct"], errors="coerce").dropna()
    if len(pct) >= 2 and pct.iloc[-1] > pct.iloc[0]:
        delta_wh = (float(pct.iloc[-1] - pct.iloc[0]) / 100.0) * (capacity_mah / 1000.0) * nominal_v
        return delta_wh, "battery_pct_x_capacity"
    return float("nan"), "unavailable"


def make_figures(frame: pd.DataFrame, out_dir: Path, summary: dict) -> list[str]:
    plot_utils.setup()
    import matplotlib.pyplot as plt

    figures = []
    figure_dir = out_dir / "figures"
    figure_dir.mkdir(parents=True, exist_ok=True)
    t = frame["elapsed_min"].to_numpy(dtype=float)

    fig, axes = plt.subplots(3, 1, figsize=(9, 8), sharex=True)
    axes[0].plot(t, frame["usb_voltage_v"], color="#4cc2ff")
    axes[0].set_ylabel("USB 电压 (V)")
    axes[1].plot(t, frame["usb_current_a"], color="#b388ff")
    axes[1].set_ylabel("USB 电流 (A)")
    axes[2].plot(t, frame["usb_power_w"], color="#ffc266")
    axes[2].set_ylabel("输出功率 (W)")
    axes[2].set_xlabel("时间 (min)")
    for ax in axes:
        ax.grid(alpha=0.25)
    fig.tight_layout()
    path = figure_dir / "voltage_current_power.png"
    fig.savefig(path, dpi=160)
    plt.close(fig)
    figures.append(path.name)

    fig, ax = plt.subplots(figsize=(9, 4.5))
    ax.plot(t, frame["cumulative_usb_wh"], label="充电宝输出能量", color="#4cc2ff")
    ax.plot(t, frame["cumulative_phone_wh"], label="手机电池获得能量", color="#5bd6a0")
    ax.set_xlabel("时间 (min)")
    ax.set_ylabel("累计能量 (Wh)")
    ax.grid(alpha=0.25)
    ax.legend()
    fig.tight_layout()
    path = figure_dir / "cumulative_energy.png"
    fig.savefig(path, dpi=160)
    plt.close(fig)
    figures.append(path.name)

    fig, ax = plt.subplots(figsize=(7, 4.5))
    labels = ["输出/标称", "手机/输出", "手机/标称"]
    values = [summary["eta_output"], summary["eta_charge"], summary["eta_total"]]
    bars = ax.bar(labels, [v * 100 for v in values], color=["#4cc2ff", "#5bd6a0", "#ffc266"])
    for bar, value in zip(bars, values):
        ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 1, f"{value*100:.1f}%", ha="center")
    ax.set_ylabel("效率 (%)")
    ax.set_ylim(0, 105)
    ax.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    path = figure_dir / "efficiency.png"
    fig.savefig(path, dpi=160)
    plt.close(fig)
    figures.append(path.name)

    fig, ax = plt.subplots(figsize=(8, 4.5))
    labels = ["标称能量", "输出到手机前", "手机实际获得"]
    values = [summary["rated_energy_wh"], summary["e_usb_wh"], summary["e_phone_wh"]]
    colors = ["#8b9aa8", "#4cc2ff", "#5bd6a0"]
    for index, (label, value, color) in enumerate(zip(labels, values, colors)):
        ax.bar(label, value, color=color)
        ax.text(index, value + 0.3, f"{value:.2f} Wh", ha="center")
    ax.set_ylabel("能量 (Wh)")
    ax.set_ylim(0, max(values) * 1.25)
    ax.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    path = figure_dir / "energy_waterfall.png"
    fig.savefig(path, dpi=160)
    plt.close(fig)
    figures.append(path.name)
    return figures


def build_report(summary: dict, figures: list[str], csv_name: str) -> str:
    lines = [
        "# 充电宝容量与转换效率核验报告",
        "",
        "- 输入数据：" + csv_name,
        "- 充电宝：" + summary["model"],
        "- 标称能量：" + f"{summary['rated_energy_wh']:.2f} Wh",
        "- 标称输出容量：" + f"{summary['rated_output_mah']:.0f} mAh @ {summary['rated_output_v']:.1f} V",
        "",
        "## 1. 实测结果",
        "",
        "| 指标 | 数值 |",
        "|---|---|",
        f"| 采样点数 / 时长 | {summary['points']} 点 / {summary['duration_h']:.2f} h |",
        f"| 平均输出功率 | {summary['avg_power_w']:.2f} W |",
        f"| 峰值输出功率 | {summary['peak_power_w']:.2f} W |",
        f"| 充电宝输出能量 E_out | {summary['e_usb_wh']:.3f} Wh |",
        f"| 手机电池获得能量 E_phone | {summary['e_phone_wh']:.3f} Wh（口径：{summary['phone_energy_method']}）|",
        f"| 输出能量 / 标称能量 | {summary['eta_output']*100:.1f}% |",
        f"| 手机获得 / 输出能量 | {summary['eta_charge']*100:.1f}% |",
        f"| 手机获得 / 标称能量 | {summary['eta_total']*100:.1f}% |",
        f"| 等效输出容量 @{summary['rated_output_v']:.1f} V | {summary['equiv_output_mah']:.0f} mAh（标称 {summary['rated_output_mah']:.0f} mAh）|",
        "",
        "## 2. 损耗分解（相对标称能量）",
        "",
        "| 环节 | 能量损失 (Wh) | 占比 |",
        "|---|---|---|",
        f"| 电芯 → 输出（升压/转换） | {summary['loss_boost_wh']:.3f} | {summary['loss_boost_pct']:.1f}% |",
        f"| 输出 → 手机电池（线损+充电损耗） | {summary['loss_charge_wh']:.3f} | {summary['loss_charge_pct']:.1f}% |",
        f"| 合计 | {summary['loss_total_wh']:.3f} | {summary['loss_total_pct']:.1f}% |",
        "",
        "铭牌反推的升压效率：" + f"{summary['nameplate_boost_eta']*100:.1f}%" +
        "（" + f"{summary['rated_output_mah']:.0f} mAh × {summary['rated_output_v']:.1f} V / {summary['rated_energy_wh']:.2f} Wh" + "）。",
        "",
        "## 3. 图",
        "",
    ]
    for name in figures:
        lines.append("![" + name + "](figures/" + name + ")")
        lines.append("")
    lines += [
        "## 4. 结论与讨论（写作提示）",
        "",
        "1. 实测输出能量与标称能量的差异，主要来自升压转换（3.7 V 电芯 → 5 V/9 V）和线缆/接口损耗；",
        "2. 手机电池获得能量小于输出能量，差额为线损 + 手机内部充电管理损耗（发热）；",
        "3. 检查曲线是否在末段出现电压跌落/电流下降（电量耗尽前的保护行为）；",
        "4. 至少重复 2 次，报告均值与极差，并写明起始电量、环境温度、测试仪型号与精度。",
        "",
    ]
    return "\n".join(lines)


def analyze(csv_path: Path, out_dir: Path, meta: dict) -> dict:
    rated_energy = float(meta.get("ratedEnergyWh", 34.49))
    rated_output_mah = float(meta.get("ratedOutputCapacityMah", 6200))
    rated_output_v = float(meta.get("ratedOutputVoltageV", 5.0))
    phone_capacity = float(meta.get("phoneBatteryCapacityMah", 5400))
    phone_nominal_v = float(meta.get("phoneBatteryNominalV", 3.85))
    model = str(meta.get("powerBankModel", "Huawei CP11QC"))

    frame = load_frame(csv_path)
    if len(frame) < 3:
        raise SystemExit("有效采样点少于 3 个，无法做能量积分")
    e_usb, method_usb = trapezoid_energy_wh(frame["elapsed_min"], frame["usb_power_w"]), "trapezoid"
    e_phone, phone_method = phone_energy_wh(frame, phone_capacity, phone_nominal_v)

    interval = [0.0]
    cumulative = [0.0]
    for index in range(1, len(frame)):
        dt_h = (frame["elapsed_min"].iloc[index] - frame["elapsed_min"].iloc[index - 1]) / 60.0
        p0 = frame["usb_power_w"].iloc[index - 1]
        p1 = frame["usb_power_w"].iloc[index]
        energy = (p0 + p1) / 2.0 * dt_h if pd.notna(p0) and pd.notna(p1) else (p1 if pd.notna(p1) else 0.0) * dt_h
        interval.append(energy)
        cumulative.append(cumulative[-1] + energy)
    frame["interval_usb_wh"] = interval
    frame["cumulative_usb_wh"] = cumulative

    pct = pd.to_numeric(frame["phone_battery_pct"], errors="coerce")
    pct_start = float(pct.dropna().iloc[0]) if pct.notna().any() else float("nan")
    pct_end = float(pct.dropna().iloc[-1]) if pct.notna().any() else float("nan")
    phone_cumulative = []
    for index in range(len(frame)):
        current = pct.iloc[index]
        if pd.isna(current) or pd.isna(pct_start):
            phone_cumulative.append(float("nan"))
        else:
            phone_cumulative.append(max(0.0, (current - pct_start) / 100.0 * (phone_capacity / 1000.0) * phone_nominal_v))
    frame["cumulative_phone_wh"] = phone_cumulative

    duration_h = float((frame["elapsed_min"].iloc[-1] - frame["elapsed_min"].iloc[0]) / 60.0)
    avg_power = float(np.nanmean(frame["usb_power_w"]))
    peak_power = float(np.nanmax(frame["usb_power_w"]))
    eta_output = e_usb / rated_energy if rated_energy > 0 else float("nan")
    eta_charge = e_phone / e_usb if e_usb > 0 and np.isfinite(e_phone) else float("nan")
    eta_total = e_phone / rated_energy if rated_energy > 0 and np.isfinite(e_phone) else float("nan")
    equiv_mah = e_usb / rated_output_v * 1000.0 if rated_output_v > 0 else float("nan")
    nameplate_boost_eta = (rated_output_mah / 1000.0 * rated_output_v) / rated_energy if rated_energy > 0 else float("nan")
    loss_boost = max(0.0, rated_energy - e_usb)
    loss_charge = max(0.0, e_usb - e_phone) if np.isfinite(e_phone) else float("nan")
    loss_total = max(0.0, rated_energy - e_phone) if np.isfinite(e_phone) else float("nan")

    summary = {
        "model": model, "rated_energy_wh": rated_energy, "rated_output_mah": rated_output_mah,
        "rated_output_v": rated_output_v, "points": len(frame), "duration_h": duration_h,
        "avg_power_w": avg_power, "peak_power_w": peak_power,
        "e_usb_wh": e_usb, "e_phone_wh": e_phone, "phone_energy_method": phone_method,
        "eta_output": eta_output, "eta_charge": eta_charge, "eta_total": eta_total,
        "equiv_output_mah": equiv_mah, "nameplate_boost_eta": nameplate_boost_eta,
        "loss_boost_wh": loss_boost, "loss_boost_pct": loss_boost / rated_energy * 100.0 if rated_energy > 0 else float("nan"),
        "loss_charge_wh": loss_charge, "loss_charge_pct": loss_charge / rated_energy * 100.0 if rated_energy > 0 and np.isfinite(loss_charge) else float("nan"),
        "loss_total_wh": loss_total, "loss_total_pct": loss_total / rated_energy * 100.0 if rated_energy > 0 and np.isfinite(loss_total) else float("nan"),
        "start_pct": pct_start, "end_pct": pct_end, "method_usb": method_usb,
    }

    out_dir.mkdir(parents=True, exist_ok=True)
    table_dir = out_dir / "tables"
    table_dir.mkdir(parents=True, exist_ok=True)
    frame.drop(columns=["_time"]).to_csv(table_dir / "charge_points.csv", index=False, encoding="utf-8-sig")
    pd.DataFrame([summary]).to_csv(table_dir / "summary.csv", index=False, encoding="utf-8-sig")

    figures = make_figures(frame, out_dir, summary)
    (out_dir / "charge_report.md").write_text(build_report(summary, figures, csv_path.name), encoding="utf-8")
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv", required=True)
    parser.add_argument("--meta", default=None)
    parser.add_argument("--out", default=str(ROOT / "analysis" / "out" / "charge"))
    args = parser.parse_args(argv)
    meta = load_meta(Path(args.meta)) if args.meta else {}
    summary = analyze(Path(args.csv).resolve(), Path(args.out).resolve(), meta)
    print("[charge] 输出到 " + args.out)
    print("[charge] E_out=" + f"{summary['e_usb_wh']:.3f} Wh, E_phone={summary['e_phone_wh']:.3f} Wh")
    print("[charge] eta_output=" + f"{summary['eta_output']*100:.1f}%", "eta_total=" + f"{summary['eta_total']*100:.1f}%")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
