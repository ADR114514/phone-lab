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

## 7. 必须由真机完成的验证（本机无设备）

| 项 | 为什么需要真机 | 对应 SOP 检查点 |
|---|---|---|
| 息屏 30 min 仍采满 6 点 | Doze/厂商 ROM 的后台策略无法在 JVM 模拟 | `EXPERIMENT_SOP` 课题02 §2 |
| RSSI/扫描值真实性、定位开关行为 | Android 12+ 权限与节流行为依赖系统 | 课题03 §3 |
| AudioRecord UNPROCESSED 可用性、实际加速度采样率 | 取决于机型音频 HAL 与传感器 | 项目三 §1 |
| CameraX 拍照/旋转、ZIP 分享 | 依赖相机与系统分享面板 | 课题03 §1 |
| 权限拒绝后的降级路径 | 需要真实用户交互 | 各 App 首启引导 |

APK 已具备安装条件；按 `docs/USER_GUIDE.md` 安装后逐条跑 SOP 即可。若真机验证发现问题，
优先看 `session.log`（每个会话目录内）定位。

## 8. 复现命令汇总

```bash
./gradlew clean
./gradlew testDebugUnitTest lintDebug
./gradlew :apps:powertrace:assembleDebug :apps:signalmap:assembleDebug :apps:vibrodiag:assembleDebug
powershell -File tools/verify-apk.ps1
python analysis/tests/run_all.py
```
