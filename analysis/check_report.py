#!/usr/bin/env python3
"""报告完成度自检：占位符、统计量、演示数据残留、结构完整性。

用法：
  python analysis/check_report.py reports/*.docx
  python analysis/check_report.py reports/*.docx --drafts   # 初稿模式：有占位符只 WARN
退出码：0 通过（可含 WARN）；1 有 FAIL（正式提交前必须为 0）。
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from docx import Document


SECTIONS = ["摘要", "实验目的", "实验方法", "结果", "讨论", "结论", "局限", "附录"]
STAT_WORDS = ["p<", "p =", "p=", "Cohen", "置信区间", "CI", "Mann", "Wilcoxon", "方差分析", "ANOVA"]


def check(path: Path, drafts: bool) -> tuple[int, int]:
    doc = Document(str(path))
    texts = [p.text for p in doc.paragraphs]
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                texts.append(cell.text)
    full = "\n".join(texts)
    placeholders = full.count("____")
    demo = full.count("演示数据") + full.count("演示批次")
    sections_found = [s for s in SECTIONS if s in full]
    stats_found = [w for w in STAT_WORDS if w in full]

    level = "PASS"
    problems = []
    if placeholders > 0:
        level = "WARN" if drafts else "FAIL"
        problems.append(f"占位符 ____ {placeholders} 处")
    if demo > 0:
        level = "WARN" if drafts else "FAIL"
        problems.append(f"疑似演示数据标记 {demo} 处")
    if len(sections_found) < 4:
        level = "FAIL"
        problems.append(f"结构章节不足（找到 {sections_found}）")
    if not stats_found and not drafts:
        level = "WARN"
        problems.append("未发现 p 值/效应量/置信区间")
    print(f"{path.name}: {level} | 段落 {len(doc.paragraphs)} 表格 {len(doc.tables)} "
          f"| 占位符 {placeholders} 演示 {demo} | 统计词 {len(stats_found)} | " + ("；".join(problems) if problems else "OK"))
    return 0 if level != "FAIL" else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--drafts", action="store_true")
    args = ap.parse_args()
    bad = 0
    for f in args.files:
        p = Path(f)
        if not p.exists():
            print(f"{f}: 文件不存在")
            bad += 1
            continue
        bad += check(p, args.drafts)
    if bad:
        print(f"FAIL：{bad} 份报告未达到提交标准")
        return 1
    print("PASS：报告完成度检查通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
