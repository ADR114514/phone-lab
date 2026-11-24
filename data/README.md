# data/ · 实验数据目录

本目录分两类数据，**不可混用**：

| 目录 | 内容 | 来源 | 是否可写进报告 |
|---|---|---|---|
| `data/raw/` | 真机实测导出数据（按会话目录组织，每次实验附 NOTES.md） | 本人手机与实验仪表 | ✅ 用于正式实验报告 |
| `data/demo/` | **合成演示数据（SYNTHETIC）**，用于验证 analysis/ 管线 | `data/demo/generate_demo.py` 脚本生成 | ❌ 只能用于管线自检，不能冒充实验结果 |

目录约定：

```
data/
├── raw/                       # 真机数据，见 data/raw/README.md
│   ├── power/<session>/       # PowerTrace 批次（4 session + campaign + summary + manifest）
│   └── vibro/<session>/       # VibroDiag 会话（5 工况 × 15 组 + index/blind/evaluation）
└── demo/                      # 合成演示数据，见 data/demo/README.md
    ├── power/<session>/
    └── vibro/<session>/
```

课题07的 App 导出、USB 检测仪记录与分析结果见[充电宝实测归档](../01-Field-Discovery/07-chargeaudit/)。采样表与会话参数模板在 [data/templates/topic07](templates/topic07/)，实验步骤见[课题07核验指南](../docs/TOPIC07_CHARGE_EFFICIENCY.md)。

分析脚本同时支持两类输入：

```powershell
# 真机数据
python analysis/power/analyze.py --data data/raw/power/<session> --out analysis/out/power
# 演示数据
python analysis/power/analyze.py --data data/demo/power/20261118_PC-20261118-001 --out analysis/out/power
```

大文件说明：CSV、JSON、Markdown 和 PNG 可直接纳入版本控制。WAV 与 MP4 的处理方式见[原始数据目录说明](raw/README.md)。
