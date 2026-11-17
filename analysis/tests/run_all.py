"""端到端自检：用 data/demo 合成数据跑通三条分析管线并断言关键输出。

用法（仓库根目录）：
    python analysis/tests/run_all.py                # 默认：数据缺失时自动生成
    python analysis/tests/run_all.py --regenerate   # 强制重新生成演示数据
    python analysis/tests/run_all.py --skip-report  # 跳过总报告合并检查

检查内容：
  1) Python 版本与依赖（numpy / pandas / matplotlib，明确不需要 scipy/sklearn）；
  2) data/demo 合成数据齐全、每个 manifest 带 synthetic 标记、总体积 < 15 MB；
  3) analysis/power|wifi|vibro/analyze.py 端到端运行并产出 figures/tables/report；
  4) 对输出数值做合理性断言（行数、功率排序、材质衰减区间、LOOCV/盲测准确率、
     抗噪曲线单调性、去噪增益等）；
  5) analysis/report/build_report.py 合并三份报告为课程总报告草稿；
  6) 所有报告都明确标注 synthetic（演示数据）。

退出码：0 = 全绿；1 = 有失败项。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ANALYSIS_DIR = HERE.parent
REPO_ROOT = ANALYSIS_DIR.parent
OUT_DIR = ANALYSIS_DIR / "out"
DEMO_DIR = REPO_ROOT / "data" / "demo"
MAX_DEMO_BYTES = 15 * 1024 * 1024

CHECKS: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    CHECKS.append((name, bool(ok), detail))
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {name}" + (f"：{detail}" if detail else ""))


def expect(condition, name: str, detail: str = "") -> bool:
    record(name, bool(condition), detail)
    return bool(condition)


def run_script(script: Path, args: list[str], label: str, timeout: int = 600) -> bool:
    started = time.time()
    command = [sys.executable, str(script)] + [str(item) for item in args]
    print(f"[run ] {label}: " + " ".join(str(item) for item in command))
    # 显式用 UTF-8 解码子进程输出：Windows 默认 GBK 会让中文/箭头字符解码失败
    environment = dict(os.environ)
    environment["PYTHONIOENCODING"] = "utf-8"
    completed = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True,
                               text=True, encoding="utf-8", errors="replace",
                               env=environment, timeout=timeout)
    elapsed = time.time() - started
    tail = (completed.stdout or "").strip().splitlines()[-6:]
    for line in tail:
        print("       " + line)
    if completed.returncode != 0:
        print("       stderr（末尾）:")
        for line in (completed.stderr or "").strip().splitlines()[-12:]:
            print("       " + line)
    return expect(completed.returncode == 0, label,
                  f"耗时 {elapsed:.1f}s" if completed.returncode == 0 else
                  f"退出码 {completed.returncode}")


def png_ok(path: Path) -> bool:
    if not path.is_file() or path.stat().st_size < 2000:
        return False
    with path.open("rb") as handle:
        return handle.read(8) == b"\x89PNG\r\n\x1a\n"


def check_environment() -> None:
    expect(sys.version_info >= (3, 10), "Python 版本 >= 3.10",
           sys.version.split()[0])
    import numpy
    import pandas
    import matplotlib
    expect(True, "依赖可导入",
           f"numpy {numpy.__version__} / pandas {pandas.__version__} / matplotlib {matplotlib.__version__}")
    try:
        import scipy  # noqa: F401
        has_scipy = True
    except ImportError:
        has_scipy = False
    expect(not has_scipy, "不依赖 scipy（纯 numpy 实现）",
           "环境中未安装 scipy，管线仍可运行" if not has_scipy else "注意：环境中存在 scipy，但代码未使用")


def check_demo_data(regenerate: bool) -> bool:
    expected = [
        DEMO_DIR / "power" / "20261118_PC-20261118-001" / "campaign_PC-20261118-001.csv",
        DEMO_DIR / "wifi" / "20261118_WS-20261118-001" / "survey_WS-20261118-001.csv",
        DEMO_DIR / "vibro" / "20261118_VD-20261118-001" / "dataset_index.csv",
        DEMO_DIR / "README.md",
    ]
    missing = [path for path in expected if not path.is_file()]
    if regenerate or missing:
        script = DEMO_DIR / "generate_demo.py"
        if not expect(script.is_file(), "演示数据生成脚本存在", str(script)):
            return False
        if not run_script(script, [], "生成 data/demo 合成演示数据"):
            return False
    missing = [path for path in expected if not path.is_file()]
    if not expect(not missing, "data/demo 关键文件齐全",
                  "缺少: " + ", ".join(str(path.name) for path in missing) if missing else "4 类数据齐全"):
        return False
    sizes = [path.stat().st_size for path in DEMO_DIR.rglob("*") if path.is_file()]
    total = sum(sizes)
    expect(total < MAX_DEMO_BYTES, "data/demo 总体积 < 15 MB",
           f"{total / 1024 / 1024:.2f} MB / {len(sizes)} 个文件")
    readme = (DEMO_DIR / "README.md").read_text(encoding="utf-8").lower()
    expect("synthetic" in readme or "合成" in readme, "data/demo README 标注 synthetic")
    manifests = list(DEMO_DIR.rglob("manifest.json"))
    expect(len(manifests) >= 3, "每套演示数据都有 manifest.json", f"{len(manifests)} 个")
    marked = 0
    for manifest_path in manifests:
        try:
            payload = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        except json.JSONDecodeError:
            continue
        if payload.get("synthetic") is True:
            marked += 1
    expect(marked == len(manifests), "所有 manifest 标记 synthetic=true",
           f"{marked}/{len(manifests)}")
    return True


def check_power() -> None:
    data_dir = DEMO_DIR / "power" / "20261118_PC-20261118-001"
    out_dir = OUT_DIR / "power"
    if not run_script(ANALYSIS_DIR / "power" / "analyze.py",
                      ["--data", data_dir, "--out", out_dir], "课题02 功耗管线"):
        return
    report = out_dir / "power_report.md"
    campaign = out_dir / "tables" / "condition_metrics.csv"
    expect(report.is_file() and report.stat().st_size > 2000, "power_report.md 生成")
    expect(campaign.is_file(), "condition_metrics.csv 生成")
    figures = sorted((out_dir / "figures").glob("*.png"))
    expect(len(figures) >= 5, "功耗图数量 >= 5", f"{len(figures)} 张")
    expect(all(png_ok(path) for path in figures), "功耗图为有效 PNG")
    if not campaign.is_file():
        return
    metrics = pd.read_csv(campaign, encoding="utf-8-sig")
    expect(len(metrics) == 4, "4 个工况指标行", f"{len(metrics)} 行")
    order = metrics.set_index("condition_id")["avg_power_w"]
    expect(order.get("IDLE", 99) < order.get("VIDEO", 0) < order.get("NAVCAM", 0) < order.get("GAME", 0),
           "平均功率排序 IDLE < VIDEO < NAVCAM < GAME",
           " / ".join(f"{key}={value:.2f}W" for key, value in order.items()))
    expect(0.3 < float(order.get("IDLE", 0.0)) < 1.5, "待机平均功率物理合理",
           f"{float(order.get('IDLE', float('nan'))):.2f} W")
    expect(4.5 < float(order.get("GAME", 0.0)) < 9.0, "游戏平均功率物理合理",
           f"{float(order.get('GAME', float('nan'))):.2f} W")
    expect((metrics["level_drop_pct"] > 0).all(), "所有工况电量下降")
    expect((metrics["temp_rise_rate_c_per_min"] > 0).all(), "所有工况温升为正")
    thermal = pd.read_csv(out_dir / "tables" / "thermal_analysis.csv", encoding="utf-8-sig")
    expect(bool(thermal.loc[thermal["condition_id"] == "GAME", "throttled"].iloc[0]),
           "GAME 工况检测到热节流")
    attribution = pd.read_csv(out_dir / "tables" / "module_attribution.csv", encoding="utf-8-sig")
    expect(len(attribution) >= 5, "模块归因表包含 >=5 项")
    increments = pd.read_csv(out_dir / "tables" / "increment_decomposition.csv", encoding="utf-8-sig")
    expect(len(increments) == 4, "工况增量分解 4 行")
    report_text = report.read_text(encoding="utf-8")
    expect("活动指示量回归" in report_text, "报告声明“活动指示量回归”而非电流计")
    expect("SYNTHETIC" in report_text, "功耗报告标注 synthetic")
    expect(report_text.count("figures/") >= 5, "功耗报告引用 >=5 张图")


def check_wifi() -> None:
    data_dir = DEMO_DIR / "wifi" / "20261118_WS-20261118-001"
    out_dir = OUT_DIR / "wifi"
    if not run_script(ANALYSIS_DIR / "wifi" / "analyze.py",
                      ["--data", data_dir, "--out", out_dir], "课题03 WiFi 管线"):
        return
    report = out_dir / "wifi_report.md"
    expect(report.is_file() and report.stat().st_size > 2000, "wifi_report.md 生成")
    figures = sorted((out_dir / "figures").glob("*.png"))
    expect(len(figures) >= 5, "WiFi 图数量 >= 5", f"{len(figures)} 张")
    expect(all(png_ok(path) for path in figures), "WiFi 图为有效 PNG")
    survey_path = out_dir / "tables" / "point_metrics.csv"
    if not expect(survey_path.is_file(), "point_metrics.csv 生成"):
        return
    points = pd.read_csv(survey_path, encoding="utf-8-sig")
    expect(len(points) == 8, "8 个点位指标行", f"{len(points)} 行")
    material = pd.read_csv(out_dir / "tables" / "material_attenuation.csv", encoding="utf-8-sig")
    values = dict(zip(material["wall_material"], material["excess_mean_db"]))
    # 演示数据由已知材质衰减参数合成（混凝土 18 / 金属 25 / 玻璃 6 / 混合 14 dB），
    # 3 次重复的随机波动会让实测均值偏离 0.5~3 dB，这里按“参数 ± 容差”断言。
    expect(abs(float(values.get("glass", 99)) - 6.0) <= 1.0, "玻璃附加衰减 ≈6 dB",
           f"{float(values.get('glass', float('nan'))):.1f} dB（容差 ±1.0）")
    expect(abs(float(values.get("concrete", 0)) - 18.0) <= 3.0, "混凝土附加衰减 ≈18 dB",
           f"{float(values.get('concrete', float('nan'))):.1f} dB（容差 ±3.0）")
    expect(abs(float(values.get("metal", 0)) - 25.0) <= 4.0, "金属附加衰减 ≈25 dB",
           f"{float(values.get('metal', float('nan'))):.1f} dB（容差 ±4.0）")
    expect(abs(float(values.get("none", 99))) <= 2.0, "无墙体附加衰减 ≈0 dB",
           f"{float(values.get('none', float('nan'))):.1f} dB（容差 ±2.0）")
    fit = pd.read_csv(out_dir / "tables" / "pathloss_fit.csv", encoding="utf-8-sig")
    n_value = float(fit["n"].iloc[0])
    expect(1.5 <= n_value <= 3.0, "路径损耗指数 n 在合理区间", f"n={n_value:.2f}")
    blind = pd.read_csv(out_dir / "tables" / "blind_zone_grades.csv", encoding="utf-8-sig")
    expect(len(blind) == 8 and blind["blind_zone_grade"].isin(["强", "中", "弱", "盲区"]).all(),
           "8 个点位均有盲区分级", "分级: " + ", ".join(blind["blind_zone_grade"].tolist()))
    grades = set(blind["blind_zone_grade"])
    expect(len(grades) >= 3, "盲区分级覆盖 >=3 档", "、".join(sorted(grades)))
    multipath = pd.read_csv(out_dir / "tables" / "multipath_correlation.csv", encoding="utf-8-sig")
    expect(len(multipath) >= 5, "多径相关性表 >=5 对指标")
    interference = pd.read_csv(out_dir / "tables" / "interference.csv", encoding="utf-8-sig")
    expect(len(interference) == 8, "邻道干扰表 8 行")
    report_text = report.read_text(encoding="utf-8")
    expect("SYNTHETIC" in report_text, "WiFi 报告标注 synthetic")
    expect("补点" in report_text and "信道" in report_text, "WiFi 报告包含补点/信道建议")


def check_vibro() -> None:
    data_dir = DEMO_DIR / "vibro" / "20261118_VD-20261118-001"
    out_dir = OUT_DIR / "vibro"
    if not run_script(ANALYSIS_DIR / "vibro" / "analyze.py",
                      ["--data", data_dir, "--out", out_dir], "项目三 声振管线"):
        return
    report = out_dir / "vibro_report.md"
    expect(report.is_file() and report.stat().st_size > 3000, "vibro_report.md 生成")
    figures = sorted((out_dir / "figures").glob("*.png"))
    expect(len(figures) >= 4, "声振图数量 >= 4", f"{len(figures)} 张")
    expect(all(png_ok(path) for path in figures), "声振图为有效 PNG")
    features_path = out_dir / "tables" / "features_all.csv"
    if not expect(features_path.is_file(), "features_all.csv 生成"):
        return
    features = pd.read_csv(features_path, encoding="utf-8-sig")
    expect(len(features) == 15, "15 组训练样本", f"{len(features)} 行")
    numeric = [column for column in features.columns
               if column not in ("file_stem", "condition_id", "condition_label", "group_index",
                                 "relative_dir")]
    expect(len(numeric) == 32, "32 个数值特征", f"{len(numeric)} 列")
    expect(features["condition_id"].nunique() == 5, "5 个工况",
           "、".join(sorted(features["condition_id"].unique())))
    evaluation_path = sorted(out_dir.glob("evaluation_*.json"))
    if not expect(bool(evaluation_path), "evaluation_*.json 生成"):
        return
    evaluation = json.loads(evaluation_path[0].read_text(encoding="utf-8"))
    loocv = float(evaluation["training"]["loocv_accuracy"])
    expect(0.55 <= loocv <= 1.0, "LOOCV 准确率合理", f"{loocv:.1%}")
    expect(int(evaluation["training"]["features"]) == 32, "evaluation JSON 特征数 32")
    blind = evaluation.get("blind_test")
    if blind:
        accuracy = float(blind.get("accuracy", float("nan")))
        expect(0.4 <= accuracy <= 1.0, "盲测准确率合理", f"{accuracy:.1%}")
    noise = pd.read_csv(out_dir / "tables" / "noise_accuracy.csv", encoding="utf-8-sig")
    expect(len(noise) == 10, "抗噪表 5 SNR x 2 噪声类型", f"{len(noise)} 行")
    white = noise[noise["noise_type"] == "white"].sort_values("snr_db", ascending=False)
    expect(bool(white["accuracy_noisy"].iloc[0] >= 0.6), "20 dB 白噪声准确率 >= 0.6",
           f"{white['accuracy_noisy'].iloc[0]:.1%}")
    expect(bool(white["accuracy_noisy"].iloc[-1] <= white["accuracy_noisy"].iloc[0]),
           "抗噪准确率随 SNR 下降（20 dB -> -5 dB）")
    denoised = white["accuracy_denoised"].to_numpy(dtype=float)
    noisy = white["accuracy_noisy"].to_numpy(dtype=float)
    valid = np.isfinite(denoised)
    if valid.any():
        expect(bool(np.mean(denoised[valid]) >= np.mean(noisy[valid]) - 1e-9),
               "谱减法去噪平均不劣于加噪",
               f"去噪 {np.mean(denoised[valid]):.1%} vs 加噪 {np.mean(noisy[valid]):.1%}")
    report_text = report.read_text(encoding="utf-8")
    expect("SYNTHETIC" in report_text, "声振报告标注 synthetic")
    expect("抗噪" in report_text and "误差" in report_text, "声振报告包含抗噪与误差分析")


def check_report_builder() -> None:
    if not run_script(ANALYSIS_DIR / "report" / "build_report.py", [],
                      "合并课程总报告草稿"):
        return
    draft = OUT_DIR / "report" / "course_report_draft.md"
    if not expect(draft.is_file(), "course_report_draft.md 生成"):
        return
    text = draft.read_text(encoding="utf-8")
    expect(len(text) > 5000, "总报告草稿长度合理", f"{len(text)} 字符")
    for keyword in ("课题02", "课题03", "项目三", "复现步骤"):
        expect(keyword in text, f"总报告包含《{keyword}》章节")
    expect("SYNTHETIC" in text, "总报告标注 synthetic 数据来源")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="phone-lab analysis 端到端自检")
    parser.add_argument("--regenerate", action="store_true", help="强制重新生成 data/demo 演示数据")
    parser.add_argument("--skip-report", action="store_true", help="跳过总报告合并检查")
    args = parser.parse_args(argv)
    print("=" * 78)
    print("phone-lab analysis 端到端自检（data/demo 合成演示数据）")
    print(f"仓库根：{REPO_ROOT}")
    print(f"解释器：{sys.executable}")
    print("=" * 78)
    check_environment()
    data_ok = check_demo_data(args.regenerate)
    if data_ok:
        check_power()
        check_wifi()
        check_vibro()
        if not args.skip_report:
            check_report_builder()
    print("=" * 78)
    failed = [item for item in CHECKS if not item[1]]
    print(f"总计 {len(CHECKS)} 项检查：通过 {len(CHECKS) - len(failed)}，失败 {len(failed)}")
    if failed:
        print("失败项：")
        for name, _, detail in failed:
            print(f"  - {name}：{detail}")
        print("结果：失败（见上方 [FAIL]）")
        return 1
    print("结果：全绿 ✅（三条管线均可在 data/demo 上端到端运行）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
