"""matplotlib 统一配置。

严格要求：只用 Agg 后端（不打开窗口，服务器/CI 也能跑）；中文字体优先 Microsoft YaHei。
所有图统一 150 dpi、白底、细网格，报告里直接引用 figures/*.png。
"""
from __future__ import annotations

from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # 必须在 pyplot 之前：禁止交互窗口

import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402

__all__ = ["setup", "save", "PALETTE", "CONDITION_COLORS", "GRADE_COLORS", "cjk_available"]

PALETTE = [
    "#2E5BFF",  # 蓝
    "#FF6B35",  # 橙
    "#00A878",  # 绿
    "#8E44AD",  # 紫
    "#F2C230",  # 黄
    "#E74C3C",  # 红
    "#16A085",  # 青
    "#7F8C8D",  # 灰
]

CONDITION_COLORS = {
    "IDLE": "#7F8C8D",
    "VIDEO": "#2E5BFF",
    "NAVCAM": "#FF6B35",
    "GAME": "#E74C3C",
}

GRADE_COLORS = {
    "强": "#00A878",
    "中": "#F2C230",
    "弱": "#FF6B35",
    "盲区": "#E74C3C",
}

_CJK_CANDIDATES = [
    "Microsoft YaHei",
    "SimHei",
    "Noto Sans CJK SC",
    "Source Han Sans SC",
    "WenQuanYi Micro Hei",
    "PingFang SC",
]

_configured = False


def cjk_available() -> bool:
    """检测系统里是否有可用的中文字体（影响图上中文是否渲染成方块）。"""
    names = {f.name for f in font_manager.fontManager.ttflist}
    return any(candidate in names for candidate in _CJK_CANDIDATES)


def setup() -> None:
    """配置全局 matplotlib 风格；重复调用无副作用。"""
    global _configured
    if _configured:
        return
    families = list(_CJK_CANDIDATES) + ["DejaVu Sans", "Arial"]
    plt.rcParams.update({
        "font.sans-serif": families,
        "font.family": "sans-serif",
        "axes.unicode_minus": False,
        "figure.dpi": 100,
        "savefig.dpi": 150,
        "savefig.facecolor": "white",
        "figure.facecolor": "white",
        "axes.facecolor": "white",
        "axes.edgecolor": "#555555",
        "axes.grid": True,
        "grid.color": "#DDDDDD",
        "grid.linewidth": 0.6,
        "grid.alpha": 0.8,
        "axes.titlesize": 12,
        "axes.labelsize": 10,
        "xtick.labelsize": 9,
        "ytick.labelsize": 9,
        "legend.fontsize": 9,
        "axes.spines.top": False,
        "axes.spines.right": False,
    })
    _configured = True


def save(fig, path: str | Path, *, dpi: int = 150, close: bool = True) -> Path:
    """保存图片；自动创建目录并关闭 figure，避免批量出图时内存泄漏。"""
    setup()
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path, dpi=dpi, bbox_inches="tight", facecolor="white")
    if close:
        plt.close(fig)
    return path
