# PhoneLab · 手机内置传感器实测试验平台

> 三部 Android 实测 App + 一套 Python 分析管线，覆盖课程三个课题：
> **课题02 个人手机多工况发热与掉电实测**、**课题03 教学楼与宿舍无线信号盲区测绘**、
> **项目三 基于手机内置传感器的旋转机械/电器异常声振诊断仪**。

本仓库同时是这三项实验的**过程数据仓库**：App 源码、数据契约、实验 SOP、原始数据目录、
分析脚本、图表与报告草稿都在这里，可直接用于课程交付与面试溯源。

---

## 1. 三个 App

| App | 目录 | 课题 | 核心能力 | 关键权限 |
|---|---|---|---|---|
| **PowerTrace 功耗热测仪** | `apps/powertrace` | 课题02 | 4 工况 × 30 min × 6 采样点（24 组）；电量/电流/电压/温度/thermal/CPU 频率/网络速率/帧率；批次 CSV、工况汇总、ZIP 导出 | 前台服务、精确闹钟、通知、WAKE_LOCK、电池优化豁免、使用情况访问（可选） |
| **SignalMap 校园信号测绘** | `apps/signalmap` | 课题03 | 8 点位 × 3 次（24 组）；RSSI/协商速率/延迟/P95/抖动/丢包/邻道扫描；CameraX 点位拍照；CSV/ZIP 导出 | 精确定位、Camera、附近 WiFi（33+）、WiFi/网络状态 |
| **VibroDiag 声振诊断仪** | `apps/vibrodiag` | 项目三 | 5 工况 × 15 组 WAV+加速度；FFT 特征（40+ 维）；kNN 分类；LOOCV；15 组盲测；白/粉红噪声抗噪评估；评估 JSON/ZIP | 录音、高采样率传感器、WAKE_LOCK |

公共能力在 `core/`：CSV/JSON 序列化、文件存储与 SAF 导出、ZIP、分享、深色主题与图表组件、权限工具、FFT/WAV、统计。

## 2. 技术规格

- **Android 12+**（`minSdk 31`），`compileSdk 36`，`targetSdk 36`
- **仅 arm64-v8a**（`ndk.abiFilters += "arm64-v8a"`，不打包 32 位原生库）
- Kotlin 2.1.21 + Jetpack Compose（Material3）+ Coroutines
- AGP 8.13.2 / Gradle 8.14.5 / JDK 17+
- 无网络后端：所有数据留在手机 App 私有目录，导出走系统文件选择器或分享面板

## 3. 构建

```bash
# 环境：JDK 17+、Android SDK Platform 36、Build-Tools 36.0.0
./gradlew :apps:powertrace:assembleDebug
./gradlew :apps:signalmap:assembleDebug
./gradlew :apps:vibrodiag:assembleDebug
# 单元测试
./gradlew testDebugUnitTest
```

APK 输出：`apps/<app>/build/outputs/apk/debug/<app>-debug.apk`。
也可用 Android Studio 直接打开仓库根目录（Gradle 工程会自动同步）。

## 4. 仓库结构

```
phone-lab/
├── apps/
│   ├── powertrace/          # 课题02 App
│   ├── signalmap/           # 课题03 App
│   └── vibrodiag/           # 项目三 App
├── core/                    # 三 App 共享库（CSV/UI/权限/信号处理）
├── analysis/                # Python 分析管线（三条）
├── data/
│   ├── demo/                # 合成演示数据（synthetic，仅用于跑通管线）
│   └── raw/                 # 真机实验原始数据（按会话目录组织，见 data/README.md）
├── docs/
│   ├── DATA_DICTIONARY.md   # 数据契约（唯一权威）
│   ├── feature_spec.md      # 声振特征定义（Kotlin/Python 对齐）
│   ├── EXPERIMENT_SOP.md    # 实验标准作业流程
│   ├── INTERVIEW_PREP.md    # 面试溯源材料与问答准备
│   ├── specs/               # 三个 App 的实现规格
│   └── report/              # 三份课程报告模板
└── .github/workflows/       # CI：构建 + 单元测试
```

## 5. 数据与隐私

- 所有采集数据**只写在本机 App 私有目录**（`Android/data/com.mobilelab.*/files/experiments/`），App 不联网上传。
- SignalMap 导出默认对 BSSID 做 SHA-256 哈希脱敏（可关闭），点位照片只拍墙体/环境，不含人脸。
- VibroDiag 只录设备声振信号，不录人声对话；采集前会提示环境要求。
- 仓库只提交 CSV/JSON/分析结果等文本；WAV/JPEG 等大文件通过 Git LFS 或 Release 附件发布（见 `data/README.md`）。
- 详见 `docs/INTERVIEW_PREP.md` 的"工程伦理"一节。

## 6. 课程交付物对照

| 课程要求 | 仓库产物 |
|---|---|
| 课题02：24 组时间序列 CSV、高功耗模块分析、省电策略 | `data/raw/power/<session>/`（24 行批次 CSV）+ `analysis/power/` 报告与图表 + `docs/report/课题02_*.md` |
| 课题03：24 组测速原始数据、点位照片、墙体材质与多径衰减分析 | `data/raw/wifi/<session>/`（survey 24 行 + photos）+ `analysis/wifi/` + `docs/report/课题03_*.md` |
| 项目三：5 工况 × 15 组 WAV/CSV + 特征代码 + 盲测视频与抗噪报告 | `apps/vibrodiag`（采集+特征+盲测+抗噪）、`data/raw/vibro/`、`analysis/vibro/`、`docs/report/项目三_*.md` |

> ⚠️ `data/demo` 是**合成数据**，仅用于验证分析脚本；正式提交的数据必须由本人用真机按 SOP 采集并放入 `data/raw`。

---

## 8. 构建与测试状态（本机验证）

| 模块 | assembleDebug | 单元测试 |
|---|---|---|
| `core` | ✅ | ✅ 22 项（CSV/JSON/FFT/WAV/统计/时间） |
| `apps/powertrace` | ✅ | ✅ 31 项（功率/能量/CSV 契约/批次状态机/状态编解码） |
| `apps/signalmap` | ✅ | ✅ 55 项（RTT 统计/CSV 契约/进度状态机/BSSID 哈希/信道） |
| `apps/vibrodiag` | ✅ | ✅ 特征/FFT/kNN/噪声/报告/WAV I/O |
| `analysis`（Python） | — | ✅ `analysis/tests/run_all.py` 68 项断言全绿（演示数据） |

三个 APK 均为 `minSdk 31 / targetSdk 36 / arm64-v8a`，可用 `tools/verify-apk.ps1` 复核。

## 9. 分析结果示例（基于 `data/demo` 合成数据，**非真实实验结论**）

- 功耗：待机 0.60 W / 短视频 2.57 W / 导航拍摄 4.73 W / 游戏 5.91 W；活动指示量回归 R²≈0.96；
  GAME 工况检测到 thermal 2→4 与 CPU 频率下降（热节流）。
- 无线：路径损耗指数 n≈1.97；材质附加衰减 混凝土 ≈18.5 dB / 金属 ≈24.7 dB / 玻璃 ≈5.0 dB；
  盲区分级 强 1 / 中 2 / 弱 3 / 盲区 2。
- 声振：5 工况 × 3 组演示数据，32 维特征，LOOCV 86.7%、盲测 93.3%；抗噪曲线与谱减法去噪对比见
  `analysis/out/vibro/figures/noise_accuracy.png`。

> 这些数字仅用于验证管线可运行。正式报告必须替换为 `data/raw` 下的真机数据并重跑 `analysis/`。

## 10. 复现步骤

```bash
# 1) 构建三个 APK
./gradlew :apps:powertrace:assembleDebug :apps:signalmap:assembleDebug :apps:vibrodiag:assembleDebug
# 2) 单元测试
./gradlew testDebugUnitTest
# 3) 分析管线（演示数据，一键 68 项断言）
python analysis/tests/run_all.py
# 4) 用真机数据重跑（把 App 导出目录放到 data/raw/<app>/<session>/）
python analysis/power/analyze.py --data data/raw/power/<session> --out analysis/out/power
python analysis/wifi/analyze.py  --data data/raw/wifi/<session>  --out analysis/out/wifi
python analysis/vibro/analyze.py --data data/raw/vibro/<session> --out analysis/out/vibro
```

## 7. 许可证

课程作业用途；第三方依赖遵循各自开源许可证。未经同意请勿将实验数据用于其他用途。