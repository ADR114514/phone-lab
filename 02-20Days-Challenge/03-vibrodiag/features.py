"""声振特征提取（严格对齐 docs/feature_spec.md 第 4 节与 App 的 Kotlin 实现）。

实现约定（与 apps/vibrodiag/features/FeatureExtractor.kt 逐条一致）：

音频
  * x = pcm / 32768；帧起点 0, 2048, 4096 ... 直到 start < N；最后一帧先减"帧内均值"，
    再乘 Hann(4096)（短帧只用 Hann(4096) 的前 length 个系数，其余补零）；
    P_k = |FFT_k|^2 / 4096，多帧算术平均，取 k = 0..2048。
  * audio_rms_dbfs / peak_dbfs 用归一化信号算 20*log10；空信号 -120 dBFS。
  * audio_peak_mag = sqrt(max_k P_k)（不含 2x 系数）；peak_hz 用抛物线插值。
  * centroid / flatness / 谐波能量 / 频带分母从 k = 1 起算；rolloff 对全谱求和。
  * 谐波中心 bin = round(f0 * h / binHz)（四舍五入远离零），取 ±2 bin 并去重；
    harmonic_ratio = h / total，hnr_db = 10*log10(h / (total - h + 1e-15))。
振动
  * m(t) = sqrt(x^2+y^2+z^2)，先去线性趋势再减均值。
  * fftSize = min(4096, nextPow2(N))，hop = fftSize/2，Hann 窗，同样的 |X|^2/N 功率定义。
  * 频带分母为全谱总能量；下界 >= Nyquist 的频带写空（NaN）。
  * 所有无法计算的值写 NaN，CSV 里输出为空字段（不写 nan/inf）。

本模块只依赖 numpy 与标准库（wave/csv/json），方便在没有 pandas 的机器上单独跑，
也方便与 App 交叉验证同一份 WAV。
"""
from __future__ import annotations

import csv
import json
import math
import wave
from pathlib import Path
from typing import Iterable

import numpy as np

AUDIO_FFT_SIZE = 4096
AUDIO_HOP = 2048
AUDIO_SAMPLE_RATE = 44100
ACCEL_FFT_SIZE = 4096
SILENCE_DBFS = -120.0
EPS = 1e-15

AUDIO_TIME = [
    "audio_rms_dbfs", "audio_peak_dbfs", "audio_crest_db",
    "audio_zcr", "audio_kurtosis", "audio_skewness",
]
AUDIO_SPECTRUM = [
    "audio_centroid_hz", "audio_rolloff85_hz", "audio_flatness",
    "audio_peak_hz", "audio_peak_mag", "audio_harmonic_ratio", "audio_hnr_db",
]
AUDIO_BANDS = [
    "band_0_100", "band_100_300", "band_300_1k", "band_1k_2k",
    "band_2k_5k", "band_5k_10k", "band_10k_20k",
]
ACCEL_TIME = ["accel_rms", "accel_peak", "accel_crest", "accel_zcr"]
ACCEL_SPECTRUM = ["accel_peak_hz", "accel_centroid_hz", "accel_flatness"]
ACCEL_BANDS = [
    "accel_band_0_5", "accel_band_5_10", "accel_band_10_25",
    "accel_band_25_50", "accel_band_50_100",
]
AUDIO_BAND_EDGES = [(0.0, 100.0), (100.0, 300.0), (300.0, 1000.0), (1000.0, 2000.0),
                    (2000.0, 5000.0), (5000.0, 10000.0), (10000.0, 20000.0)]
ACCEL_BAND_EDGES = [(0.0, 5.0), (5.0, 10.0), (10.0, 25.0), (25.0, 50.0), (50.0, 100.0)]

NUMERIC_COLUMNS = AUDIO_TIME + AUDIO_SPECTRUM + AUDIO_BANDS + ACCEL_TIME + ACCEL_SPECTRUM + ACCEL_BANDS
LABEL_COLUMNS = ["condition_id", "condition_label", "group_index",
                 "file_stem", "recorded_at_ms", "duration_s"]
CSV_HEADER = NUMERIC_COLUMNS + LABEL_COLUMNS


# ---------------------------------------------------------------- 基础工具

def hann_window(size: int) -> np.ndarray:
    """与 core.signal.Fft.hannWindow 一致：0.5*(1-cos(2*pi*i/(N-1)))。"""
    if size <= 1:
        return np.ones(max(size, 1), dtype=float)
    i = np.arange(size, dtype=float)
    return 0.5 * (1.0 - np.cos(2.0 * math.pi * i / (size - 1)))


def _round_half_away(value: float) -> int:
    """Kotlin roundToInt 的语义：.5 远离零取整（Python round 是银行家舍入，不能直接用）。"""
    return int(math.floor(value + 0.5)) if value >= 0 else -int(math.floor(-value + 0.5))


def accel_fft_size(n: int) -> int:
    """最小 >= n 的 2 的幂，并封顶到 4096（至少 2）。"""
    if n >= ACCEL_FFT_SIZE:
        return ACCEL_FFT_SIZE
    size = 2
    while size < n:
        size <<= 1
    return min(size, ACCEL_FFT_SIZE)


def average_power_spectrum(x: np.ndarray, fft_size: int, hop: int,
                           window: np.ndarray) -> np.ndarray:
    """多帧平均功率谱 P_k = |X_k|^2 / N，返回 k = 0..N/2。"""
    x = np.asarray(x, dtype=float).ravel()
    half = fft_size // 2 + 1
    accumulator = np.zeros(half, dtype=float)
    if x.size == 0:
        return accumulator
    frames = 0
    start = 0
    while start < x.size:
        length = min(fft_size, x.size - start)
        segment = x[start:start + length]
        mean = float(np.mean(segment))
        frame = np.zeros(fft_size, dtype=float)
        frame[:length] = (segment - mean) * window[:length]
        spectrum = np.fft.rfft(frame)
        accumulator += (spectrum.real ** 2 + spectrum.imag ** 2) / fft_size
        frames += 1
        start += hop
    if frames > 1:
        accumulator /= frames
    return accumulator


def _zero_crossing_rate(values: np.ndarray) -> float:
    values = np.asarray(values, dtype=float).ravel()
    if values.size < 2:
        return 0.0
    changes = int(np.sum((values[1:] < 0.0) != (values[:-1] < 0.0)))
    return changes / values.size


def _standardized_moments(values: np.ndarray) -> tuple[float, float]:
    values = np.asarray(values, dtype=float).ravel()
    n = values.size
    if n < 2:
        return float("nan"), float("nan")
    mean = float(np.mean(values))
    deviation = values - mean
    m2 = float(np.mean(deviation ** 2))
    m3 = float(np.mean(deviation ** 3))
    m4 = float(np.mean(deviation ** 4))
    if m2 <= 1e-30:
        return float("nan"), float("nan")
    sigma = math.sqrt(m2)
    skewness = m3 / (sigma * sigma * sigma)
    kurtosis = m4 / (m2 * m2)
    return skewness, kurtosis


def _band_energy(power: np.ndarray, bin_hz: float, low_hz: float, high_hz: float) -> float:
    frequencies = np.arange(power.size, dtype=float) * bin_hz
    mask = (frequencies >= low_hz) & (frequencies < high_hz)
    return float(np.sum(power[mask]))


def _strongest_bin(power: np.ndarray, min_bin: int) -> int:
    best = -1
    best_value = 0.0
    for k in range(min_bin, power.size):
        if power[k] > best_value:
            best_value = float(power[k])
            best = k
    return best


def _refine_peak(power: np.ndarray, index: int, bin_hz: float) -> float:
    if index <= 0 or index >= power.size - 1:
        return index * bin_hz
    a = float(power[index - 1])
    b = float(power[index])
    c = float(power[index + 1])
    denominator = a - 2.0 * b + c
    delta = 0.0 if abs(denominator) < 1e-15 else 0.5 * (a - c) / denominator
    return (index + delta) * bin_hz


def _centroid(power: np.ndarray, bin_hz: float, min_bin: int) -> float:
    k = np.arange(min_bin, power.size, dtype=float)
    weights = power[min_bin:]
    denominator = float(np.sum(weights))
    if denominator <= 0.0:
        return float("nan")
    return float(np.sum(k * bin_hz * weights) / denominator)


def _rolloff(power: np.ndarray, bin_hz: float, fraction: float = 0.85) -> float:
    total = float(np.sum(power))
    if total <= 0.0:
        return float("nan")
    threshold = total * fraction
    cumulative = 0.0
    for k in range(power.size):
        cumulative += float(power[k])
        if cumulative >= threshold:
            return k * bin_hz
    return (power.size - 1) * bin_hz


def _flatness(power: np.ndarray, min_bin: int) -> float:
    values = power[min_bin:] + EPS
    if values.size == 0:
        return float("nan")
    arithmetic = float(np.mean(values))
    if arithmetic <= 0.0:
        return float("nan")
    geometric = float(np.exp(np.mean(np.log(values))))
    return min(max(geometric / arithmetic, 0.0), 1.0)


def _harmonic_mask_indices(power: np.ndarray, bin_hz: float, fundamental_hz: float) -> list[int] | None:
    if not math.isfinite(fundamental_hz) or fundamental_hz <= 0.0 or bin_hz <= 0.0:
        return None
    used: set[int] = set()
    for harmonic in range(2, 7):
        center = _round_half_away(harmonic * fundamental_hz / bin_hz)
        for delta in range(-2, 3):
            k = center + delta
            if 1 <= k < power.size:
                used.add(k)
    return sorted(used)


def _harmonic_features(power: np.ndarray, bin_hz: float,
                       fundamental_hz: float) -> tuple[float, float]:
    indices = _harmonic_mask_indices(power, bin_hz, fundamental_hz)
    total = float(np.sum(power[1:]))
    if indices is None or total <= 0.0:
        return float("nan"), float("nan")
    harmonic = float(np.sum(power[indices]))
    ratio = min(max(harmonic / total, 0.0), 1.0)
    noise = max(total - harmonic, 0.0)
    if harmonic <= 0.0:
        return ratio, float("nan")
    return ratio, 10.0 * math.log10(harmonic / (noise + EPS))


def _detrend(values: np.ndarray, sample_rate_hz: float) -> np.ndarray:
    """最小二乘去线性趋势 + 去均值（与 Kotlin detrend 一致）。"""
    values = np.asarray(values, dtype=float).ravel()
    n = values.size
    if n == 0:
        return np.zeros(0, dtype=float)
    t = np.arange(n, dtype=float) / sample_rate_hz
    mean_t = float(np.mean(t))
    mean_v = float(np.mean(values))
    covariance = float(np.sum((t - mean_t) * (values - mean_v)))
    variance = float(np.sum((t - mean_t) ** 2))
    slope = covariance / variance if variance > 0.0 else 0.0
    intercept = mean_v - slope * mean_t
    residual = values - (intercept + slope * t)
    return residual - float(np.mean(residual))


# ---------------------------------------------------------------- WAV / CSV 读取

def read_wav(path: str | Path) -> tuple[np.ndarray, int]:
    """用标准库 wave 读 16-bit PCM WAV，返回 (归一化到 [-1,1] 的 float64, 采样率)。

    多声道时与 Kotlin WavIo 一致，只取第一个声道（本项目数据契约是单声道，
    多声道视为异常数据，但不应让整条管线崩溃）。
    """
    path = Path(path)
    with wave.open(str(path), "rb") as wf:
        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        sample_rate = wf.getframerate()
        frames = wf.readframes(wf.getnframes())
    if sample_width != 2:
        raise ValueError(f"{path.name}: 只支持 16-bit PCM，实际 {sample_width * 8} bit")
    if channels <= 0:
        raise ValueError(f"{path.name}: 声道数非法 {channels}")
    pcm = np.frombuffer(frames, dtype="<i2")
    if channels > 1:
        pcm = pcm.reshape(-1, channels)[:, 0].copy()
    return pcm.astype(np.float64) / 32768.0, sample_rate


def read_accel_csv(path: str | Path) -> dict:
    """读加速度 CSV（t_ns,t_ms,x,y,z,mag_g,sample_rate_hz），返回数组字典。

    采样率以 CSV 中的 sample_rate_hz 为准（200 或 ~50）；缺失时按 t_ns 差分估算。
    """
    path = Path(path)
    xs: list[float] = []
    ys: list[float] = []
    zs: list[float] = []
    ts: list[float] = []
    rate = float("nan")
    with path.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames:
            reader.fieldnames = [str(name).strip() for name in reader.fieldnames]
        for row in reader:
            try:
                xs.append(float(row.get("x") or "nan"))
                ys.append(float(row.get("y") or "nan"))
                zs.append(float(row.get("z") or "nan"))
                ts.append(float(row.get("t_ns") or "nan"))
            except (TypeError, ValueError):
                continue
            value = row.get("sample_rate_hz")
            if value not in (None, "") and not math.isfinite(rate):
                try:
                    rate = float(value)
                except ValueError:
                    rate = float("nan")
    x = np.asarray(xs, dtype=float)
    y = np.asarray(ys, dtype=float)
    z = np.asarray(zs, dtype=float)
    t = np.asarray(ts, dtype=float)
    if not math.isfinite(rate) or rate <= 0.0:
        deltas = np.diff(t[np.isfinite(t)])
        if deltas.size > 0:
            median = float(np.median(deltas))
            if median > 0:
                rate = 1e9 / median
    if not math.isfinite(rate) or rate <= 0.0:
        rate = 200.0
    return {"x": x, "y": y, "z": z, "sample_rate_hz": float(rate)}


def read_meta(path: str | Path) -> dict:
    try:
        with Path(path).open("r", encoding="utf-8-sig") as handle:
            return json.load(handle)
    except (OSError, json.JSONDecodeError):
        return {}


# ---------------------------------------------------------------- 特征提取

def extract_audio_features(samples: np.ndarray | Iterable[int], sample_rate: int = AUDIO_SAMPLE_RATE) -> dict:
    """音频特征。samples 可以是归一化 float（[-1,1]）或 int16 PCM。"""
    x = np.asarray(samples)
    if x.dtype.kind in ("i", "u"):
        x = x.astype(np.float64) / 32768.0
    else:
        x = x.astype(np.float64)
    n = x.size
    out: dict[str, float] = {}

    rms = math.sqrt(float(np.mean(x * x))) if n > 0 else 0.0
    peak = float(np.max(np.abs(x))) if n > 0 else 0.0
    rms_dbfs = 20.0 * math.log10(rms) if rms > 0.0 else SILENCE_DBFS
    peak_dbfs = 20.0 * math.log10(peak) if peak > 0.0 else SILENCE_DBFS
    out["audio_rms_dbfs"] = rms_dbfs
    out["audio_peak_dbfs"] = peak_dbfs
    out["audio_crest_db"] = peak_dbfs - rms_dbfs
    out["audio_zcr"] = _zero_crossing_rate(x)
    skewness, kurtosis = _standardized_moments(x)
    out["audio_skewness"] = skewness
    out["audio_kurtosis"] = kurtosis

    window = hann_window(AUDIO_FFT_SIZE)
    power = average_power_spectrum(x, AUDIO_FFT_SIZE, AUDIO_HOP, window)
    bin_hz = sample_rate / AUDIO_FFT_SIZE
    band_denominator = _band_energy(power, bin_hz, 20.0, 20000.0)
    peak_index = _strongest_bin(power, 1)
    peak_power = float(power[peak_index]) if peak_index >= 0 else 0.0
    peak_hz = (_refine_peak(power, peak_index, bin_hz)
               if peak_index >= 0 and peak_power > 0.0 else float("nan"))

    out["audio_centroid_hz"] = _centroid(power, bin_hz, 1)
    out["audio_rolloff85_hz"] = _rolloff(power, bin_hz, 0.85)
    out["audio_flatness"] = _flatness(power, 1)
    out["audio_peak_hz"] = peak_hz
    out["audio_peak_mag"] = math.sqrt(peak_power) if peak_index >= 0 else float("nan")
    ratio, hnr = _harmonic_features(power, bin_hz, peak_hz)
    out["audio_harmonic_ratio"] = ratio
    out["audio_hnr_db"] = hnr

    for (low, high), name in zip(AUDIO_BAND_EDGES, AUDIO_BANDS):
        energy = _band_energy(power, bin_hz, low, high)
        out[name] = energy / band_denominator if band_denominator > 0.0 else float("nan")
    return out


def extract_accel_features(x, y, z, sample_rate_hz: float) -> dict:
    """振动特征；样本不足或采样率非法时全部返 NaN（CSV 写空字段）。"""
    x = np.asarray(x, dtype=float).ravel()
    y = np.asarray(y, dtype=float).ravel()
    z = np.asarray(z, dtype=float).ravel()
    n = min(x.size, y.size, z.size)
    out: dict[str, float] = {name: float("nan") for name in ACCEL_TIME + ACCEL_SPECTRUM + ACCEL_BANDS}
    if n < 8 or not math.isfinite(sample_rate_hz) or sample_rate_hz <= 0.0:
        return out

    magnitude = np.sqrt(x[:n] ** 2 + y[:n] ** 2 + z[:n] ** 2)
    a = _detrend(magnitude, sample_rate_hz)
    rms = math.sqrt(float(np.mean(a * a)))
    peak = float(np.max(np.abs(a)))
    out["accel_rms"] = rms
    out["accel_peak"] = peak
    out["accel_crest"] = peak / rms if rms > 0.0 else float("nan")
    out["accel_zcr"] = _zero_crossing_rate(a)

    fft_size = accel_fft_size(n)
    power = average_power_spectrum(a, fft_size, fft_size // 2, hann_window(fft_size))
    bin_hz = sample_rate_hz / fft_size
    total_energy = float(np.sum(power))
    peak_index = _strongest_bin(power, 1)
    peak_power = float(power[peak_index]) if peak_index >= 0 else 0.0
    out["accel_peak_hz"] = (_refine_peak(power, peak_index, bin_hz)
                            if peak_index >= 0 and peak_power > 0.0 else float("nan"))
    out["accel_centroid_hz"] = _centroid(power, bin_hz, 1)
    out["accel_flatness"] = _flatness(power, 1)

    nyquist = sample_rate_hz / 2.0
    for (low, high), name in zip(ACCEL_BAND_EDGES, ACCEL_BANDS):
        # Match the app and feature_spec.md: a truncated frequency band is missing.
        if low >= nyquist or high > nyquist or total_energy <= 0.0:
            out[name] = float("nan")
        else:
            out[name] = _band_energy(power, bin_hz, low, high) / total_energy
    return out


def extract_from_files(wav_path: str | Path, accel_csv_path: str | Path | None = None) -> dict:
    """从 WAV（+ 可选加速度 CSV）提取全部 32 个数值特征。"""
    normalized, sample_rate = read_wav(wav_path)
    features = extract_audio_features(normalized, sample_rate)
    if accel_csv_path is not None and Path(accel_csv_path).is_file():
        accel = read_accel_csv(accel_csv_path)
        features.update(extract_accel_features(accel["x"], accel["y"], accel["z"],
                                               accel["sample_rate_hz"]))
    else:
        features.update({name: float("nan") for name in ACCEL_TIME + ACCEL_SPECTRUM + ACCEL_BANDS})
    return features


def format_value(value: float) -> str:
    """特征 CSV 数值格式：%.6f；NaN/Inf 写空字段（数据契约禁止写 nan）。"""
    try:
        number = float(value)
    except (TypeError, ValueError):
        return ""
    if not math.isfinite(number):
        return ""
    return f"{number:.6f}"


def feature_row(features: dict, labels: dict | None = None) -> dict:
    """按数据契约顺序拼一行 features.csv。"""
    row = {name: format_value(features.get(name, float("nan"))) for name in NUMERIC_COLUMNS}
    labels = labels or {}
    for name in LABEL_COLUMNS:
        value = labels.get(name, "")
        row[name] = "" if value is None else str(value)
    return row


def write_features_csv(path: str | Path, row: dict) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_HEADER)
        writer.writeheader()
        writer.writerow(row)
    return path


# ---------------------------------------------------------------- 自检

def _self_test() -> int:
    """合成 1 kHz 正弦自检（feature_spec 第 4.8 条）：峰值/频带是否符合预期。"""
    sample_rate = 44100
    t = np.arange(sample_rate, dtype=float) / sample_rate
    signal = 0.5 * np.sin(2 * math.pi * 1000.0 * t)
    features = extract_audio_features(signal, sample_rate)
    peak = features["audio_peak_hz"]
    top_band = max(AUDIO_BANDS, key=lambda name: features[name])
    print(f"合成 1 kHz 正弦: audio_peak_hz = {peak:.3f} Hz, 主导频带 = {top_band}")
    ok = abs(peak - 1000.0) <= 2.0 and top_band == "band_1k_2k"
    print("自检:", "通过" if ok else "失败")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(_self_test())
