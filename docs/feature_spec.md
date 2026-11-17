# 声振特征规格 / Feature Specification

App（Kotlin，`apps/vibrodiag/.../features/`）与 Python（`analysis/vibro/features.py`）必须实现同一套定义。
输入：16-bit PCM 单声道 WAV（44.1 kHz）+ 加速度 CSV。所有窗函数为 Hann。

## 1. 音频特征（`audio_*`）

- 先转为 `DoubleArray` 并归一化到 [-1, 1]：`x = pcm / 32768.0`。
- 分帧：`fftSize = 4096`，帧移 `hop = 2048`；最后一帧不足补零。对每帧去直流（减均值）后乘 Hann 窗。
- 功率谱：`P_k = |FFT_k|² / N`，只取 `k = 0..N/2`；多帧取算术平均得到 `P(f)`。
- `audio_rms_dbfs = 20*log10(rms / 32768)`，rms 为整段 PCM 的 RMS；空信号取 -120。
- `audio_peak_dbfs = 20*log10(max|x|)`。
- `audio_crest_db = 20*log10(peak/rms) = peak_dbfs - rms_dbfs`。
- `audio_zcr`：相邻样本符号变化的次数 / 样本数。
- `audio_kurtosis`、`audio_skewness`：整段归一化信号的 4 阶/3 阶标准化矩。
- `audio_centroid_hz = Σ f_k P_k / Σ P_k`（k 从 1 开始，排除直流）。
- `audio_rolloff85_hz`：累计能量达到总能量 85% 的频率。
- `audio_flatness`：`exp(mean(ln(P_k+eps))) / mean(P_k)`，eps=1e-15，k≥1。
- `audio_peak_hz`：P 最大值对应频率，抛物线插值细化到子 bin。
- `audio_harmonic_ratio`：设 f0 = audio_peak_hz，统计 `f ∈ {2f0..6f0}` 各 ±2 bin 内能量之和 / 总能量（0..1）。
- `audio_hnr_db = 10*log10(harmonic_energy / (total - harmonic_energy + eps))`。
- 频带能量占比（`band_*`，分母为 20 Hz–20 kHz 总能量）：
  `0–100, 100–300, 300–1k, 1k–2k, 2k–5k, 5k–10k, 10k–20k` Hz。

## 2. 振动特征（`accel_*`）

- 读取 `x,y,z`（m/s²），计算 `m(t) = sqrt(x²+y²+z²)`；去线性趋势 + 去均值得到 `a(t)`。
- `accel_rms = sqrt(mean(a²))`，`accel_peak = max|a|`，`accel_crest = peak/rms`，`accel_zcr` 同上。
- 频谱：`fftSize = 4096`（若采样数不足则取 ≥N 的最小 2 的幂，上限 4096），Hann 窗，多帧平均。
- `accel_peak_hz`（抛物线插值）、`accel_centroid_hz`、`accel_flatness`。
- 频带能量（绝对均值，m²/s⁴ 量纲无关，归一化到总能量）：`0–5, 5–10, 10–25, 25–50, 50–100` Hz。
- 采样率以 CSV 中 `sample_rate_hz` 为准（200 或 ~50）。

## 3. 模型

- 标准化：对每个特征列做 z-score（均值/标准差来自训练集；标准差为 0 时置 1）。
- 分类器：kNN，k=3，欧氏距离；平票取距离更近者。
- 数据集划分：留一法（LOOCV）评估训练集准确率；盲测集独立评估。
- 抗噪：对测试音频叠加白噪声/粉红噪声到指定 SNR，重新提取特征后分类。
- 参考噪声：安静环境录制的 `noise_reference.wav`（App 提供"录环境噪声"按钮），粉红噪声由白噪声经 1/f 滤波生成。
## 4. 实现约定（Kotlin 与 Python 必须一致，2026-11 定稿）

1. **分帧**：音频帧起点为 0, 2048, 4096, …，只保留 `start < n` 的帧；最后一帧不足 4096 补零。
   每帧先减"帧内均值"，再乘 Hann(4096)。功率谱 `P = |X|² / 4096`，多帧算术平均，取 `k = 0..2048`。
2. **DC 处理**：`audio_peak_hz / audio_centroid_hz / audio_flatness` 等以及频带能量分母均从 `k = 1` 起算（排除直流）。
3. **audio_peak_mag** = `sqrt(max(P))`（与 FFT 幅度谱一致的规约，不含 2× 系数）。
4. **频带分母**：音频 = 20 Hz–20 kHz 能量；振动 = 全谱总能量（0 至 Nyquist）。
5. **谐振**：f0 = audio_peak_hz；谐波取 2f0…6f0，每处取 `round(f/binHz) ± 2` bin（去重）；`harmonic_ratio = h/total`，
   `hnr_db = 10·log10(h / (total - h + 1e-15))`。
6. **振动**：`m = sqrt(x²+y²+z²)`，先去线性趋势再减均值；`fftSize = min(4096, nextPow2(N))`，`hop = fftSize/2`；
   某频带下界 ≥ Nyquist 或上界超过 Nyquist 时该带写空字段。
7. **数值输出**：特征 CSV 统一 `%.6f`；NaN/Inf/不可算写空字段。
8. **验证**：合成 1 kHz 正弦（44.1 kHz，1 s）→ `audio_peak_hz` 应在 1000 ± 2 Hz，`band_1k_2k` 为最大占比带。
   Kotlin 与 Python 对同一 WAV 的特征相对误差 < 1e-3。
