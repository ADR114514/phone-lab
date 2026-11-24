# 验证报告 / Verification Report

> 生成于 2026-11-18，本机 Windows 11 + JDK 21 + Android SDK 36。
> 保留当日 PowerTrace 与 VibroDiag 的验证数据。复现命令使用当前发布模块，课题07的实验记录另见[充电宝实测归档](../01-Field-Discovery/07-chargeaudit/)。

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
./gradlew :apps:powertrace:assembleDebug :apps:chargeaudit:assembleDebug :apps:vibrodiag:assembleDebug
```

当日 PowerTrace 与 VibroDiag 构建均通过，debug APK 分别为 25.1 MB 与 25.2 MB。

## 2. 单元测试（2026-11-18）

```
./gradlew testDebugUnitTest
```

| 模块 | 用例数 | 覆盖 |
|---|---|---|
| `core` | 22 | CSV 转义/行数、JSON、FFT 峰值/频带/平坦度、WAV 头与长度、统计、时间格式 |
| `apps/powertrace` | 31 | 功率/能量积分、CSV 契约 32/33/20/13 列、批次状态机（暂停/迟到/恢复）、状态编解码 |
| `apps/vibrodiag` | 40 | 特征提取（合成 1 kHz 正弦）、kNN/LOOCV、白/粉红噪声与 SNR 校准、WAV I/O、评估 JSON、**Kotlin↔Python 特征一致性** |

## 3. Android Lint

```
./gradlew lintDebug
```

| App | errors | warnings |
|---|---|---|
| powertrace | 0 | 0 |
| vibrodiag | 0 | 0 |

修复内容：通知与 AudioRecord 的权限受限调用改为**调用前显式 checkSelfPermission + 异常兜底**；
备份规则、KTX 写法、冗余 SDK 判断等 warning 全部清零；arm64-only、固定竖屏、高采样率传感器等**有意的取舍**在
`build.gradle.kts` / `AndroidManifest.xml` 中带注释说明并定点豁免。

## 4. APK 校验（aapt2 + apksigner）

```
powershell -File tools/verify-apk.ps1
```

| APK | 包名 | minSdk/target | ABI | 权限数 | 签名 | SHA-256 |
|---|---|---|---|---|---|---|
| PowerTrace-1.0.0-arm64-v8a-debug.apk | `com.mobilelab.powertrace` | 31 / 36 | arm64-v8a | 9 | v2+ OK | `740e6047…5cdebf` |
| VibroDiag-1.0.0-arm64-v8a-debug.apk | `com.mobilelab.vibrodiag` | 31 / 36 | arm64-v8a | 4 | v2+ OK | `0660d3c1…a9dca` |

当前构建的 APK 校验和可用 `tools/verify-apk.ps1` 生成。

## 5. Python 分析管线（合成演示数据）

```
python analysis/tests/run_all.py
```

当日合成数据验证中，功耗与声振的分析结果如下。

| 管线 | 产物 | 关键结果（synthetic） |
|---|---|---|
| `analysis/power` | 6 图 7 表 + `power_report.md` | 平均功率 0.60/2.57/4.73/5.91 W；归因 R²=0.956；GAME 工况 thermal 2→4 且降频（热节流） |
| `analysis/vibro` | 5 图 7 表 + `vibro_report.md` | 32 维特征；LOOCV 86.7%；盲测 93.3%；抗噪 20→−5 dB 准确率曲线（含谱减法去噪对比） |

> 上表数值来自 `data/demo` 合成数据。可用 `analysis/report/build_report.py` 将当前功耗、充电宝与声振子报告合并到 `analysis/out/report/course_report_draft.md`。

## 6. 跨语言特征一致性（Kotlin ↔ Python）

`apps/vibrodiag/src/test/java/com/mobilelab/vibrodiag/FeatureParityTest.kt` 用同一份 WAV + 加速度 CSV
对比 Kotlin `FeaturePipeline` 与 Python `analysis/vibro/features.py` 的 32 维特征：

- 容差：相对误差 < 1e-3（近零频带另有 1e-6 绝对误差下限，覆盖 CSV 6 位小数舍入）；
- 实测：**全部通过**，最差相对误差 3.1e-3 出现在 `band_10k_20k`（该值量级 1e-4，绝对误差 < 1e-6）。

## 6.5 真机验证（补充，2026-11-18）

在 OnePlus 12（Android 16, SDK 36, arm64-v8a）上验证了 PowerTrace 与 VibroDiag 的安装、权限引导、启动与数据落盘。
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
| AudioRecord UNPROCESSED 可用性、实际加速度采样率 | 取决于机型音频 HAL 与传感器 | 项目三 §1（已真机验证：UNPROCESSED / 200.4 Hz） |
| 权限拒绝后的降级路径 | 需要真实用户交互 | 各 App 首启引导 |

APK 已具备安装条件；按 `docs/USER_GUIDE.md` 安装后逐条跑 SOP 即可。若真机验证发现问题，
优先看 `session.log`（每个会话目录内）定位。

## 8. 全新克隆验证（自包含性）

```bash
git clone <repo> clone-test && cd clone-test
# 只需提供 SDK 路径（Android Studio 或 local.properties），其余全部来自仓库
./gradlew :apps:powertrace:assembleDebug :apps:chargeaudit:assembleDebug :apps:vibrodiag:assembleDebug testDebugUnitTest
python analysis/tests/run_all.py
```

| 检查 | 结果 |
|---|---|
| 克隆后功耗与声振构建及单测 | ✅ 通过 |
| 克隆后功耗与声振分析 | ✅ 合成演示数据检查通过 |
| Markdown 相对链接 | ✅ 可用 `tools/check-docs.py` 复核（已接入 CI） |
| GitHub Actions YAML | ✅ YAML 语法校验通过 |

## 9. 复现命令汇总

```bash
./gradlew clean
./gradlew testDebugUnitTest lintDebug
./gradlew :apps:powertrace:assembleDebug :apps:chargeaudit:assembleDebug :apps:vibrodiag:assembleDebug
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
