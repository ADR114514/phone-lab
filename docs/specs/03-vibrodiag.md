# 项目三 应用规格 / VibroDiag

包名 `com.mobilelab.vibrodiag`；App 名称"声振诊断仪"；minSdk 31 / targetSdk 36 / arm64-v8a；Compose + core 库（信号处理放在 App 内）。

## 目标

用手机麦克风 + 加速度计采集电风扇/吹风机在 5 种工况下的声振信号，每组 15 次；App 内完成 FFT 特征提取、
kNN 分类、盲测准确率、抗噪评估，并导出可复现的数据集与评估报告。

## 权限（按需动态申请）

`RECORD_AUDIO`、`HIGH_SAMPLING_RATE_SENSORS`（normal，声明即可）、`WAKE_LOCK`；
无需存储权限（App 私有目录 + SAF 导出）。相机可选（现场照可由系统相机拍摄后手工放入 photos/）。

## 页面

1. **首页**：项目说明；5 工况数据集进度（每组 15 次，进度条 + 已完成数）；按钮：开始采集 / 盲测 / 评估与报告 / 数据集 / 导出 / 设置。
   设备能力卡：麦克风采样率、音频源（UNPROCESSED/ MIC）、加速度计名称与最高采样率、是否支持 200 Hz。
2. **采集页**：工况选择（5 个 chip，默认跟随"当前最缺数据"的工况）；组号自动递增（显示 g7/15）；
   采集前提示（手机固定方式、距离、档位、环境安静）；"录制"按钮 → 3-2-1 倒计时 → 15 s 录音（可配 5–30 s）；
   录音中显示实时波形（最近 1 s）+ 音频 RMS dBFS + 加速度幅值；结束自动保存 WAV/CSV/features.json，提取特征并给出"本组特征摘要"；
   "重录本组"（覆盖）与"下一组"；屏幕常亮。
3. **盲测页**：说明盲测规则；每工况自动抽 3 组（共 15 组），采集时不显示工况标签（显示"样本 #k"），
   录制完成后点击"预测"，显示预测结果与置信度，再点击"揭示真实标签"；完成后显示 15 组准确率与混淆矩阵预览；
   写 `blind_test_<session>.csv`；可重置重测。
4. **评估页**：数据集统计（每组样本数）；LOOCV 准确率、逐工况准确率、混淆矩阵（表格）；
   抗噪评估：选择噪声类型（white/pink）与 SNR（20/10/5/0/-5），对全部样本加噪重算准确率，生成表格与折线图；
   "生成评估报告"写 `evaluation_<id>.json`；"导出 ZIP"。
5. **数据集页**：按工况分组列出录音（时长、RMS、峰值频率），可播放（MediaPlayer）、查看 meta、删除（二次确认）。
6. **设置**：录音时长（默认 15 s）、采样率（44.1 kHz 固定）、加速度采样率（200/50 Hz 自动）、
   是否加噪评估默认参数；关于/版本/隐私说明（数据仅本机）。

## 录音实现

- `AudioRecord`：`MediaRecorder.AudioSource.UNPROCESSED`（设备支持时），否则 `MIC`；44.1 kHz、单声道、PCM16；
  缓冲 ≥ 2×minBuffer；录音线程 → `core.signal.WavWriter` 写入；同时 `AudioRecord.read` 的缓冲计算 RMS 用于 UI。
- 加速度：`SensorManager` 注册 `TYPE_ACCELEROMETER`，200 Hz（`samplingPeriodUs=5000`；若 `getMinDelay` > 5000 则用最小可用值并记录实际速率），
  记录 `SensorEvent.timestamp` 与相对毫秒；写 CSV 按数据字典 3.2。
- 录音与传感器同一时刻启动（`SystemClock.elapsedRealtimeNanos()` 基准），写 meta.json。
- 结束：停止线程 → 关闭 WAV（更新头部）→ 写 CSV → 提取特征 → 更新 dataset_index.csv。
- 中断（来电/旋转/返回）要安全收尾，不能留下损坏 WAV；异常写 session.log。
- 屏幕常亮：`FLAG_KEEP_SCREEN_ON`；录音期间禁止返回键退出（确认弹窗）。

## 特征与模型

- 必须严格实现 `docs/feature_spec.md`；特征提取放在 `features/` 包，纯 Kotlin，可单元测试。
- 模型：z-score + kNN(k=3)，训练集 = 当前会话全部已标注样本；LOOCV 评估。
- 盲测：训练集 = 采集模式数据；盲测样本不参与训练；置信度 = 邻居中同类的加权比例。
- 抗噪：用 `WhiteNoise`/`PinkNoise` 生成器按目标 SNR 叠加到盲测/测试音频，重算特征与准确率；
  噪声参考可选用"环境噪声"录制（可选功能，优先级低）。
- 所有模型/特征参数写进 `evaluation_<id>.json` 的 `model` 字段，保证可复现。

## 验收

- 5 工况 × 15 组可完整采集（每组 15 s），文件命名与目录结构符合数据字典 3.7。
- 特征提取与 Python 端在同一 WAV 上结果一致（误差 <1e-3 相对），由 `analysis/vibro/tests` 交叉验证。
- 盲测 15 组完成后写出 CSV，准确率显示；抗噪表可生成；ZIP 导出包含全部数据与 evaluation JSON。
- 单元测试：FFT 峰值、Hann 窗、特征（合成正弦：峰值频率/频带占比）、kNN 可分数据准确率、WAV 头长度。

## 写入范围

只改 `apps/vibrodiag/**`。不要改 `core/`、`gradle/`、根构建文件、其他 App。需要 core/依赖变更时先告知 lead。
