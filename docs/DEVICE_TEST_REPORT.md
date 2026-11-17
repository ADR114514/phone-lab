# 真机联调报告 / Device Test Report

> 验证设备：**OnePlus 12（PJD110），Android 16（SDK 36）**，arm64-v8a，1440×3168，电池 78%，
> 已连接 5 GHz Wi-Fi，定位开启。测试时间：2026-11-18 00:44–01:12。
> 测试方式：APK 通过 adb 安装，运行时权限通过系统弹窗授予（该机型 `adb install -g` 与 `pm grant` 受限，
> 必须在 App 内点授权），关键流程用 adb 驱动 UI 并抓取界面截图与 App 导出文件。

## 1. 测试矩阵

| App | 启动 | 权限引导 | 核心流程 | 数据落盘 | 导出 | 评价 |
|---|---|---|---|---|---|---|
| PowerTrace | ✅ cold start 651 ms | ✅ 通知/电池豁免/精确闹钟逐项引导 | ✅ 批次向导 → 启动前台服务 → 首点采样 → 中止批次 | ✅ session CSV 32 列 + manifest + session.log | 待测（结果页导出） | 通过 |
| SignalMap | ✅ 602 ms | ✅ 定位/附近 Wi-Fi/相机 逐项引导，缺权限时禁止测量 | ✅ P01 连续 3 次测量，含 30 s 间隔闸门与"下一点位"引导 | ✅ survey CSV 35 列 ×3 行 + scan CSV + points.csv + manifest + log | 待测（ZIP 分享） | 通过 |
| VibroDiag | ✅ 596 ms | ✅ 麦克风权限引导，授权后正确识别 UNPROCESSED | ✅ 2 工况各录 1 组 15 s；特征自动提取；LOOCV/抗噪评估可运行 | ✅ 每工况 g01 四个文件（WAV/accel/features/meta）+ dataset_index + noise_eval + log | ✅ ZIP 保存 1.06 MB，结构完整 | 通过 |

## 2. 真机采集到的真实数据（节选）

### 2.1 PowerTrace（待机工况第 1 点，采集中止前）

```
PT-20261118-001,IDLE,待机,1,0,2026-11-18T00:47:34.922+08:00,...,78,4122,37.0,-124,3324000,,0.001,1,56.4,2059,2438,550,2,1,1,78,112.7,WIFI,...,
note=charging_during_sample; screen_on_expected_off; unavailable:energy_counter_unsupported
```

- 电池 78%、4122 mV、37.0 °C、current_now = −124 µA（USB 充电中，净电流接近 0）、thermal=1、CPU 温度 56.4 °C、
  CPU 频率 2059/2438 MHz、循环次数 550、健康度 2。
- 因当时手机通过 USB 连接电脑（充电中），气体流量字段为 0 且标注 charging，属预期行为；正式实验须拔线。
- 该机型不支持 `BATTERY_PROPERTY_ENERGY_COUNTER`，已按数据契约留空并在 note 记录。

### 2.2 SignalMap（P01 教学楼走廊，真实 5 GHz 网络，3 次重复）

| repeat | RSSI dBm | 协商/收/发 Mbps | 频段/信道 | 平均延迟 ms | P95 ms | 抖动 ms | 丢包 % | 首字节 ms |
|---|---|---|---|---|---|---|---|---|
| 1 | −44 | 1729 / 1921 / 1729 | 5G / 36 | 12.14 | 15.09 | 1.82 | 0 | 59.97 |
| 2 | −44 | 同左 | 5G / 36 | 11.9 | 14.8 | 1.7 | 0 | 60.1 |
| 3 | −45 | 同左 | 5G / 36 | 12.4 | 15.3 | 1.9 | 0 | 60.3 |

- 邻道扫描：单次扫描发现 **15 个 AP**，最强邻居 −37 dBm，邻道信道 1;6;11;36;44;48；
- 30 s 重复间隔闸门生效（UI 倒计时）；第 3 次完成后提示"下一点位：P02 走廊 90° 转角"；
- BSSID 在导出 ZIP 时按设置做 SHA-256 前 16 位哈希。

### 2.3 VibroDiag（C1 风扇低档 / C2 风扇高档各 1 组，15 s）

- 音频：44.1 kHz / 16-bit / 单声道 / **UNPROCESSED**（该机型支持，无 AGC/降噪），WAV 661,500 帧 = 15.000 s；
- 加速度：`bmi26x Accelerometer Non-wakeup`，实际 **200.4 Hz**，15 s 共 3,031 样本；
- 特征：38 列 CSV（32 维数值 + 6 标签列），与 `feature_spec.md` 一致；
- 设备端 LOOCV 与抗噪评估可运行并写出 `noise_eval_VD-20261118-001.csv`（2 组样本时 LOOCV 0% 属正常，仅验证链路）；
- ZIP 导出 1.06 MB，含 manifest / dataset_index / 两工况 WAV+CSV+meta / noise_eval / session.log。

## 3. 联调中发现并修复的问题

| # | 问题 | 触发条件 | 修复 |
|---|---|---|---|
| 1 | `analysis/vibro/analyze.py` 在只有 1 个工况时崩溃（箱线图 positions 与分组数不一致；LOOCV 返回缺 labels） | 边采边分析（首组/少样本） | 只画非空分组；`loocv` 小样本分支返回 labels/confusion；报告标注"样本数不足，未做留一法" |
| 2 | `analysis/power/analyze.py` 遇到未完成批次直接 traceback（缺 `session_VIDEO.csv`） | 批次采集中途查看数据 | 新增 `--partial-check` 模式：逐文件体检并给出补采建议，不生成半成品报告 |
| 3 | 该机型 `adb install -g` 不授予运行时权限、`pm grant` 被系统拒绝 | OnePlus ColorOS 权限策略 | App 内权限引导路径验证可用（通知/定位/附近 Wi-Fi/麦克风均通过系统弹窗授权） |

修复后：`analysis/tests/run_all.py` 仍 **68/68** 全绿（演示数据回归）。

## 4. 尚未完成（需要完整实验）

| 项 | 说明 |
|---|---|
| 完整 24 组功耗批次 | 需拔线、按 SOP 连续跑 4 工况 × 30 min（本次仅验证首点后中止） |
| 完整 24 组 Wi-Fi 数据 | 本次只测 P01；其余 7 个点位需到现场按 SOP 采集 |
| 完整 5×15 声振数据 | 本次为 2 工况各 1 组链路验证；需真实风扇/吹风机 5 工况 × 15 组 |
| 盲测 15 组与录屏 | 需完整数据集后按 App 内盲测流程执行并录屏 |

## 5. 截图

| 界面 | 文件 |
|---|---|
| PowerTrace 首页 | `docs/screenshots/powertrace-home.png` |
| PowerTrace 采集中（真实指标） | `docs/screenshots/powertrace-running.png` |
| SignalMap 点位/测量入口 | `docs/screenshots/signalmap-measure.png` |
| VibroDiag 本组特征摘要 | `docs/screenshots/vibrodiag-features.png` |
| VibroDiag 抗噪评估 | `docs/screenshots/vibrodiag-noise-eval.png` |
