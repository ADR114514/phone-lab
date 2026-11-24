# 架构设计 / Architecture

## 1. 为什么是单仓多模块

三个实验 App 共享 CSV/JSON 序列化、文件存储与导出、权限引导、深色主题与图表等能力。
公共实现放在 `core` Android Library，各 App 负责自己的实验流程与界面。

```
core · CSV / JSON / 文件 / 权限 / UI / FFT / WAV / 日志
├── powertrace · 电池、热状态与工况采样
├── chargeaudit · USB 检测仪读数录入与充电能量汇总
└── vibrodiag · 音频、加速度与频谱特征
```

权限、导出与 UI 组件共用，底层逻辑在 `core` 中单测。三个 APK 独立安装、独立申请权限、独立打包（无动态特性、无进程共享）。

## 2. 数据流（以一次实验为例）

```
传感器/API 或仪表读数 ──▶ 采集引擎（Service/Activity）
                              │
                    ┌─────────┼──────────────┐
                    ▼         ▼              ▼
              CSV 增量追加   session.log   manifest.json
                    │
                    ▼
             App 私有目录 experiments/<app>/<session>/
                    │  （SAF/分享/ZIP 导出 或 adb run-as 拉取）
                    ▼
             data/raw/<app>/<session>/   （Git 仓库）
                    │
                    ▼
        analysis/<app>/analyze.py ──▶ figures/ tables/ *_report.md
                    │
                    ▼
              docs/report/*.md（课程报告）
```

设计原则：

1. **边采边写**：每得到一个采样点立即追加 CSV 并 flush，进程被杀最多丢当前点；状态存 SharedPreferences 可恢复。
2. **单一数据契约**：字段名/单位/空值规则只在 `docs/DATA_DICTIONARY.md` 定义，Kotlin 与 Python 都按它实现。
3. **原始数据不可变**：分析脚本只读 `data/raw`，任何清洗/剔除都输出到 `analysis/out` 并记录规则，不覆盖原始文件。
4. **可解释模型**：项目三用 z-score+kNN 而不是黑盒深度模型：75 组样本下可解释、可在手机端秒级重训、便于面试讲清每一步。

## 3. 关键工程决策

| 问题 | 方案 | 备选与否决原因 |
|---|---|---|
| 30 min 息屏采集不被 Doze 杀 | 前台服务（dataSync）+ `setExactAndAllowWhileIdle` + 采样时短持 wakelock | 整场持锁会污染待机功耗；WorkManager 最小 15 min 周期不够 |
| 采样点必须准时 | 精确闹钟权限（用户可授）+ 迟到样本标记 | 不伪造时间戳；迟到就如实记录 |
| USB 检测仪读数 | ChargeAudit 定时提醒后手工录入电压、电流等读数 | 手机系统 API 提供手机电池状态，充电宝输出看外接检测仪 |
| 录音质量 | AudioRecord UNPROCESSED 优先，回退 MIC；16-bit 44.1k | MediaRecorder 会做 AGC/降噪，破坏频谱特征 |
| 加速度采样 | 200 Hz（HIGH_SAMPLING_RATE_SENSORS），不可用回退 ~50 Hz | 风扇基频通常 <60 Hz，200 Hz 足够 4 阶谐波；记录实际速率 |
| 跨语言特征一致 | 规格文档 + 双侧实现 + 合成正弦单测 + 交叉验证脚本 | 只写 Python 会无法在手机上实时分类；只写 Kotlin 无法批量复现 |
| 大文件 | CSV/JSON 进 Git；WAV/MP4 走 Git LFS 或 Release | 普通 Git 不适合 100 MB+ 二进制 |

## 4. 线程与生命周期

- 采集引擎跑在独立线程/协程（`Dispatchers.IO`），UI 只订阅状态（`StateFlow`），不直接做 IO。
- PowerTrace 的会话状态在 Service 中持有，Activity 只做展示与操作转发；旋转/重建不影响采样。
- VibroDiag 录音线程 + 传感器回调统一时间基准（`elapsedRealtimeNanos`），停止时 join 后关闭 WAV 头。
- 所有模块在 `onDestroy/onStop` 中释放传感器、音频、定时器，避免泄漏与"幽灵录音"。

## 5. 测试策略

| 层 | 方式 | 覆盖 |
|---|---|---|
| core 纯逻辑 | JUnit4（JVM） | CSV 转义/行数、JSON、FFT 峰值/频带、WAV 头部、统计、时间格式 |
| App 纯逻辑 | JUnit4（JVM） | 功率/能量积分、批次状态机、特征提取、kNN、SNR 校准 |
| 数据管线 | `analysis/tests/run_all.py` | 用 `data/demo` 自检分析流程并断言输出 |
| 真机 | 手动 SOP + 导出校验 | 功耗 24 组、充电宝仪表记录、声振 75 组，另查权限拒绝降级与中断恢复 |

## 6. Android 12+ / arm64 约束

- `minSdk 31`：使用 `java.time`、`PowerManager.getCurrentThermalStatus`、`SensorManager` 高采样率权限等 API 不需要兼容层。
- `targetSdk 36`：遵循前台服务类型（`dataSync`/`microphone`）、精确闹钟与通知权限等新规。
- 仅 `arm64-v8a`：`ndk.abiFilters` 限定；当前无 JNI，若有原生模块也只编 64 位。APK 校验见 `tools/verify-apk.ps1`。
