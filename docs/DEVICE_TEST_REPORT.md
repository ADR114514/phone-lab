# 真机联调报告 / Device Test Report

> 验证设备：**OnePlus 12（PJD110），Android 16（SDK 36）**，arm64-v8a，1440×3168，电池 78%，
> 已连接 5 GHz Wi-Fi。测试时间：2026-11-18 00:44–01:12。当天的联调记录涵盖 PowerTrace 与 VibroDiag。课题07另见[充电宝实测归档](../01-Field-Discovery/07-chargeaudit/)。
> 测试方式：APK 通过 adb 安装，运行时权限通过系统弹窗授予（该机型 `adb install -g` 与 `pm grant` 受限，
> 必须在 App 内点授权），关键流程用 adb 驱动 UI 并抓取界面截图与 App 导出文件。

## 1. 测试矩阵

| App | 启动 | 权限引导 | 核心流程 | 数据落盘 | 导出 | 评价 |
|---|---|---|---|---|---|---|
| PowerTrace | ✅ cold start 651 ms | ✅ 通知/电池豁免/精确闹钟逐项引导 | ✅ 批次向导 → 启动前台服务 → 首点采样 → 中止批次 | ✅ session CSV 32 列 + manifest + session.log | 待测（结果页导出） | 通过 |
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

### 2.2 VibroDiag（C1 风扇低档 / C2 风扇高档各 1 组，15 s）

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
| 3 | 该机型 `adb install -g` 不授予运行时权限、`pm grant` 被系统拒绝 | OnePlus ColorOS 权限策略 | App 内权限引导路径验证可用（通知与麦克风通过系统弹窗授权） |

修复后，功耗与声振演示数据的分析回归均通过。

## 3.1 待机采样调度问题与修复（重要）

第一次熄屏待机测试（PC-20261118-002）只拿到 1 个采样点：预期 01:19:28 的第 2 点直到 01:35 仍未写入。
排查过程与证据：

1. `dumpsys alarm` 显示我们的 `SAMPLE_ALARM` 是 **ELAPSED_WAKEUP 且 windowLength≈224 s**，并被系统加上
   `policy adjustment=+2d23h52m`（App Standby / battery_saver 批量延后）——即系统把"精确闹钟"当非精确闹钟批量延迟；
2. App 侧 `session.log` 记录 `exactAlarm=true`，说明系统 API 报有权限，但 ROM 策略仍会延后；
3. 进程、前台服务、Doze 白名单（`dumpsys deviceidle whitelist` 含本 App）均正常，排除被杀；
4. 重新启动 App 后，控制器会把到期点补采并标 `late_sample`（设计如此），说明数据链路正常，只是唤醒源不可靠。

已实现的修复（`SamplingScheduler` / `SamplingService`）：

- 有精确闹钟权限时改用 `AlarmManager.setAlarmClock()`（用户可见闹钟通道，OEM 节流更少）；
  失败时退回 `setExactAndAllowWhileIdle`；
- **待机工况准点守护**：IDLE 工况期间持有 `PARTIAL_WAKE_LOCK`（上限 40 min），保证 Handler 准点触发；
  切换到其它工况/暂停/结束立即释放；
- 代价与诚实说明：IDLE 工况的功耗会包含 CPU 驻留开销（数十 mW 量级），报告中必须注明；
  建议用"开/关守护"各跑一次同批次，做偏差对照。

> 该修复的最后一轮真机复测因手机锁屏（需要机主输入密码）未能完成，已在 `docs/VERIFICATION.md` 中标注为"待复测"。

### 3.3 真机不完整批次已可用分析工具体检

把手机上 3 个测试会话（PC-20261118-001/002/003）拉到电脑后，用新增的
`analysis/power/summarize_partial.py` 对 PC-20261118-002 体检，输出：
已采 IDLE 3/6 点、late_sample 2 个、charging 3 个；并明确提示"缺 VIDEO/NAVCAM/GAME，需补采后跑正式分析"。
说明"边采边看"的数据链路可用，且工具对充电/迟到样本给出可执行建议（拔线重采、关闭系统睡眠待机优化）。

## 4. 当轮联调待补的完整实验

| 项 | 说明 |
|---|---|
| 完整 24 组功耗批次 | 需拔线、按 SOP 连续跑 4 工况 × 30 min（本次仅验证首点与中止）；待机准点修复待解锁手机复测 |
| 完整 5×15 声振数据 | 本次为 2 工况各 1 组链路验证；需真实风扇/吹风机 5 工况 × 15 组 |
| 盲测 15 组与录屏 | 需完整数据集后按 App 内盲测流程执行并录屏 |

## 5. 截图

| 界面 | 文件 |
|---|---|
| PowerTrace 首页 | `docs/screenshots/powertrace-home.png` |
| PowerTrace 采集中（真实指标） | `docs/screenshots/powertrace-running.png` |
| VibroDiag 本组特征摘要 | `docs/screenshots/vibrodiag-features.png` |
| VibroDiag 抗噪评估 | `docs/screenshots/vibrodiag-noise-eval.png` |

### 3.2 修复后的观察（设备锁屏状态下，2026-11-18 01:37–01:43）

- 重新安装带守护的 APK 并启动 App 后，控制器补采了样本 2（elapsed=300 s，实际写入 01:37:08，
  标注 `late_sample(+150s)`），并成功获取 `PowerTrace:standby-guard` 部分唤醒锁；
- 设备锁屏后的 6 分钟内，`dumpsys power` 能看到守护锁被系统反复 ACQ/REL（01:37:08 ACQ → 01:37:50 REL →
  01:37:52 ACQ → 01:38:30 REL），进程未 frozen、前台服务仍在、`dumpsys alarm` 中 `setAlarmClock` 闹钟
  windowLength=0 且已过期，但下一个 5 min 采样点（01:39:38）在 4 分钟后仍未写入；
- 结论：**ColorOS 的"睡眠待机优化/应用冻结"会在深睡阶段接管唤醒与唤醒锁**，仅靠应用侧
  `setAlarmClock`+wakelock 不能 100% 保证准点。需要用户在系统设置中配合：
  设置 → 电池 → 更多 → **关闭"睡眠待机优化"**；应用详情 → 耗电管理 → **允许后台活动/自启动**；
  必要时在开发者选项里保持"充电时不锁定屏幕"仅用于调试。
- 这进一步说明 SOP 中"实验前先跑 10 分钟试采，确认前 3 个点准点后再开始 30 min 正式批次"的必要性；
  App 已对迟到点标注 `late_sample`，分析报告会统计迟到/缺失并给出剔除建议，不会伪造数据。

### 3.3 待机准点复测（设备解锁后，2026-11-18 11:16–11:24）

- 11:16:48 开始新批次 PC-20261118-004（IDLE），屏幕熄灭（`mWakefulness=Dozing`）；
- `PowerTrace:standby-guard` 唤醒锁自 11:16:48 获取后持续持有（无 REL 记录），`/proc/uptime` 持续增长，
  说明 CPU 未挂起；前台服务存活、进程未冻结；
- `dumpsys alarm`：`setAlarmClock` 闹钟 `RTC_WAKEUP ... windowLength=0`，到期时间 11:21:48；
- 11:23:58（+130 s）第 2 个采样点仍未写入，闹钟仍 pending → **ColorOS 在屏幕熄灭状态下会整体延迟闹钟投递，
  即使 CPU 唤醒、闹钟精确且应用持有唤醒锁**。

**结论**：本机型上"应用侧方案无法保证熄屏 5 min 准点"，必须由用户在系统设置中关闭"睡眠待机优化"并允许应用后台活动；
因此 App 的守护锁改为**设置页可选开关（默认关闭）**，避免在能正常唤醒的机型上引入不必要功耗偏差。
若系统设置调整后仍迟到，再开启守护锁并在报告中说明 CPU 驻留开销。


### 3.4 强制权限门禁真机验证（2026-11-18 12:12）

- 新构建安装后点“新建批次”进入《开始前强制检查》，3 项系统权限显示“已完成”，2 项厂商设置需勾选；
- 勾选“后台活动/自启动”“睡眠待机优化已关闭”后，按钮变为“通过检查，去配置批次”，点击进入批次配置页；
- 期间发现并修复：通过检查后因 route 仍为 WIZARD、无状态刷新导致界面停留在检查页；修复为通过时 accessTick++ 强制重组（提交 feat(powertrace): preflight gate）。
- 截图：docs/screenshots/powertrace-preflight.png（检查页）。

### 端到端复验（12:22，新构建 fea65977）

清空 App 数据后：新建批次 → 强制检查页（两个厂商勾选框均未勾选，无法开始）→ 勾选两项 → 点“通过检查，去配置批次”
→ 正常进入“配置实验参数”页（备注/工况顺序/开始采集 120 min），门禁链路打通。
