"""CSV / 目录 I/O 公共函数。

数据契约要求：CSV 为 UTF-8（可带 BOM）、CRLF、空字段表示无数据。真机导出可能因为
Windows/Excel 另存而变成 GBK，所以读取时按 utf-8-sig -> utf-8 -> gbk -> cp936 -> latin-1
依次尝试，禁止因为编码问题让整条管线失败。
"""
from __future__ import annotations

import math
import re
from pathlib import Path
from typing import Iterable, Sequence

import numpy as np
import pandas as pd

__all__ = [
    "read_csv",
    "read_csvs",
    "write_csv",
    "require_columns",
    "coerce_numeric",
    "expected_row_report",
    "fill_missing_note",
    "find_files",
    "has_files",
    "safe_div",
    "nan_to_none",
    "fmt",
    "ensure_dir",
    "relative_posix",
]

_ENCODINGS = ("utf-8-sig", "utf-8", "gbk", "cp936", "big5", "latin-1")


def read_csv(path: str | Path, *, required: Sequence[str] | None = None,
             name: str | None = None, **kwargs) -> pd.DataFrame:
    """读取一个 CSV，自动处理 BOM 与非 UTF-8 编码。

    pandas 会把空字段读成 NaN（数据契约规定的"无数据"表示法），这里保持该行为；
    调用方需要区分"0"和"缺失"时用 isna() 判断。
    """
    path = Path(path)
    if not path.is_file():
        raise FileNotFoundError(f"CSV 不存在: {path}")
    last_error: Exception | None = None
    df: pd.DataFrame | None = None
    for encoding in _ENCODINGS:
        try:
            df = pd.read_csv(path, encoding=encoding, **kwargs)
            break
        except UnicodeDecodeError as exc:  # 换下一种编码再试
            last_error = exc
    if df is None:
        raise RuntimeError(f"无法解码 CSV {path}: {last_error}")
    df.columns = [str(c).strip() for c in df.columns]
    if required:
        require_columns(df, required, name or path.name)
    return df


def read_csvs(paths: Iterable[str | Path], *, required: Sequence[str] | None = None,
              **kwargs) -> pd.DataFrame:
    """合并多个同构 CSV（例如同一目录下所有 survey_*.csv / session_*.csv）。"""
    frames: list[pd.DataFrame] = []
    for path in sorted(Path(p) for p in paths):
        df = read_csv(path, required=required, **kwargs)
        df.insert(0, "_source_file", path.name)
        frames.append(df)
    if not frames:
        raise FileNotFoundError("没有找到可读取的 CSV 文件")
    return pd.concat(frames, ignore_index=True)


def write_csv(df: pd.DataFrame, path: str | Path, *, float_format: str | None = "%.6f") -> Path:
    """写 analysis/out 下的分析表格：UTF-8 BOM + CRLF，方便 Excel 直接打开中文。"""
    path = Path(path)
    ensure_dir(path.parent)
    df.to_csv(path, index=False, encoding="utf-8-sig", lineterminator="\r\n",
              float_format=float_format)
    return path


def require_columns(df: pd.DataFrame, required: Sequence[str], name: str = "数据表") -> None:
    missing = [c for c in required if c not in df.columns]
    if missing:
        raise ValueError(f"{name} 缺少数据契约要求的列: {missing}（实际列: {list(df.columns)}）")


def coerce_numeric(df: pd.DataFrame, columns: Sequence[str], *, required: bool = False) -> pd.DataFrame:
    """把指定列转成数值；空字段 -> NaN，非数值字符串 -> NaN（并保留原表其它列）。"""
    out = df.copy()
    for column in columns:
        if column not in out.columns:
            if required:
                raise ValueError(f"缺少数值列: {column}")
            continue
        out[column] = pd.to_numeric(out[column], errors="coerce")
        out[column] = out[column].replace([np.inf, -np.inf], np.nan)
    return out


def expected_row_report(df: pd.DataFrame, expected: int, name: str) -> dict:
    """行数完整性检查结果，供 *_report.md"数据完整性"一节直接引用。"""
    actual = int(len(df))
    return {
        "表": name,
        "期望行数": expected,
        "实际行数": actual,
        "完整": bool(actual == expected),
        "差额": actual - expected,
    }


def fill_missing_note(df: pd.DataFrame, column: str, default: str = "") -> pd.DataFrame:
    out = df.copy()
    if column in out.columns:
        out[column] = out[column].fillna(default)
    return out


def find_files(root: str | Path, pattern: str) -> list[Path]:
    """按 glob 递归查找文件（大小写不敏感由文件系统决定）。"""
    root = Path(root)
    if not root.exists():
        return []
    return sorted(p for p in root.rglob(pattern) if p.is_file())


def has_files(root: str | Path, pattern: str) -> bool:
    return bool(find_files(root, pattern))


def ensure_dir(path: str | Path) -> Path:
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    return path


def safe_div(numerator: float, denominator: float, default: float = float("nan")) -> float:
    if denominator is None or numerator is None:
        return default
    try:
        if not math.isfinite(float(denominator)) or abs(float(denominator)) < 1e-12:
            return default
        value = float(numerator) / float(denominator)
    except (TypeError, ValueError, ZeroDivisionError):
        return default
    return value if math.isfinite(value) else default


def nan_to_none(value):
    """把 NaN/Inf 转成 None，便于写 JSON。"""
    if value is None:
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return value
    if not math.isfinite(number):
        return None
    return number


def fmt(value, digits: int = 2, suffix: str = "") -> str:
    """报告里用的数值格式化：缺失值输出 em dash，避免出现 nan。"""
    if value is None:
        return "—"
    try:
        number = float(value)
    except (TypeError, ValueError):
        return str(value)
    if not math.isfinite(number):
        return "—"
    return f"{number:.{digits}f}{suffix}"


def relative_posix(path: str | Path, start: str | Path | None = None) -> str:
    """输出相对路径（统一用 /），便于报告里的 Markdown 链接。"""
    path = Path(path).resolve()
    if start is not None:
        try:
            return path.relative_to(Path(start).resolve()).as_posix()
        except ValueError:
            pass
    return path.as_posix()
