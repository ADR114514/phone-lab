# 交付说明 / Delivery Notes

## 1. 已交付内容

| 类别 | 内容 | 位置 |
|---|---|---|
| 三部 Android App | 源码 + 可编译 APK（debug） | `apps/powertrace`、`apps/signalmap`、`apps/vibrodiag` |
| 共享库 | CSV/JSON/导出/主题/图表/权限/FFT/WAV/统计 + 单元测试 | `core/` |
| 数据契约 | 三套 CSV/JSON 字段、单位、空值与文件布局 | `docs/DATA_DICTIONARY.md` |
| 特征规格 | 声振 32 维特征的精确定义（Kotlin/Python 对齐） | `docs/feature_spec.md` |
| 实验 SOP | 三课题的现场操作、异常处理、复测标准 | `docs/EXPERIMENT_SOP.md` |
| 用户指南 | 安装、权限、每步操作、数据导出 | `docs/USER_GUIDE.md` |
| 分析管线 | 功耗归因 / WiFi 衰减 / 声振分类与抗噪 | `analysis/` |
| 报告模板 | 三份课程报告草稿框架（含图表占位） | `docs/report/` |
| 面试材料 | 5 维面试的证据链、追问与回答框架 | `docs/INTERVIEW_PREP.md` |
| 演示数据 | 合成数据（synthetic，仅用于跑通管线） | `data/demo/` |
| CI | 构建 + 单测 + Python 管线 | `.github/workflows/android.yml` |

## 2. 还需要你亲自完成的部分（无法代劳）

1. **真机实验**：按 `docs/EXPERIMENT_SOP.md` 用你自己的手机采集
   - 课题02：4 工况 × 30 min（约 2 小时 + 降温）
   - 课题03：8 点位 × 3 次（约 1.5 小时）
   - 项目三：5 工况 × 15 组（约 1.5–2 小时）+ 盲测录屏 + 抗噪实验
2. **把导出数据放进** `data/raw/<app>/<session>/`，每次实验写一份 `NOTES.md`。
3. **填写报告**：把 `analysis/out/<app>/figures` 的图插入 `docs/report/` 模板，替换占位结论。
4. **录制盲测视频**：从开始盲测到显示准确率全程录屏，上传后把链接写进 README/报告。
5. **GitHub 推送**：见第 4 节；推送后把仓库链接填入报告与面试材料。

## 3. 本机已完成的验证（2026-11-18）

| 检查 | 结果 |
|---|---|
| `:apps:powertrace:assembleDebug` / `testDebugUnitTest` | ✅ 构建通过；31 项单测全过 |
| `:apps:signalmap:assembleDebug` / `testDebugUnitTest` | ✅ 构建通过；55 项单测全过 |
| `:apps:vibrodiag:assembleDebug` / `testDebugUnitTest` | ✅ 构建通过；40 项单测全过（含 Kotlin/Python 特征一致性） |
| `testDebugUnitTest`（全仓） | ✅ 通过（148 项） |
| `lintDebug`（全仓） | ✅ 0 errors（权限受限 API 均已显式复核） |
| APK 元数据（aapt2） | ✅ 三个 APK 均 minSdk 31 / targetSdk 36 / arm64-v8a，权限与规格一致 |
| `python analysis/tests/run_all.py` | ✅ 68/68 断言全绿（合成演示数据） |
| 真机安装与实测 | ⏳ **待你在 Android 12+ 真机上完成**（当前环境无设备/模拟器） |

## 4. 本地验证命令

```bash
# 构建三个 APK
./gradlew :apps:powertrace:assembleDebug :apps:signalmap:assembleDebug :apps:vibrodiag:assembleDebug
# 全部单元测试
./gradlew testDebugUnitTest
# APK 校验（minSdk/ABI/权限）
powershell -File tools/verify-apk.ps1
# Python 分析管线（演示数据）
python analysis/tests/run_all.py
```

## 5. GitHub 推送（拿到账号后）

```bash
cd phone-lab
git remote add origin https://github.com/<你的用户名>/phone-lab.git
git push -u origin main
```

> 若需要提交 WAV：先 `git lfs install`，再用 `git add data/raw/...`；否则把 WAV 打包上传到 Release 并在 README 链链接。

## 6. 课程交付物对照

- 课题02：`data/raw/power/<session>/campaign_*.csv`（恰好 24 行）+ `analysis/out/power/` + 报告。
- 课题03：`data/raw/wifi/<session>/survey_*.csv`（恰好 24 行）+ photos + `analysis/out/wifi/` + 报告。
- 项目三：`data/raw/vibro/<session>/`（5×15 组 WAV/CSV/features）+ `analysis/out/vibro/`（特征/混淆矩阵/抗噪曲线）+ 盲测视频 + 报告。