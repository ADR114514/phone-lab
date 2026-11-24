"""合并三份分析报告为课程总报告草稿（Markdown）。

输入：analysis/out/{power,charge,vibro}/*_report.md 与对应分析表。
      若没有课题07正式输出，使用 analysis/out/charge-demo/ 下的模板演示输出。
输出：analysis/out/report/course_report_draft.md

说明：
  * 三份分报告是“由脚本自动生成的分析结果”，总报告草稿把它们按课程报告结构串起来，
    并预留“跨课题综合小结 / 结论 / 反思”等需要人工填写的章节；
  * 若输入报告带 SYNTHETIC 标记，总报告会显著标注“合成演示数据”，避免误当真实测量结果；
  * 纯标准库 + pandas（pandas 仅用于读取汇总表），不引入额外依赖。

用法：
    python analysis/report/build_report.py
    python analysis/report/build_report.py --root analysis/out --out analysis/out/report
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent
ANALYSIS_DIR = HERE.parent
REPO_ROOT = ANALYSIS_DIR.parent

import sys
if str(ANALYSIS_DIR) not in sys.path:
    sys.path.insert(0, str(ANALYSIS_DIR))
from common import io_utils, md_utils  # noqa: E402

REPORT_FILES = {
    "power": ("课题02 · 个人手机多工况发热与掉电实测", "power_report.md"),
    "charge": ("课题07 · 充电宝容量与转换效率核验", "charge_report.md"),
    "vibro": ("项目三 · 手机声振诊断仪", "vibro_report.md"),
}


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="合并三份分析报告为课程总报告草稿")
    parser.add_argument("--root", default=None, help="分析结果根目录，默认 analysis/out")
    parser.add_argument("--out", default=None, help="输出目录，默认 <root>/report")
    parser.add_argument("--title", default="phone-lab 课程设计总报告（草稿）",
                        help="总报告标题")
    args = parser.parse_args(argv)
    if args.root is None:
        args.root = str(REPO_ROOT / "analysis" / "out")
    if args.out is None:
        args.out = str(Path(args.root) / "report")
    return args


def shift_headings(text: str, levels: int = 1) -> str:
    """把分报告的标题整体降 1–2 级，嵌入总报告时不打乱层级。"""
    lines = []
    for line in text.splitlines():
        stripped = line.lstrip("#")
        count = len(line) - len(stripped)
        if 0 < count <= 6 and stripped.startswith(" "):
            lines.append("#" * min(6, count + levels) + stripped)
        else:
            lines.append(line)
    return "\n".join(lines)


def rebase_report_links(text: str, key: str) -> str:
    """分报告里的 figures/、tables/ 链接是相对 analysis/out/<key>/ 的。

    总报告位于 analysis/out/report/，直接拼进来会让 GitHub 上的图片链接失效，
    这里统一补上 ../<key>/ 前缀。
    """
    return (text.replace("](figures/", f"](../{key}/figures/")
                .replace("](tables/", f"](../{key}/tables/"))


def read_reports(root: Path) -> dict[str, dict]:
    reports = {}
    for key, (title, filename) in REPORT_FILES.items():
        path = root / key / filename
        if key == "charge" and not path.is_file():
            demo_path = root / "charge-demo" / filename
            if demo_path.is_file():
                path = demo_path
        if path.is_file():
            reports[key] = {"title": title, "path": path,
                            "text": path.read_text(encoding="utf-8")}
        else:
            reports[key] = {"title": title, "path": path, "text": ""}
    return reports


def has_synthetic_report(reports: dict[str, dict]) -> bool:
    return any("SYNTHETIC" in report["text"] or report["path"].parent.name == "charge-demo"
               for report in reports.values())


def load_power_metrics(root: Path):
    path = root / "power" / "tables" / "condition_metrics.csv"
    if not path.is_file():
        return None
    return io_utils.coerce_numeric(io_utils.read_csv(path),
                                   ["avg_power_w", "level_drop_pct", "drop_rate_pct_per_h",
                                    "temp_rise_c", "thermal_max"])


def load_vibro_evaluation(root: Path):
    paths = sorted((root / "vibro").glob("evaluation_*.json"))
    if not paths:
        return None
    try:
        return json.loads(paths[0].read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return None


def build_key_results(root: Path, charge_dir: Path) -> list[str]:
    """从三套输出表里自动汇总“关键结果速览”，不解析报告正文。"""
    lines: list[str] = ["## 关键结果速览", ""]
    power = load_power_metrics(root)
    if power is not None and len(power):
        lines.append("### 课题02 功耗与发热")
        lines.append("")
        lines.append(md_utils.md_table(
            ["工况", "平均功率 W", "掉电 %", "掉电率 %/h", "温升 °C", "thermal 峰值"],
            [[row["condition_id"], md_utils.fmt_number(row["avg_power_w"], 3),
              md_utils.fmt_number(row["level_drop_pct"], 1),
              md_utils.fmt_number(row["drop_rate_pct_per_h"], 1),
              md_utils.fmt_number(row["temp_rise_c"], 1),
              md_utils.fmt_number(row["thermal_max"], 0)] for _, row in power.iterrows()]))
        lines.append("")
    charge_path = charge_dir / "tables" / "summary.csv"
    if charge_path.is_file():
        charge = io_utils.read_csv(charge_path)
        if len(charge):
            label = "（模板演示）" if charge_dir.name == "charge-demo" else ""
            row = charge.iloc[0]
            lines.append(f"### 课题07 充电宝{label}")
            lines.append("")
            lines.append(f"- 采样 {md_utils.fmt_number(row['points'], 0)} 点，"
                         f"覆盖 {md_utils.fmt_number(row['duration_h'], 2)} h，"
                         f"该时段输出能量 {md_utils.fmt_number(row['e_usb_wh'], 3)} Wh。")
            lines.append("")
    evaluation = load_vibro_evaluation(root)
    if evaluation:
        training = evaluation.get("training", {})
        blind_test = evaluation.get("blind_test") or {}
        lines.append("### 项目三 声振诊断")
        lines.append("")
        lines.append(f"- 训练样本 {training.get('samples', '—')} 组、特征 {training.get('features', '—')} 维；"
                     f"LOOCV 准确率 {training.get('loocv_accuracy', float('nan')):.1%}。")
        if blind_test:
            lines.append(f"- 盲测 {blind_test.get('trials', '—')} 组，"
                         f"准确率 {blind_test.get('accuracy', float('nan')):.1%}。")
        noise = evaluation.get("noise") or []
        if noise:
            lines.append("- 抗噪（白噪声）：" + "、".join(
                f"SNR {item['snr_db']:.0f} dB → {item['white']:.1%}" for item in noise))
        lines.append("")
    return lines


def build_draft(root: Path, title: str, reports: dict[str, dict]) -> str:
    synthetic = has_synthetic_report(reports)
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    lines: list[str] = [f"# {title}", ""]
    lines.append(f"> 自动生成时间：{generated}。由 analysis/report/build_report.py 合并各项目分析报告。")
    if synthetic:
        lines.append(">")
        lines.append("> **本稿包含合成演示数据（SYNTHETIC）。提交课程报告前，请用真实实验记录重新运行对应的分析。**")
    lines.append("")
    lines.append("## 目录")
    lines.append("")
    lines.append("1. [课题02 · 个人手机多工况发热与掉电实测](#课题02)")
    lines.append("2. [课题07 · 充电宝容量与转换效率核验](#课题07)")
    lines.append("3. [项目三 · 手机声振诊断仪](#项目三)")
    lines.append("4. [跨课题综合小结（待填写）](#综合小结)")
    lines.append("5. [复现步骤](#复现步骤)")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.extend(build_key_results(root, reports["charge"]["path"].parent))
    lines.append("---")
    lines.append("")
    anchors = {"power": "课题02", "charge": "课题07", "vibro": "项目三"}
    part_numbers = {"power": "第一部分", "charge": "第二部分", "vibro": "第三部分"}
    for key, report in reports.items():
        lines.append(f'<a id="{anchors[key]}"></a>')
        lines.append("")
        lines.append(f"# {part_numbers[key]}：{report['title']}")
        lines.append("")
        if report["text"]:
            directory = report["path"].parent.name
            if directory == "charge-demo":
                lines.append("> **SYNTHETIC 模板演示。记录仅覆盖前 30 分钟，无法据此判断完整放电效率或损耗。**")
                lines.append("")
            lines.append(rebase_report_links(shift_headings(report["text"], levels=1), directory))
            lines.append("")
        else:
            lines.append(f"> 未找到 {report['path']}，请先运行对应的 analyze.py。")
            lines.append("")
    lines.append('<a id="综合小结"></a>')
    lines.append("")
    lines.append("# 第四部分：跨课题综合小结（待填写）")
    lines.append("")
    lines.append("> 以下内容需要结合真机实验数据人工撰写；本节给出填写框架。")
    lines.append("")
    lines.append("## 4.1 三个课题的共同方法论")
    lines.append("")
    lines.append("- 统一的数据契约（docs/DATA_DICTIONARY.md）让 Android 采集端与 Python 分析端字段、单位、空值规则一致；")
    lines.append("- 统一的可复现实验流程：App 采集 → data/raw → analysis/ 管线 → 报告与图表；")
    lines.append("- 统一的误差观：先做完整性/异常校验，再做统计与建模，最后明确写出局限。")
    lines.append("")
    lines.append("## 4.2 主要量化结论（填写模板）")
    lines.append("")
    lines.append("- 功耗：____ 工况平均功率最高（____ W），比待机高 ____ 倍；最有效的省电手段是 ____，预计可省 ____ W。")
    lines.append("- 充电宝：从满电到耗尽的输出能量为 ____ Wh，占铭牌能量 ____ Wh 的 ____%。")
    lines.append("- 声振：LOOCV 准确率 ____%，盲测 ____%；在 SNR ____ dB 以下需要降噪或加噪训练。")
    lines.append("")
    lines.append("## 4.3 反思与改进")
    lines.append("")
    lines.append("- 采样规模与统计显著性；")
    lines.append("- 跨设备一致性（不同机型/麦克风/电池）；")
    lines.append("- 从“课程级实验”走向“可复用的评测方法”还缺什么。")
    lines.append("")
    lines.append('<a id="复现步骤"></a>')
    lines.append("")
    lines.append("# 第五部分：复现步骤")
    lines.append("")
    lines.append("~~~powershell")
    lines.append("# 0) 安装依赖（numpy / pandas / matplotlib）")
    lines.append("python -m pip install -r analysis/requirements.txt")
    lines.append("")
    lines.append("# 1) 生成/准备数据：演示数据（synthetic）或真机数据")
    lines.append("python data/demo/generate_demo.py")
    lines.append("")
    lines.append("# 2) 三条分析管线")
    lines.append("python analysis/power/analyze.py --data data/demo/power/20261118_PC-20261118-001 --out analysis/out/power")
    lines.append("python analysis/charge/analyze.py --csv data/templates/topic07/charge_discharge_template.csv --meta data/templates/topic07/session_meta.json --out analysis/out/charge-demo")
    lines.append("python analysis/vibro/analyze.py --data data/demo/vibro/20261118_VD-20261118-001 --out analysis/out/vibro")
    lines.append("")
    lines.append("# 3) 合并总报告草稿")
    lines.append("python analysis/report/build_report.py")
    lines.append("")
    lines.append("# 4) 一键端到端自检")
    lines.append("python analysis/tests/run_all.py")
    lines.append("~~~")
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("## 附录：本次生成的分析产物清单")
    lines.append("")
    for report in reports.values():
        base = report["path"].parent
        lines.append(f"### {base.name}")
        lines.append("")
        if not base.is_dir():
            lines.append("- （无输出）")
            lines.append("")
            continue
        for pattern_dir in ("figures", "tables"):
            directory = base / pattern_dir
            if directory.is_dir():
                for path in sorted(directory.iterdir()):
                    if path.is_file():
                        lines.append(f"- {path.relative_to(root).as_posix()}")
        for path in sorted(base.glob("*.md")) + sorted(base.glob("*.json")):
            lines.append(f"- {path.relative_to(root).as_posix()}")
        lines.append("")
    return "\n".join(lines) + "\n"


def run(root: Path, out_dir: Path, title: str) -> dict:
    reports = read_reports(root)
    draft = build_draft(root, title, reports)
    path = md_utils.write_text(out_dir / "course_report_draft.md", draft)
    found = [key for key, report in reports.items() if report["text"]]
    missing = [key for key, report in reports.items() if not report["text"]]
    print(f"[report] 合并 {len(found)} 份分报告 -> {path}")
    if missing:
        print(f"[report] 警告：缺少分报告 {missing}")
    return {"path": str(path), "found": found, "missing": missing,
            "synthetic": has_synthetic_report(reports)}


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    result = run(Path(args.root).resolve(), Path(args.out).resolve(), args.title)
    if result["synthetic"]:
        print("[report] 注意：输入报告包含 SYNTHETIC 合成演示数据标记")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
