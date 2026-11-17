# analysis/out

本目录是 `analysis/` 管线的生成产物（图表 / 表格 / 子报告 / 总报告草稿）。

- 当前提交的是 **`data/demo` 合成演示数据**的运行结果，仅用于验证管线、预览图表模板；
  所有子报告开头都会标注 `SYNTHETIC`。
- 真机实验后，按 `docs/DELIVERY.md` 用 `data/raw` 重跑管线，本目录会被真实结果覆盖，
  再提交一次即可作为课程交付结果。
- 目录结构：`power/`、`wifi/`、`vibro/` 各有 `figures/`、`tables/`、`*_report.md`；
  `report/course_report_draft.md` 是合并后的总报告草稿。
