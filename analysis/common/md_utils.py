"""Markdown 报告拼装工具：表格、图片占位、目录结构。

pandas.DataFrame.to_markdown 依赖 tabulate（不在依赖清单里），这里自己实现一个
不依赖第三方库的 Markdown 表格渲染，保证 requirements 只有 numpy/pandas/matplotlib。
"""
from __future__ import annotations

import math
from pathlib import Path
from typing import Sequence

__all__ = ["md_table", "md_figure", "df_table", "write_text", "fmt_number", "section"]


def fmt_number(value, digits: int = 2, suffix: str = "") -> str:
    if value is None:
        return "—"
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    if not math.isfinite(number):
        return "—"
    if digits <= 0:
        return f"{int(round(number))}{suffix}"
    return f"{number:.{digits}f}{suffix}"


def md_table(headers: Sequence[str], rows: Sequence[Sequence], aligns: Sequence[str] | None = None) -> str:
    """生成标准 Markdown 表格；单元格里的 None/NaN 显示为 em dash。"""
    headers = [str(h) for h in headers]
    align_marks = []
    for index, _ in enumerate(headers):
        mark = "---"
        if aligns and index < len(aligns):
            mark = {"left": ":---", "center": ":---:", "right": "---:"}.get(aligns[index], "---")
        align_marks.append(mark)
    lines = ["| " + " | ".join(headers) + " |", "| " + " | ".join(align_marks) + " |"]
    for row in rows:
        cells = []
        for value in row:
            if value is None:
                cells.append("—")
                continue
            if isinstance(value, float):
                cells.append(fmt_number(value))
            else:
                cells.append(str(value))
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def df_table(df, columns: Sequence[str] | None = None, digits: int = 2) -> str:
    columns = list(columns) if columns else list(df.columns)
    rows = []
    for _, record in df.iterrows():
        row = []
        for column in columns:
            value = record.get(column)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                row.append(fmt_number(float(value), digits))
            else:
                row.append("—" if value is None else str(value))
        rows.append(row)
    return md_table(columns, rows)


def md_figure(relative_path: str, caption: str) -> str:
    return f"![{caption}]({relative_path})\n\n*{caption}*"


def section(title: str, level: int = 2) -> str:
    return f"{'#' * level} {title}"


def write_text(path: str | Path, content: str) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path
