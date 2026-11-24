# PhoneLab 手机传感器实测工具

正式审核入口使用[考号仓库 ICEC-2026-0056-hanzitai](https://github.com/ADR114514/ICEC-2026-0056-hanzitai)。本仓库提供工具和实测资料的开发归档。[下载站](http://189.105.67.241/)

| 官网阶段 | 项目 | 实测材料 |
|---|---|---|
| 第一阶段 | 课题 02 · 手机多工况发热与掉电 | [原始 24 点、分析和图表](01-Field-Discovery/02-powertrace/) |
| 第一阶段 | 课题 07 · 充电宝容量与转换效率 | [19 点记录、现场照和能量分析](01-Field-Discovery/07-chargeaudit/) |
| 第二阶段 | 项目三 · 旋转机械声振诊断 | [75 组训练、15 组盲测、代码、实验照与抗噪](02-20Days-Challenge/03-vibrodiag/) |

## 源码与复现

- [PowerTrace](apps/powertrace/) · [ChargeAudit](apps/chargeaudit/) · [VibroDiag](apps/vibrodiag/) · [共享组件](core/)。
- [实验操作](docs/EXPERIMENT_SOP.md) · [数据字典](docs/DATA_DICTIONARY.md) · [声振特征](docs/feature_spec.md) · [分析脚本](analysis/) · [训练与盲测的复现路径](02-20Days-Challenge/03-vibrodiag/README.md)。
- Android 12+、arm64；构建使用 JDK 17、Android SDK 36。命令见下方，实测和演示数据分别标注。VibroDiag 1.5.0 正式盲测使用冻结的 MATLAB 核岭模型，旧 App 的内置分类评估未用于报告盲测成绩。

~~~bash
./gradlew :apps:powertrace:assembleDebug :apps:chargeaudit:assembleDebug :apps:vibrodiag:assembleDebug
./gradlew testDebugUnitTest lintDebug
python analysis/tests/run_all.py
~~~

[官网提交页](https://icec.openkub.com/submit.html) · [考核大纲](https://icec.openkub.com/assessment.html)
