# data/raw · 真机实验原始数据

> 本目录存放**本人真机实测**的导出数据（按会话目录组织）。`data/demo` 是合成演示数据，不可混用。

## 目录约定

```
data/raw/
├── power/<session>/       # PowerTrace 批次目录（4 个 session_*.csv + campaign_*.csv + summary + manifest + log）
├── topic07/<session>/     # ChargeAudit 实验记录（逐点 CSV、铭牌参数与仪表原始记录）
└── vibro/<session>/       # VibroDiag 会话目录（5 工况 × 15 组 + dataset_index/blind_test/noise_eval/evaluation）
```

## 每次提交数据时必须附带 `NOTES.md`

模板：

```markdown
# <session id> 实验记录

- 日期/时间：2026-11-18 14:00–16:10
- 地点/环境：三教 2F，室温 24.5 °C，湿度 55%
- 设备：Pixel 7 / Android 14 / 电池设计容量 4355 mAh
- 采集人：
- 实验条件：4 工况顺序 IDLE→VIDEO→NAVCAM→GAME；亮度固定 50%；未充电
- 异常与处理：游戏工况第 3 个采样点接到来电，标记 late_sample 并保留；其余正常
- 结论要点：游戏平均功耗 6.1 W，电池 30 min 掉落 9%
- 对应提交：见本目录同批次 git commit
```

## 大文件

- CSV/JSON/MD/PNG/JPG 可直接提交。
- WAV/MP4/M4A 通过 Git LFS 提交（`git lfs install` 后 `git add` 自动处理），或放在 GitHub Release 附件并在 README 链接。
- 原始数据只增不改；清洗/剔除结果写到 `analysis/out`，并记录在报告与 NOTES 中。
