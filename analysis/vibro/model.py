"""z-score + kNN(k=3) 与抗噪工具，纯 numpy 实现（不依赖 sklearn）。

与 App 的 model 规格一致（docs/feature_spec.md 第 3 节）：
  * 每个特征列用训练集均值/标准差做 z-score；标准差为 0 时置 1（该列 z 恒为 0）；
  * 缺失值（NaN）用训练集均值填补，避免传感器缺列把距离函数带偏；
  * k = 3，欧氏距离；投票权重 = 1/(d+eps)，平票时取最近邻所属类别；
  * 置信度 = 获胜类别的权重 / 全部 k 个邻居权重之和；
  * LOOCV 时每个折都重新拟合标准化参数，held-out 样本不参与统计。
"""
from __future__ import annotations

import math
from collections import Counter

import numpy as np

K = 3
DESCRIPTOR = "knn(k=3, zscore)"
EPS = 1e-12


class Standardizer:
    """按列 z-score；NaN 用训练均值填补。"""

    def __init__(self, means: np.ndarray, stds: np.ndarray):
        self.means = means
        self.stds = stds

    @classmethod
    def fit(cls, rows: np.ndarray) -> "Standardizer":
        rows = np.asarray(rows, dtype=float)
        if rows.ndim != 2:
            raise ValueError("Standardizer.fit 需要二维特征矩阵")
        means = np.full(rows.shape[1], 0.0)
        stds = np.full(rows.shape[1], 1.0)
        for j in range(rows.shape[1]):
            column = rows[:, j]
            finite = column[np.isfinite(column)]
            if finite.size == 0:
                continue
            mean = float(np.mean(finite))
            means[j] = mean
            if finite.size > 1:
                std = float(np.sqrt(np.mean((finite - mean) ** 2)))
            else:
                std = 0.0
            stds[j] = std if std > 1e-12 else 1.0
        return cls(means, stds)

    def transform(self, row: np.ndarray) -> np.ndarray:
        row = np.asarray(row, dtype=float)
        values = np.where(np.isfinite(row), row, self.means)
        return (values - self.means) / self.stds


class KnnClassifier:
    """k 近邻分类器：tie-break 用最近邻距离，置信度用距离倒数加权。"""

    def __init__(self, k: int = K):
        self.k = max(1, int(k))
        self.standardizer: Standardizer | None = None
        self._X: np.ndarray | None = None
        self._labels: list[str] = []

    def fit(self, features: np.ndarray, labels: list[str]) -> "KnnClassifier":
        features = np.asarray(features, dtype=float)
        if features.ndim != 2:
            raise ValueError("fit 需要二维特征矩阵")
        if features.shape[0] != len(labels):
            raise ValueError("特征行数与标签数不一致")
        self.standardizer = Standardizer.fit(features)
        self._X = np.vstack([self.standardizer.transform(row) for row in features])
        self._labels = list(labels)
        return self

    def predict(self, features: np.ndarray) -> dict:
        if self.standardizer is None or self._X is None:
            raise RuntimeError("KnnClassifier 未 fit")
        z = self.standardizer.transform(np.asarray(features, dtype=float))
        distances = np.sqrt(np.sum((self._X - z) ** 2, axis=1))
        order = np.argsort(distances, kind="mergesort")
        k = min(self.k, len(order))
        neighbours = order[:k]
        neighbour_labels = [self._labels[i] for i in neighbours]
        neighbour_distances = [float(distances[i]) for i in neighbours]
        weights: dict[str, float] = {}
        nearest: dict[str, float] = {}
        for label, distance in zip(neighbour_labels, neighbour_distances):
            weight = 1.0 / (distance + EPS)
            weights[label] = weights.get(label, 0.0) + weight
            nearest[label] = min(nearest.get(label, float("inf")), distance)
        # 票数加权 + 平票取距离更近者
        best_label = max(weights, key=lambda label: (weights[label], -nearest[label]))
        total_weight = sum(weights.values())
        confidence = weights[best_label] / total_weight if total_weight > 0 else 0.0
        return {
            "label": best_label,
            "confidence": float(confidence),
            "neighbor_labels": neighbour_labels,
            "distances": neighbour_distances,
        }


def confusion_matrix(labels: list[str], truths: list[str], predictions: list[str]) -> list[list[int]]:
    index = {label: i for i, label in enumerate(labels)}
    matrix = [[0] * len(labels) for _ in labels]
    for truth, predicted in zip(truths, predictions):
        if truth in index and predicted in index:
            matrix[index[truth]][index[predicted]] += 1
    return matrix


def per_condition_accuracy(labels: list[str], truths: list[str],
                           predictions: list[str]) -> dict[str, float]:
    total: dict[str, int] = {label: 0 for label in labels}
    correct: dict[str, int] = {label: 0 for label in labels}
    for truth, predicted in zip(truths, predictions):
        if truth not in total:
            continue
        total[truth] += 1
        if truth == predicted:
            correct[truth] += 1
    return {label: (correct[label] / total[label] if total[label] else 0.0) for label in labels}


def accuracy(truths: list[str], predictions: list[str]) -> float:
    if not truths:
        return 0.0
    return sum(1 for truth, predicted in zip(truths, predictions) if truth == predicted) / len(truths)


def loocv(features: np.ndarray, labels: list[str], k: int = K) -> dict:
    """留一法交叉验证；每个折重新拟合 z-score 参数。"""
    features = np.asarray(features, dtype=float)
    labels = list(labels)
    folds: list[dict] = []
    if len(labels) < 2:
        # 真实数据边采边测时常常只有 1 组：直接返回可用的空评估，而不是缺字段，
        # 让报告与 CSV 仍能生成并明确标注"样本数不足，未做留一法"。
        order = sorted(set(labels))
        return {"folds": folds, "accuracy": 0.0,
                "confusion": confusion_matrix(order, [], []),
                "per_condition": {label: 0.0 for label in order},
                "labels": order,
                "note": f"样本数不足 2（n={len(labels)}），跳过 LOOCV"}
    for index in range(len(labels)):
        mask = np.arange(len(labels)) != index
        classifier = KnnClassifier(k).fit(features[mask], [labels[i] for i in np.where(mask)[0]])
        prediction = classifier.predict(features[index])
        folds.append({
            "index": index,
            "truth": labels[index],
            "predicted": prediction["label"],
            "confidence": prediction["confidence"],
        })
    order = sorted(set(labels))
    truths = [fold["truth"] for fold in folds]
    predictions = [fold["predicted"] for fold in folds]
    return {
        "folds": folds,
        "accuracy": accuracy(truths, predictions),
        "confusion": confusion_matrix(order, truths, predictions),
        "per_condition": per_condition_accuracy(order, truths, predictions),
        "labels": order,
    }


# ---------------------------------------------------------------- 抗噪

def white_noise(count: int, rng: np.random.Generator) -> np.ndarray:
    return rng.normal(0.0, 1.0, size=count)


def pink_noise(count: int, rng: np.random.Generator) -> np.ndarray:
    """白噪声经 1/f 滤波（Paul Kellet 7 极点近似），再归一化到 RMS=1。"""
    white = rng.normal(0.0, 1.0, size=count)
    out = np.zeros(count, dtype=float)
    b0 = b1 = b2 = b3 = b4 = b5 = b6 = 0.0
    for i in range(count):
        w = white[i]
        b0 = 0.99886 * b0 + w * 0.0555179
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522
        b5 = -0.7616 * b5 - w * 0.0168980
        out[i] = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
        b6 = w * 0.115926
    rms = float(np.sqrt(np.mean(out * out))) if count else 0.0
    return out / rms if rms > 0 else out


def rms(values: np.ndarray) -> float:
    values = np.asarray(values, dtype=float)
    if values.size == 0:
        return 0.0
    return float(np.sqrt(np.mean(values * values)))


def scale_noise_to_snr(signal: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    signal_rms = rms(signal)
    noise_rms = rms(noise)
    if signal_rms <= 0.0 or noise_rms <= 0.0:
        return np.zeros_like(noise)
    scale = signal_rms / (noise_rms * 10.0 ** (snr_db / 20.0))
    return noise * scale


def mix_noise(signal: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    """S + 缩放后噪声；若峰值超过满量程则整体缩放（保持精确 SNR，不削波）。"""
    scaled = scale_noise_to_snr(signal, noise, snr_db)
    mixture = np.asarray(signal, dtype=float) + scaled
    peak = float(np.max(np.abs(mixture))) if mixture.size else 0.0
    if peak > 1.0:
        mixture = mixture / peak
    return mixture


def measured_snr_db(signal: np.ndarray, noise: np.ndarray) -> float:
    signal_power = float(np.sum(np.asarray(signal, dtype=float) ** 2))
    noise_power = float(np.sum(np.asarray(noise, dtype=float) ** 2))
    if signal_power <= 0.0 or noise_power <= 0.0:
        return float("nan")
    return 10.0 * math.log10(signal_power / noise_power)


def spectral_subtract(signal: np.ndarray, *, noise_percentile: float = 20.0,
                      alpha: float = 1.5, floor_ratio: float = 0.02) -> np.ndarray:
    """简化谱减法降噪（纯 numpy，全局 FFT 一次）。

    噪声底估计：把功率谱各频点的中低分位数（默认 20%）视为宽带噪声功率；
    幅度谱减去 alpha * sqrt(噪声底)，再做下限约束，保留原相位后用 irfft 还原。
    对白/粉红这类宽带噪声有效，且不会像低通滤波那样把吹风机的高频特征抹掉。
    """
    x = np.asarray(signal, dtype=float).ravel()
    if x.size < 64:
        return x.copy()
    spectrum = np.fft.rfft(x)
    magnitude = np.abs(spectrum)
    power = magnitude ** 2
    floor_power = float(np.percentile(power, noise_percentile))
    if floor_power <= 0.0:
        return x.copy()
    noise_magnitude = math.sqrt(floor_power)
    cleaned = np.maximum(magnitude - alpha * noise_magnitude, floor_ratio * magnitude)
    denoised = np.fft.irfft(cleaned * np.exp(1j * np.angle(spectrum)), n=x.size)
    return denoised