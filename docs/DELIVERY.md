# 交付说明 / Delivery Notes

## 1. 已交付内容

| 类别 | 内容 | 位置 |
|---|---|---|
| 三部 Android App | 源码 + 可编译 APK（debug） | `apps/powertrace`、`apps/chargeaudit`、`apps/vibrodiag` |
| 共享库 | CSV/JSON/导出/主题/图表/权限/FFT/WAV/统计 + 单元测试 | `core/` |
| 数据契约 | 三套 CSV/JSON 字段、单位、空值与文件布局 | `docs/DATA_DICTIONARY.md` |
| 特征规格 | 声振 32 维特征的精确定义（Kotlin/Python 对齐） | `docs/feature_spec.md` |
| 实验 SOP | 三课题的现场操作、异常处理、复测标准 | `docs/EXPERIMENT_SOP.md` |
| 用户指南 | 安装、权限、每步操作、数据导出 | `docs/USER_GUIDE.md` |
| 分析管线 | 功耗归因 / 充电能量与效率 / 声振分类与抗噪 | `analysis/` |
| 报告模板 | 三份课程报告草稿框架（含图表占位） | `docs/report/` |
| 面试材料 | 5 维面试的证据链、追问与回答框架 | `docs/INTERVIEW_PREP.md` |
| 演示数据 | 合成数据（synthetic，仅用于跑通管线） | `data/demo/` |
| CI | 构建 + 单测 + Python 管线 | `.github/workflows/android.yml` |

## 2. 还需要你亲自完成的部分（无法代劳）

1. **真机实验**：按 `docs/EXPERIMENT_SOP.md` 用你自己的手机采集
   - 课题02：4 工况 × 30 min（约 2 小时 + 降温）
   - 课题07：按[充电宝核验指南](TOPIC07_CHARGE_EFFICIENCY.md)记录输入与输出侧仪表读数及会话终点
   - 项目三：5 工况 × 15 组（约 1.5–2 小时）+ 盲测录屏 + 抗噪实验
2. **把导出数据放进** `data/raw/<app>/<session>/`，每次实验写一份 `NOTES.md`。
3. **填写报告**：把 `analysis/out/<app>/figures` 的图插入 `docs/report/` 模板，替换占位结论。
4. **录制盲测视频**：从开始盲测到显示准确率全程录屏，上传后把链接写进 README/报告。

## 3. 功耗与声振的历史验证（2026-11-18）

| 检查 | 结果 |
|---|---|
| `:apps:powertrace:assembleDebug` / `testDebugUnitTest` | ✅ 构建通过；31 项单测全过 |
| `:apps:vibrodiag:assembleDebug` / `testDebugUnitTest` | ✅ 构建通过；40 项单测全过（含 Kotlin/Python 特征一致性） |
| Android Lint | ✅ PowerTrace 与 VibroDiag 均为 0 errors / 0 warnings |
| APK 签名与元数据（apksigner/aapt2） | ✅ PowerTrace 与 VibroDiag 皆为 v2+ 签名、minSdk 31 / targetSdk 36 / arm64-v8a |
| Python 分析 | ✅ 功耗与声振的合成演示数据回归通过 |
| Kotlin↔Python 特征一致性 | ✅ 32 维特征相对误差 < 1e-3 |
| 真机联调（OnePlus 12 / Android 16） | 在 PowerTrace 验证了首点采样与中止。在 VibroDiag 验证了录制、评估与 ZIP 导出。详见[真机联调报告](DEVICE_TEST_REPORT.md)。 |

## 4. 本地验证命令

```bash
# 构建三个 APK
./gradlew :apps:powertrace:assembleDebug :apps:chargeaudit:assembleDebug :apps:vibrodiag:assembleDebug
# 全部单元测试
./gradlew testDebugUnitTest
# APK 校验（minSdk/ABI/权限）
powershell -File tools/verify-apk.ps1
# Python 分析管线（演示数据）
python analysis/tests/run_all.py
```

## 5. 课程交付物对照

- 课题02 [功耗实测归档](../01-Field-Discovery/02-powertrace/)包含 24 条原始记录、曲线、有效功率窗和省电建议。
- 课题07 [充电宝实测归档](../01-Field-Discovery/07-chargeaudit/)包含 App 原始读数、放电曲线、区间能量及损耗分析。
- 项目三 [风扇声振实测归档](../02-20Days-Challenge/03-vibrodiag/)包含 5×15 组训练、15 组盲测、特征代码、模型与抗噪结果。
