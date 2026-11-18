# 验证报告 / Verification Report

> 生成于 2026-11-18，本机 Windows 11 + JDK 21 + Android SDK 36。
> 所有命令均可在仓库根直接复现；不依赖真机（真机项见第 7 节）。

## 0. 环境

| 项 | 值 |
|---|---|
| JDK | BellSoft Liberica 21.0.8 LTS |
| Gradle | 8.14.5（wrapper，Gradle 缓存离线可用） |
| AGP / Kotlin | 8.13.2 / 2.1.21 |
| compileSdk / buildTools / targetSdk / minSdk | 36 / 36.0.0 / 36 / **31 (Android 12)** |
| ABI | **arm64-v8a only**（无 32 位原生库） |
| 设备 | 无真机/模拟器（`adb devices` 为空，未安装 emulator/system-image） |
| Python（分析管线） | 3.14 / numpy 2.5.4 / pandas 3.1.1 / matplotlib 3.11.2 |

## 1. 构建（clean 后重跑）

```
./gradlew clean
./gradlew :apps:powertrace:assembleDebug :apps:signalmap:assembleDebug :apps:vibrodiag:assembleDebug
```

结果：**BUILD SUCCESSFUL**，产出三个 debug APK（25.1 / 29.7 / 25.2 MB）。

## 2. 单元测试（JVM，148 项）

```
./gradlew testDebugUnitTest
```

| 模块 | 用例数 | 覆盖 |
|---|---|---|
| `core` | 22 | CSV 转义/行数、JSON、FFT 峰值/频带/平坦度、WAV 头与长度、统计、时间格式 |
| `apps/powertrace` | 31 | 功率/能量积分、CSV 契约 32/33/20/13 列、批次状态机（暂停/迟到/恢复）、状态编解码 |
| `apps/signalmap` | 55 | RTT 统计（含全失败/部分失败）、35 列契约与 24 行、进度状态机与崩溃重建、BSSID 哈希、频率信道、CSV 解析 |
| `apps/vibrodiag` | 40 | 特征提取（合成 1 kHz 正弦）、kNN/LOOCV、白/粉红噪声与 SNR 校准、WAV I/O、评估 JSON、**Kotlin↔Python 特征一致性** |
| **合计** | **148** | 全部通过，0 失败 |

## 3. Android Lint

```
./gradlew lintDebug
```

| App | errors | warnings |
|---|---|---|
| powertrace | 0 | 0 |
| signalmap | 0 | 0 |
| vibrodiag | 0 | 0 |

修复内容：通知 / Wi‑Fi 扫描缓存 / AudioRecord 三类权限受限调用均改为**调用前显式 checkSelfPermission + 异常兜底**；
备份规则、KTX 写法、冗余 SDK 判断等 warning 全部清零；arm64-only、固定竖屏、高采样率传感器等**有意的取舍**在
`build.gradle.kts` / `AndroidManifest.xml` 中带注释说明并定点豁免。

## 4. APK 校验（aapt2 + apksigner）

```
powershell -File tools/verify-apk.ps1
```

| APK | 包名 | minSdk/target | ABI | 权限数 | 签名 | SHA-256 |
|---|---|---|---|---|---|---|
| PowerTrace-1.0.0-arm64-v8a-debug.apk | `com.mobilelab.powertrace` | 31 / 36 | arm64-v8a | 9 | v2+ OK | `740e6047…5cdebf` |
| SignalMap-1.0.0-arm64-v8a-debug.apk | `com.mobilelab.signalmap` | 31 / 36 | arm64-v8a | 9 | v2+ OK | `8e33c18f…e7fbca` |
| VibroDiag-1.0.0-arm64-v8a-debug.apk | `com.mobilelab.vibrodiag` | 31 / 36 | arm64-v8a | 4 | v2+ OK | `0660d3c1…a9dca` |

完整校验和见 `dist/SHA256SUMS.txt`。三个 App 的权限清单与各自 spec 一一对应（见 APK badging 输出）。

## 5. Python 分析管线（合成演示数据）

```
python analysis/tests/run_all.py
```

结果：**68/68 断言通过**（`--regenerate` 为 70/70），三条管线端到端产出：

| 管线 | 产物 | 关键结果（synthetic） |
|---|---|---|
| `analysis/power` | 6 图 7 表 + `power_report.md` | 平均功率 0.60/2.57/4.73/5.91 W；归因 R²=0.956；GAME 工况 thermal 2→4 且降频（热节流） |
| `analysis/wifi` | 6 图 9 表 + `wifi_report.md` | 路径损耗 n=1.97；材质附加衰减 混凝土 18.5 / 金属 24.7 / 玻璃 5.0 / 混合 14.5 / 无墙体 0 dB；盲区分级 强1/中2/弱3/盲区2 |
| `analysis/vibro` | 5 图 7 表 + `vibro_report.md` | 32 维特征；LOOCV 86.7%；盲测 93.3%；抗噪 20→−5 dB 准确率曲线（含谱减法去噪对比） |
| `analysis/report` | `course_report_draft.md`（约 2.2 万字符） | 合并三份子报告 + 复现步骤 |

> `analysis/out/` 中当前结果是 **data/demo 合成数据**的产物，报告内均标注 `SYNTHETIC`；真机数据重跑后覆盖即可。

## 6. 跨语言特征一致性（Kotlin ↔ Python）

`apps/vibrodiag/src/test/java/com/mobilelab/vibrodiag/FeatureParityTest.kt` 用同一份 WAV + 加速度 CSV
对比 Kotlin `FeaturePipeline` 与 Python `analysis/vibro/features.py` 的 32 维特征：

- 容差：相对误差 < 1e-3（近零频带另有 1e-6 绝对误差下限，覆盖 CSV 6 位小数舍入）；
- 实测：**全部通过**，最差相对误差 3.1e-3 出现在 `band_10k_20k`（该值量级 1e-4，绝对误差 < 1e-6）。

## 6.5 真机验证（补充，2026-11-18）

在 OnePlus 12（Android 16, SDK 36, arm64-v8a）上完成了三 App 的安装、权限引导、核心采集流程、数据落盘与 ZIP 导出：
三个 App 均正常启动无崩溃；SignalMap 实测 P01 三次重复（RSSI −44/−44/−45 dBm、延迟 12 ms、15 个邻道 AP）；
VibroDiag 录制 2 工况各 1 组（44.1 kHz UNPROCESSED WAV + 200.4 Hz 加速度、32 维特征、评估与抗噪可运行、ZIP 1.06 MB）；
PowerTrace 前台服务启动并写入首点后中止批次。过程中修复了分析管线在"少样本/不完整批次"下的两个鲁棒性问题。
详见 [`DEVICE_TEST_REPORT.md`](DEVICE_TEST_REPORT.md)。

**待复测**：真机发现 ColorOS 会把 `setExactAndAllowWhileIdle` 批量延后（`dumpsys alarm` 可见 +2 天 policy adjustment），
已改为 `setAlarmClock` + IDLE 工况守护 `PARTIAL_WAKE_LOCK`；最后一轮 5 min 准点复测因设备锁屏未能完成，
解锁后可复测（预期第 2 个采样点出现在 300 s ± 90 s 内）。

补充观察（锁屏状态下，无法解锁继续验证）：守护锁可被正常获取，但 ColorOS 深睡阶段会反复释放唤醒锁并延迟闹钟，
样本 2 被补采且标记 `late_sample(+150s)`，下一个点仍未准点。因此**待机准点依赖用户关闭系统"睡眠待机优化"并允许应用后台活动**，
SOP 已加入"先试采 10 分钟确认前 3 点"的门槛。

## 7. 必须由真机完成的验证（本机无设备）

| 项 | 为什么需要真机 | 对应 SOP 检查点 |
|---|---|---|
| 息屏 30 min 仍采满 6 点 | Doze/厂商 ROM 的后台策略无法在 JVM 模拟 | `EXPERIMENT_SOP` 课题02 §2（真机首点已验证，长时待验） |
| RSSI/扫描值真实性、定位开关行为 | Android 12+ 权限与节流行为依赖系统 | 课题03 §3（P01 已真机验证） |
| AudioRecord UNPROCESSED 可用性、实际加速度采样率 | 取决于机型音频 HAL 与传感器 | 项目三 §1（已真机验证：UNPROCESSED / 200.4 Hz） |
| CameraX 拍照/旋转、ZIP 分享 | 依赖相机与系统分享面板 | 课题03 §1 |
| 权限拒绝后的降级路径 | 需要真实用户交互 | 各 App 首启引导 |

APK 已具备安装条件；按 `docs/USER_GUIDE.md` 安装后逐条跑 SOP 即可。若真机验证发现问题，
优先看 `session.log`（每个会话目录内）定位。

## 8. 全新克隆验证（自包含性）

```bash
git clone <repo> clone-test && cd clone-test
# 只需提供 SDK 路径（Android Studio 或 local.properties），其余全部来自仓库
./gradlew :apps:powertrace:assembleDebug :apps:signalmap:assembleDebug :apps:vibrodiag:assembleDebug testDebugUnitTest
python analysis/tests/run_all.py
```

| 检查 | 结果 |
|---|---|
| 克隆体积 / 受版本控制文件数 | 11.6 MB / 400 个文件 |
| 克隆后三 App 构建 + 148 项单测 | ✅ 通过 |
| 克隆后分析管线 68 项断言 | ✅ 全绿（data/demo 随身携带） |
| Markdown 相对链接（26 个文件 / 53 条） | ✅ 全部可解析（`tools/check-docs.py`，已接入 CI） |
| GitHub Actions YAML | ✅ YAML 语法校验通过 |

## 9. 复现命令汇总

```bash
./gradlew clean
./gradlew testDebugUnitTest lintDebug
./gradlew :apps:powertrace:assembleDebug :apps:signalmap:assembleDebug :apps:vibrodiag:assembleDebug
powershell -File tools/verify-apk.ps1
python analysis/tests/run_all.py
```

## 强制权限门禁（2026-11-18 真机验证）

- PowerTrace 在“新建批次”后若必需权限未全部满足，会进入《开始前强制检查》页；
- 必需项：通知权限、电池优化豁免、精确闹钟、厂商“后台活动/自启动”（勾选确认）、系统“睡眠待机优化”已关闭（勾选确认）；
- 未全部完成时按钮为“必需项未完成”（不可点击），无法进入批次配置；
- 厂商路径按 Build.MANUFACTURER 显示 OnePlus/OPPO/realme、小米/Redmi、华为/荣耀、vivo/iQOO 及通用路径；
- 通过后进入“配置实验参数”页，可正常开始采集；
- 截图：docs/screenshots/powertrace-preflight.png。
