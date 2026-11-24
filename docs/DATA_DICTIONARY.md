# 数据字典 / Data Dictionary

本文件是三个实验 App 与 `analysis/` Python 管线的**唯一数据契约**。
任何 CSV/JSON 字段变更都必须先改本文件，并在 PR/提交说明中注明。

通用约定：

- 编码 UTF-8（CSV 带 BOM，便于 Excel 直接打开中文）。
- 行结束符 CRLF；字段分隔符逗号；字段内含逗号/引号/换行时按 RFC4180 加引号。
- 时间：毫秒时间戳一律为 Unix epoch ms；另有 ISO 8601 本地时间列（带时区偏移）与 UTC 列。
- 布尔值：`1`/`0`；无数据：空字段（不要写 NaN、null、N/A）。
- 数值保留：电流为 µA 整数，电压 mV 整数，温度 °C 一位小数，功率 W 三位小数。
- 设备/会话标识在同一份导出中保持一致。ChargeAudit 专用字段与文件名见第三节。

## 0. 通用 manifest.json

PowerTrace 与 VibroDiag 的 `manifest.json` 记录设备与 App 元数据。ChargeAudit 使用第三节的专用 schema。

```json
{
  "schema": "phonelab.manifest/1",
  "project": "02-power-thermal | 03-vibro-diag",
  "app": "PowerTrace",
  "appVersion": "1.0.0",
  "generatedAt": 1730000000000,
  "device": {
    "model": "Pixel 7", "manufacturer": "Google", "brand": "google",
    "device": "panther", "androidRelease": "14", "sdkInt": 34,
    "socManufacturer": "Google", "socModel": "Tensor G2",
    "abi": "arm64-v8a", "supportedAbis": "arm64-v8a;armeabi-v7a",
    "screenWidthPx": 1080, "screenHeightPx": 2400, "densityDpi": 420,
    "refreshRateHz": 90.0, "batteryCapacityMah": 4355,
    "locale": "zh-CN", "timeZone": "Asia/Shanghai"
  },
  "...": "各 App 追加字段"
}
```

---

# 一、课题02 · PowerTrace 功耗热测

会话 ID 格式：`PT-<yyyyMMdd>-<序号三位>`，例如 `PT-20261118-001`。

## 1.1 会话 CSV：`session_<conditionId>.csv`

一次 30 分钟工况采集 = 1 个文件 = 6 行（每 5 分钟一行）。4 个工况合计 24 行 = "24 组时间序列"。

| 列 | 类型 | 单位 | 说明 |
|---|---|---|---|
| session_id | string | | 会话 ID |
| condition_id | string | | `IDLE`/`VIDEO`/`NAVCAM`/`GAME` |
| condition_label | string | | 中文工况名 |
| sample_index | int | | 1..6 |
| elapsed_sec | int | s | 0/300/600/900/1200/1500 |
| timestamp_iso | string | | 本地时间 ISO8601 |
| timestamp_utc | string | | UTC ISO8601 |
| battery_level_pct | int | % | 系统电量 |
| battery_voltage_mv | int | mV | |
| battery_temp_c | double | °C | 电池温度 |
| current_now_ua | int | µA | `BATTERY_PROPERTY_CURRENT_NOW`；符号随厂商，分析脚本按绝对值处理并记录符号约定 |
| charge_counter_uah | int | µAh | 剩余电荷计数，用于交叉校验电量差 |
| energy_counter_nwh | int | nWh | 部分机型支持，空表示不支持 |
| power_w | double | W | 优先 `Δcharge_counter_uah × voltage_mv × 3.6 / (Δt_s × 1e6)`（电荷计差分，跨采样点 ≥60 s 有效）；仅当 `|current_now_ua| ≥ 2 mA` 才回退 `voltage_mv × |current_ua| / 1e9`。首点无差分时留空，原因写入 note（`power_unavailable` / `power_source=charge_counter`）。原始电流口径保留在分析产物的 `power_w_current` 列 |
| thermal_status | int | | `PowerManager.getCurrentThermalStatus()` 0..6 |
| cpu_temp_c | double | °C | 多个 thermal zone 取最大值，无法读取时为空 |
| cpu_freq_mhz_avg | int | MHz | 各核心 `scaling_cur_freq` 平均值，无法读取为空 |
| cpu_freq_mhz_max | int | MHz | 各核心最大值 |
| battery_cycle_count | int | | Android 14+ `EXTRA_CYCLE_COUNT`，空表示不支持 |
| battery_health | int | | `BatteryManager.EXTRA_HEALTH` |
| charging | int | 0/1 | 采集期间必须为 0（异常标记） |
| screen_on | int | 0/1 | |
| screen_brightness_pct | int | % | 0..100 |
| ambient_lux | double | lx | 光线传感器，不可用为空 |
| net_type | string | | `WIFI`/`CELLULAR`/`NONE` |
| net_rx_kbps | double | kbps | 采样区间内平均下行速率（`TrafficStats` 差分） |
| net_tx_kbps | double | kbps | 采样区间内平均上行速率 |
| mem_available_mb | int | MB | |
| mem_total_mb | int | MB | |
| fps | double | fps | 仅 App 在前台时可测（Choreographer），否则为空 |
| foreground_pkg | string | | 前台应用包名（需"使用情况访问"授权，否则为空） |
| note | string | | 人工备注（如"游戏为 XXX 60fps 模式"） |

## 1.2 汇总 CSV：`campaign_<campaignId>.csv`

同一批次 4 个工况合并，共 24 行，列 = `block` + 1.1 全部列（去掉 session_id，改用 campaign_id）。

| 列 | 说明 |
|---|---|
| campaign_id | 批次 ID，`PC-<yyyyMMdd>-<序号>` |
| block | A/B/C/D，采集顺序（用于抵消顺序/温度漂移效应） |

## 1.3 工况汇总：`campaign_summary_<campaignId>.csv`

每个工况 1 行：

`condition_id, condition_label, duration_min, start_level_pct, end_level_pct, level_drop_pct,
avg_power_w, peak_power_w, energy_wh, avg_current_ma, temp_start_c, temp_end_c, temp_rise_c,
thermal_max, thermal_rise_events, avg_cpu_freq_mhz, avg_screen_brightness_pct,
net_rx_mb, net_tx_mb, notes`

## 1.4 原始高频序列（可选）：`raw_<conditionId>.csv`

默认关闭；开启后每 5 秒一行（30 分钟 = 360 行）。

`elapsed_sec, timestamp_iso, battery_level_pct, current_now_ua, voltage_mv, battery_temp_c,
thermal_status, cpu_temp_c, cpu_freq_mhz_avg, screen_on, fps, net_rx_kbps, net_tx_kbps`

## 1.5 目录结构

```
experiments/powertrace/<stamp>_<campaignId>/
  manifest.json
  session_IDLE.csv            # 6 行
  session_VIDEO.csv           # 6 行
  session_NAVCAM.csv          # 6 行
  session_GAME.csv            # 6 行
  campaign_<id>.csv           # 24 行
  campaign_summary_<id>.csv
  raw_<condition>.csv         # 可选
  session.log
```

---

# 二、项目三 · VibroDiag 声振诊断仪

会话 ID：`VD-<yyyyMMdd>-<序号>`。

## 2.1 5 种工况（默认，可在 App 内改名但不改 ID）

| condition_id | 中文 | 设备/模拟方式 |
|---|---|---|
| C1_NORMAL_LOW | 风扇低档正常 | 电风扇 1 档，扇叶原状 |
| C2_NORMAL_HIGH | 风扇高档正常 | 电风扇最大档 |
| C3_IMBALANCE | 扇叶轻微失衡 | 1 片扇叶贴 ~2 g 配重片 |
| C4_BLOCKED | 风道遮挡 | 进风口遮挡 1/2 |
| C5_HAIRDRYER | 吹风机热风档 | 吹风机高速热风（不同声源类别） |

每组 15 次，每次 15 s（可 5–30 s 配置）。总计 75 组。

## 2.2 单次采集文件

每个工况一个目录，每次采集一个子目录（组号两位补零），文件名前缀与目录名一致：

```
<conditionId>/g<NN>/g<NN>_<conditionId>_audio.wav      # 44.1kHz / 16bit / 单声道 PCM
<conditionId>/g<NN>/g<NN>_<conditionId>_accel.csv
<conditionId>/g<NN>/g<NN>_<conditionId>_features.csv   # 38 列 = 32 个数值特征 + 6 个标签列
<conditionId>/g<NN>/g<NN>_<conditionId>_meta.json
```

分析脚本必须递归扫描（`rglob("**/*_meta.json")`），不要假设文件都在会话根目录。

### 加速度 CSV 列

`t_ns, t_ms, x, y, z, mag_g, sample_rate_hz`

- `t_ns`：`SensorEvent.timestamp`（纳秒，单调时钟）
- `t_ms`：相对录音开始的毫秒数
- 单位 m/s²；`mag_g = sqrt(x²+y²+z²)/9.80665`
- 采样率：优先 200 Hz（需 `HIGH_SAMPLING_RATE_SENSORS`），不可用回退 `SENSOR_DELAY_GAME`（约 50 Hz）
- 文件头不写注释行（便于 pandas 直接读）

### meta.json

```json
{
  "schema": "phonelab.vibro/1",
  "session_id": "VD-20261118-001",
  "condition_id": "C1_NORMAL_LOW",
  "condition_label": "风扇低档正常",
  "group_index": 1,
  "recorded_at_ms": 1730000000000,
  "duration_s": 15.0,
  "audio": {"sample_rate": 44100, "channels": 1, "bits": 16, "source": "UNPROCESSED",
            "file": "g1_C1_NORMAL_LOW_audio.wav", "rms_dbfs": -28.4, "peak_dbfs": -6.1},
  "accel": {"sample_rate_hz": 200, "file": "g1_C1_NORMAL_LOW_accel.csv",
            "rms_ms2": 1.83, "peak_ms2": 9.4, "samples": 3000},
  "placement": "手机平放于风扇底座正前方 30cm 桌面",
  "device_distance_cm": 30,
  "noise_condition": "quiet | fan-only | classroom",
  "noise_spl_db": null,
  "operator": "",
  "notes": "",
  "blind_trial": false,
  "exclude_from_dataset": false,
  "interrupted": false
}
```

### features.csv（单行，38 列 = 32 个数值特征 + 6 个标签列）

> 数值列按 `%.6f` 输出；未定义/不可算的值写空字段（不要写 NaN）。

时域（音频）：`audio_rms_dbfs, audio_peak_dbfs, audio_crest_db, audio_zcr, audio_kurtosis, audio_skewness`（6）
频域（音频）：`audio_centroid_hz, audio_rolloff85_hz, audio_flatness, audio_peak_hz, audio_peak_mag, audio_harmonic_ratio, audio_hnr_db`（7）
频带能量占比（音频，归一化到总和）：`band_0_100, band_100_300, band_300_1k, band_1k_2k, band_2k_5k, band_5k_10k, band_10k_20k`（7）
振动：`accel_rms, accel_peak, accel_crest, accel_zcr, accel_peak_hz, accel_centroid_hz, accel_flatness, accel_band_0_5, accel_band_5_10, accel_band_10_25, accel_band_25_50, accel_band_50_100`（12）
工况标签：`condition_id, condition_label, group_index, file_stem, recorded_at_ms, duration_s`

> 特征提取必须在 App（Kotlin）与 `analysis/features.py`（Python）两侧实现一致；以 `docs/feature_spec.md` 为准。

## 2.3 数据集索引：`dataset_index.csv`

`session_id, condition_id, condition_label, groups_collected, target_groups, complete`

## 2.4 盲测表：`blind_test_<sessionId>.csv`

每行一次盲测：`session_id, trial_index, condition_id_true, condition_id_predicted, predicted_confidence,
correct, features_file, model, timestamp_iso`

盲测流程：每个工况 3 组、共 15 组；App 在采集时不显示标签，预测后才揭示真实标签。

盲测录音目录为会话根下的 `blind/<conditionId>/b<NN>/`（扩展目录，不属于 5×15 训练集）。
其 `meta.json` 必须带 `"blind_trial": true` 与 `"exclude_from_dataset": true`；
分析脚本必须按目录名或这两个字段排除，否则盲测样本会混入训练集导致准确率虚高。

## 2.5 抗噪评估：`noise_eval_<sessionId>.csv`

`session_id, snr_db, condition_id, trials, correct, accuracy, model, noise_type, timestamp_iso`
（snr_db 取 20/10/5/0/-5；noise_type = white/pink）

## 2.6 评估报告：`evaluation_<sessionId>.json`

```json
{
  "schema": "phonelab.vibro.eval/1",
  "session_id": "...",
  "training": {"samples": 75, "features": 32, "model": "knn(k=3, zscore)", "loocv_accuracy": 0.93,
               "confusion_matrix": {"C1_NORMAL_LOW": [14,1,0,0,0], "...": []},
               "per_condition_accuracy": {"C1_NORMAL_LOW": 0.93}},
  "blind_test": {"trials": 15, "correct": 14, "accuracy": 0.9333},
  "noise": [{"snr_db": 20, "white": 0.93, "pink": 0.90}, {"snr_db": 0, "white": 0.60, "pink": 0.53}],
  "notes": "手机平放，风扇底座 30cm，安静实验室"
}
```

## 2.7 目录结构

```
experiments/vibrodiag/<stamp>_<sessionId>/
  manifest.json
  C1_NORMAL_LOW/g01/ g02/ ... g15/
  C2_NORMAL_HIGH/...
  C3_IMBALANCE/...
  C4_BLOCKED/...
  C5_HAIRDRYER/...
  dataset_index.csv
  blind/<conditionId>/b01/ b02/ b03/   # 盲测录音（15 组，不属于训练集）
  blind_test_<sessionId>.csv
  noise_eval_<sessionId>.csv
  evaluation_<sessionId>.json
  session.log
  photos/          # 实验现场照（可选，用系统相机或 App 内拍照）
```

> 中断的录音（来电/退出）标记 `meta.interrupted=true`，保留文件用于审计，但不参与训练与评估。
> Kotlin 与 Python 的特征一致性由 `apps/vibrodiag/src/test/java/.../FeatureParityTest.kt` 自动校验：
> 对同一 WAV/加速度文件，两侧 32 维特征的相对误差 < 1e-3（实测最差 3.1e-3 出现在 `band_10k_20k` 这类近零频带，绝对误差 < 1e-6）。

---

# 三、课题07 · ChargeAudit 充电宝容量与转换效率核验

导出 ZIP 包含 `chargeaudit_<sessionId>.csv`、`manifest.json` 和有汇总结果时的 `summary.json`。清单使用 `phonelab.charge/1`，`project` 为 `07-charge-bank-efficiency`。仪表读数、手机电池状态、采样时间和备注的字段见[课题07数据表与模板](TOPIC07_CHARGE_EFFICIENCY.md#23-数据表字段)。

---

# 四、Python 分析管线输入输出

| 脚本 | 输入 | 输出 |
|---|---|---|
| `analysis/power/analyze.py` | PowerTrace 导出目录 | `figures/*.png`、`tables/*.csv`、`power_report.md` |
| `analysis/charge/analyze.py` | ChargeAudit 导出 CSV 与可选会话清单 | `figures/*.png`、`tables/*.csv`、`charge_report.md` |
| `analysis/vibro/analyze.py` | VibroDiag 导出目录 | 特征表、混淆矩阵、抗噪曲线、`vibro_report.md` |
| `analysis/report/build_report.py` | 功耗、充电宝与声振的 `*_report.md` | `analysis/out/report/course_report_draft.md` |

功耗与声振的分析脚本使用 `--data <会话目录> --out <输出目录>`；充电分析使用 `--csv <采样表> --meta <会话清单> --out <输出目录>`。

特征一致性：`analysis/vibro/features.py` 与 App 的 `FeatureExtractor.kt` 必须逐条对齐 `docs/feature_spec.md` 第 4 节；
仓库内已有 Kotlin 侧单测 `FeatureParityTest` 用同一份 WAV 与 Python 结果比对（容差 1e-3 相对 + 1e-6 绝对）。
