#!/usr/bin/env python3
"""课题07 分析管线自检：用合成数据断言能量与效率计算。"""
from __future__ import annotations

import csv
import sys
import tempfile
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "analysis" / "charge"))

import analyze  # noqa: E402


def write_synthetic(path: Path) -> None:
    rows = []
    for minute in range(0, 121, 10):
        pct = 20 + minute / 120 * 70  # 20% -> 90%
        rows.append({
            "point_index": minute // 10,
            "elapsed_min": minute,
            "timestamp_iso": "2026-01-01T" + f"{minute // 60 + 10:02d}:{minute % 60:02d}:00+08:00",
            "usb_voltage_v": 5.0,
            "usb_current_a": 2.0,
            "usb_power_w": 10.0,
            "phone_battery_pct": round(pct, 2),
            "phone_voltage_mv": 4000,
            "phone_battery_temp_c": 33.0,
            "charging": 1,
            "note": "",
        })
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def main() -> int:
    with tempfile.TemporaryDirectory() as temp:
        csv_path = Path(temp) / "synth.csv"
        write_synthetic(csv_path)
        summary = analyze.analyze(csv_path, Path(temp) / "out", {
            "powerBankModel": "Synthetic",
            "ratedEnergyWh": 34.49,
            "ratedOutputCapacityMah": 6200,
            "ratedOutputVoltageV": 5.0,
            "phoneBatteryCapacityMah": 5400,
            "phoneBatteryNominalV": 3.85,
        })
        assert abs(summary["e_usb_wh"] - 20.0) < 1e-6, summary["e_usb_wh"]
        expected_phone = 0.70 * 5.4 * 3.85
        assert abs(summary["e_phone_wh"] - expected_phone) < 1e-3, summary["e_phone_wh"]
        assert abs(summary["eta_output"] - 20.0 / 34.49) < 1e-6
        assert abs(summary["eta_charge"] - expected_phone / 20.0) < 1e-6
        assert abs(summary["eta_total"] - expected_phone / 34.49) < 1e-6
        assert abs(summary["equiv_output_mah"] - 4000.0) < 1e-3
        assert (Path(temp) / "out" / "charge_report.md").is_file()
        print("[PASS] charge pipeline: E_out=%.3f Wh, E_phone=%.3f Wh, eta_total=%.1f%%" % (
            summary["e_usb_wh"], summary["e_phone_wh"], summary["eta_total"] * 100))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
