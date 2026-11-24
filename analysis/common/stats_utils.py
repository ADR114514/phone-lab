"""numpy 实现的统计与回归工具（不依赖 scipy / sklearn）。

所有函数对空数组、全 NaN、常数列都返回 NaN 而不是抛异常，方便报告里用 fmt() 统一渲染。
"""
from __future__ import annotations

import math

import numpy as np

__all__ = [
    "as_float_array",
    "mean_std",
    "percentile",
    "pearson",
    "spearman",
    "r_squared",
    "rmse",
    "ols",
    "ols_nonnegative_elimination",
    "linear_slope",
    "correlation_table",
]


def as_float_array(values) -> np.ndarray:
    arr = np.asarray(values, dtype=float).ravel()
    return arr[np.isfinite(arr)]


def mean_std(values) -> tuple[float, float]:
    arr = as_float_array(values)
    if arr.size == 0:
        return float("nan"), float("nan")
    return float(np.mean(arr)), float(np.std(arr, ddof=0))


def percentile(values, q: float) -> float:
    arr = as_float_array(values)
    if arr.size == 0:
        return float("nan")
    return float(np.percentile(arr, q))


def pearson(x, y) -> float:
    x = np.asarray(x, dtype=float).ravel()
    y = np.asarray(y, dtype=float).ravel()
    mask = np.isfinite(x) & np.isfinite(y)
    x, y = x[mask], y[mask]
    if x.size < 3:
        return float("nan")
    if np.std(x) < 1e-12 or np.std(y) < 1e-12:
        return float("nan")
    return float(np.corrcoef(x, y)[0, 1])


def _rank(values: np.ndarray) -> np.ndarray:
    """平均秩（ties 取平均），用于 Spearman。"""
    order = np.argsort(values, kind="mergesort")
    ranks = np.empty(values.size, dtype=float)
    ranks[order] = np.arange(values.size, dtype=float)
    # 处理并列
    sorted_values = values[order]
    i = 0
    while i < values.size:
        j = i + 1
        while j < values.size and sorted_values[j] == sorted_values[i]:
            j += 1
        if j - i > 1:
            ranks[order[i:j]] = np.mean(np.arange(i, j, dtype=float))
        i = j
    return ranks


def spearman(x, y) -> float:
    x = np.asarray(x, dtype=float).ravel()
    y = np.asarray(y, dtype=float).ravel()
    mask = np.isfinite(x) & np.isfinite(y)
    x, y = x[mask], y[mask]
    if x.size < 3:
        return float("nan")
    return pearson(_rank(x), _rank(y))


def r_squared(y_true, y_pred) -> float:
    y_true = np.asarray(y_true, dtype=float).ravel()
    y_pred = np.asarray(y_pred, dtype=float).ravel()
    mask = np.isfinite(y_true) & np.isfinite(y_pred)
    if mask.sum() < 2:
        return float("nan")
    y_true, y_pred = y_true[mask], y_pred[mask]
    ss_res = float(np.sum((y_true - y_pred) ** 2))
    ss_tot = float(np.sum((y_true - np.mean(y_true)) ** 2))
    if ss_tot < 1e-15:
        return float("nan")
    return 1.0 - ss_res / ss_tot


def rmse(y_true, y_pred) -> float:
    y_true = np.asarray(y_true, dtype=float).ravel()
    y_pred = np.asarray(y_pred, dtype=float).ravel()
    mask = np.isfinite(y_true) & np.isfinite(y_pred)
    if mask.sum() == 0:
        return float("nan")
    return float(np.sqrt(np.mean((y_true[mask] - y_pred[mask]) ** 2)))


def ols(X, y) -> tuple[np.ndarray, float, float]:
    """普通最小二乘。返回 (系数, R², RMSE)。X 需自行包含常数列。"""
    X = np.asarray(X, dtype=float)
    y = np.asarray(y, dtype=float).ravel()
    if X.ndim == 1:
        X = X.reshape(-1, 1)
    mask = np.isfinite(y) & np.all(np.isfinite(X), axis=1)
    X, y = X[mask], y[mask]
    if X.shape[0] < X.shape[1] or X.shape[0] < 2:
        return np.full(X.shape[1], np.nan), float("nan"), float("nan")
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    y_hat = X @ beta
    return beta, r_squared(y, y_hat), rmse(y, y_hat)


def ols_nonnegative_elimination(X, y, protect_first: bool = True) -> tuple[np.ndarray, list[int]]:
    """带非负约束的最小二乘（贪心消除法，纯 numpy 实现）。

    OLS 是线性最小二乘；物理上"活动指示量 -> 功率"的系数不应为负。这里先做普通
    OLS，若存在负系数就剔除最负的一列（可保护常数列）再拟合，直到系数全部非负。
    对 4 个工况、24 个样本的小模型，这个启发式稳定且可解释（比 NNLS 更不容易过拟合）。
    """
    X = np.asarray(X, dtype=float)
    y = np.asarray(y, dtype=float).ravel()
    if X.ndim == 1:
        X = X.reshape(-1, 1)
    active = list(range(X.shape[1]))
    removed: list[int] = []
    while active:
        beta, *_ = ols(X[:, active], y)
        if not np.all(np.isfinite(beta)):
            break
        negative = [i for i, value in enumerate(beta) if value < 0.0]
        if not negative:
            full = np.zeros(X.shape[1], dtype=float)
            full[active] = beta
            return full, removed
        # 找最负的一列；常数列（第一列）只有在允许时才会被剔除
        candidates = [i for i in negative if not (protect_first and active[i] == 0)]
        if not candidates:
            break
        worst_local = min(candidates, key=lambda i: beta[i])
        removed.append(active.pop(worst_local))
    full = np.zeros(X.shape[1], dtype=float)
    if active:
        beta, *_ = ols(X[:, active], y)
        full[active] = beta
    return full, removed


def linear_slope(x, y) -> float:
    x = np.asarray(x, dtype=float).ravel()
    y = np.asarray(y, dtype=float).ravel()
    mask = np.isfinite(x) & np.isfinite(y)
    x, y = x[mask], y[mask]
    if x.size < 2 or np.std(x) < 1e-15:
        return float("nan")
    return float(np.polyfit(x, y, 1)[0])


def correlation_table(pairs: dict[tuple[str, str], tuple]) -> list[dict]:
    """pairs: {(a_name, b_name): (a_values, b_values)} -> 相关性表行列表。"""
    rows = []
    for (a_name, b_name), (a_values, b_values) in pairs.items():
        rows.append({
            "指标A": a_name,
            "指标B": b_name,
            "Pearson r": pearson(a_values, b_values),
            "Spearman rho": spearman(a_values, b_values),
            "样本数": int(np.sum(np.isfinite(np.asarray(a_values, dtype=float))
                                 & np.isfinite(np.asarray(b_values, dtype=float)))),
        })
    return rows
