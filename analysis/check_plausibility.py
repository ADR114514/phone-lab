#!/usr/bin/env python3
"""数据可信度/区分度自检（防“所有数值都一样”被 AI 评分为 0）。

用法：
  python analysis/check_plausibility.py --power data/raw/power/<session>/campaign_*.csv
  python analysis/check_plausibility.py --wifi  data/raw/wifi/<session>/survey_*.csv
  python analysis/check_plausibility.py --vibro analysis/out/vibro/tables/features_all.csv
  python analysis/check_plausibility.py --demo    # 跑 data/demo 与 analysis/out 的演示数据
退出码：0 通过（可含 WARN）；1 存在 FAIL。
"""
from __future__ import annotations

import argparse
import glob
import sys
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]


class Checker:
    def __init__(self) -> None:
        self.rows: list[dict] = []

    def add(self, name: str, level: str, detail: str) -> None:
        self.rows.append({"检查项": name, "结果": level, "说明": detail})

    def report(self, title: str) -> int:
        print()
        print("=" * 78)
        print(title)
        print("=" * 78)
        df = pd.DataFrame(self.rows)
        print(df.to_string(index=False))
        fails = (df["结果"] == "FAIL").sum()
        warns = (df["结果"] == "WARN").sum()
        print("-" * 78)
        if fails:
            print(f"结论：FAIL（{fails} 项不通过，{warns} 项警告）—— 先修数据再交，不要直接写报告。")
            return 1
        if warns:
            print(f"结论：PASS（{warns} 项警告）—— 可以写报告，但报告里要说明警告项。")
            return 0
        print("结论：PASS —— 数据在“物理合理性/区分度”层面站得住。")
        return 0


def _to_num(series: pd.Series) -> pd.Series:
    return pd.to_numeric(series, errors="coerce")


def check_power(c: Checker, csv_paths: list[str]) -> None:
    frames = [pd.read_csv(p) for p in csv_paths]
    df = pd.concat(frames, ignore_index=True)
    n = len(df)
    c.add("功耗/行数", "PASS" if n >= 24 else ("WARN" if n >= 16 else "FAIL"), f"共 {n} 行（目标 24 行）")

    cond_col = "condition_id" if "condition_id" in df.columns else "condition_label"
    df[cond_col] = df[cond_col].astype(str)
    conds = sorted(df[cond_col].unique())
    c.add("功耗/工况数", "PASS" if len(conds) >= 4 else "FAIL", f"工况 {conds}")

    if "charging" in df.columns:
        ch = _to_num(df["charging"]).fillna(0)
        c.add("功耗/充电异常", "FAIL" if (ch > 0).any() else "PASS",
              f"charging=1 的行数：{int((ch > 0).sum())}（必须为 0）")

    if "power_w" in df.columns:
        pw = _to_num(df["power_w"])
        means = df.assign(_p=pw).groupby(cond_col)["_p"].mean()
        ratio = float(means.max() / means.min()) if means.min() > 0 else float("inf")
        c.add("功耗/工况区分度", "PASS" if ratio >= 2.0 else ("WARN" if ratio >= 1.5 else "FAIL"),
              f"工况均值 {means.round(3).to_dict()}，最大/最小 = {ratio:.2f}（目标 ≥2）")
        stds = df.assign(_p=pw).groupby(cond_col)["_p"].std(ddof=0)
        if (stds < 0.02).any():
            c.add("功耗/无方差嫌疑", "WARN", f"工况内功率标准差过小：{stds.round(4).to_dict()}（检查是否复制粘贴）")
        else:
            c.add("功耗/无方差嫌疑", "PASS", "各工况内均有波动（非复制粘贴）")
    else:
        c.add("功耗/功率列", "FAIL", "缺少 power_w 列")

    if "battery_temp_c" in df.columns:
        t = _to_num(df["battery_temp_c"])
        span = df.assign(_t=t).groupby(cond_col)["_t"].agg(lambda x: float(x.max() - x.min()))
        biggest = float(span.max())
        c.add("功耗/温升可测", "PASS" if biggest >= 1.0 else "WARN",
              f"各工况内温差 {span.round(2).to_dict()}（最大 {biggest:.2f} °C，目标 ≥1 °C）")
    else:
        c.add("功耗/温度列", "WARN", "缺少 battery_temp_c，无法核对温升")

    if {"battery_level_pct", "condition_id"} <= set(df.columns):
        trend_bad = 0
        for _, g in df.groupby("condition_id"):
            g = g.sort_values("elapsed_sec") if "elapsed_sec" in g.columns else g
            b = _to_num(g["battery_level_pct"]).to_numpy()
            if len(b) >= 2 and b[-1] > b[0] + 1:
                trend_bad += 1
        c.add("功耗/电量单调", "WARN" if trend_bad else "PASS",
              f"电量回升的工况数：{trend_bad}（应为 0）")


def check_wifi(c: Checker, csv_path: str) -> None:
    df = pd.read_csv(csv_path)
    n = len(df)
    c.add("WiFi/行数", "PASS" if n >= 24 else ("WARN" if n >= 8 else "FAIL"), f"共 {n} 行（目标 24 = 8 点 × 3 次）")

    point_col = "point_id" if "point_id" in df.columns else ("point" if "point" in df.columns else None)
    if point_col is None:
        c.add("WiFi/点位列", "FAIL", "找不到 point_id 列")
        return
    df[point_col] = df[point_col].astype(str)
    pts = df[point_col].unique()
    c.add("WiFi/点位数", "PASS" if len(pts) >= 6 else "FAIL", f"点位 {list(pts)[:10]}（目标 8）")

    if "rssi_dbm" in df.columns:
        rssi = _to_num(df["rssi_dbm"])
        means = df.assign(_r=rssi).groupby(point_col)["_r"].mean()
        spread = float(means.max() - means.min())
        c.add("WiFi/跨点RSSI极差", "PASS" if spread >= 15 else ("WARN" if spread >= 8 else "FAIL"),
              f"点位均值 {means.round(1).to_dict()}，极差 {spread:.1f} dB（目标 ≥15 dB）")
        within = df.assign(_r=rssi).groupby(point_col)["_r"].std(ddof=0)
        bad = within[within > 6]
        c.add("WiFi/重复性", "WARN" if len(bad) else "PASS",
              "单点 3 次重复标准差应 ≤6 dB；超标点：" + (bad.round(1).to_dict().__str__() if len(bad) else "无"))
    else:
        c.add("WiFi/RSSI列", "FAIL", "缺少 rssi_dbm 列")

    rtt_cols = [c for c in df.columns if "rtt" in c or "latency" in c or "tcp_connect" in c]
    if rtt_cols:
        col = rtt_cols[0]
        v = _to_num(df[col]).dropna()
        if len(v):
            q95 = float(v.quantile(0.95))
            c.add("WiFi/延迟可测", "PASS" if q95 > 0 else "WARN", f"{col} p95 ≈ {q95:.1f} ms")
    else:
        c.add("WiFi/延迟列", "WARN", "没有 RTT/latency 列")


def fisher_ratio(X: np.ndarray, y: np.ndarray) -> float:
    classes = np.unique(y)
    overall = X.mean(axis=0)
    between, within = 0.0, 0.0
    for cls in classes:
        Xi = X[y == cls]
        if len(Xi) < 2:
            continue
        mu = Xi.mean(axis=0)
        between += len(Xi) * float(((mu - overall) ** 2).sum())
        within += float(((Xi - mu) ** 2).sum())
    return float("inf") if within <= 0 else between / within


def knn_loocv(X: np.ndarray, y: np.ndarray, k: int = 3) -> float:
    n = len(X)
    if n < 4:
        return float("nan")
    correct = 0
    for i in range(n):
        d = np.linalg.norm(X - X[i], axis=1)
        d[i] = np.inf
        idx = np.argsort(d)[: min(k, n - 1)]
        votes = y[idx]
        vals, counts = np.unique(votes, return_counts=True)
        pred = vals[np.argmax(counts)]
        correct += int(pred == y[i])
    return correct / n


def check_vibro(c: Checker, csv_path: str) -> None:
    df = pd.read_csv(csv_path)
    n = len(df)
    c.add("声振/样本数", "PASS" if n >= 75 else ("WARN" if n >= 30 else "FAIL"), f"共 {n} 组（目标 75 = 5 × 15）")
    label = "condition_id" if "condition_id" in df.columns else "condition_label"
    if label not in df.columns:
        c.add("声振/标签列", "FAIL", "找不到 condition_id/condition_label")
        return
    y = df[label].astype(str).to_numpy()
    classes, counts = np.unique(y, return_counts=True)
    c.add("声振/类别数", "PASS" if len(classes) >= 4 else "FAIL", f"类别 {list(classes)}，每类 {counts.tolist()}")
    if (counts < 5).any():
        c.add("声振/每类样本", "WARN", "存在类别样本 <5 组，LOOCV 方差会很大")

    num_cols = [col for col in df.columns if col not in {label, "condition_label", "group_index", "file_stem", "recorded_at_ms"}]
    X = df[num_cols].apply(pd.to_numeric, errors="coerce")
    keep = [col for col in X.columns if X[col].notna().mean() > 0.9 and X[col].std(ddof=0) > 0]
    if len(keep) < 5:
        c.add("声振/特征方差", "FAIL", f"可用特征只有 {len(keep)} 个，疑似数值全同")
        return
    Z = (X[keep] - X[keep].mean()) / X[keep].std(ddof=0).replace(0, np.nan)
    Z = Z.fillna(0.0).to_numpy(dtype=float)
    fr = fisher_ratio(Z, y)
    c.add("声振/类间可分性(Fisher)", "PASS" if fr >= 2.0 else ("WARN" if fr >= 1.2 else "FAIL"),
          f"Fisher 比 = {fr:.2f}（目标 ≥2，<1.2 说明各类几乎不可分）")
    acc = knn_loocv(Z, y, k=3)
    lvl = "PASS" if acc >= 0.60 else ("WARN" if acc >= 0.45 else "FAIL")
    c.add("声振/LOOCV 准确率", lvl, f"kNN(k=3) LOOCV = {acc:.2%}（预实验判据 ≥60%；正式报告建议 ≥75%）")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--power", nargs="+", help="campaign_*.csv 路径")
    ap.add_argument("--wifi", help="survey_*.csv 路径")
    ap.add_argument("--vibro", help="features_all.csv 或合并后的特征表路径")
    ap.add_argument("--demo", action="store_true", help="用 data/demo 与 analysis/out 演示数据自检")
    args = ap.parse_args()

    c = Checker()
    if args.demo:
        power = sorted(glob.glob(str(ROOT / "data/demo/power/*/campaign_*.csv")))
        vibro = ROOT / "analysis/out/vibro/tables/features_all.csv"
        wifi = None
        for cand in glob.glob(str(ROOT / "data/demo/wifi/*/survey_*.csv")) + glob.glob(str(ROOT / "data/raw/wifi/*/survey_*.csv")):
            wifi = cand
            break
        if power:
            check_power(c, power)
        if wifi:
            check_wifi(c, wifi)
        else:
            print("[skip] 找到不到 survey_*.csv（WiFi 演示数据只有 scan 明细），WiFi 检查跳过")
        if vibro.exists():
            check_vibro(c, str(vibro))
    else:
        if args.power:
            check_power(c, args.power)
        if args.wifi:
            check_wifi(c, args.wifi)
        if args.vibro:
            check_vibro(c, args.vibro)
    if not c.rows:
        ap.error("至少给一个 --power/--wifi/--vibro 或 --demo")
    return c.report("PhoneLab 数据可信度 / 区分度自检")


if __name__ == "__main__":
    raise SystemExit(main())
