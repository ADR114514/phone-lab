# data/demo · 合成演示数据（SYNTHETIC DEMO DATA）

> ⚠️ **本目录全部数据都是脚本合成的演示数据，不是真机实测结果。**
> 它们只用于让 `analysis/` 三条管线端到端跑通、验证 CSV/JSON 数据契约与图表流程；
> 课程报告与任何对外材料中的测量结论必须使用 `data/raw/` 下的真实采集数据。
> 如果误用了这里的数字，请在提交前删掉并重跑。

## 生成方式

```powershell
# 仓库根目录执行；固定随机种子，可复现
python data/demo/generate_demo.py                 # 生成到 data/demo（默认）
python data/demo/generate_demo.py --seed 20261118 # 指定种子
python data/demo/generate_demo.py --only vibro    # 只生成某一类
```

每份 CSV/JSON 都带标记：

- `manifest.json`：`"synthetic": true`、`"dataSource": "SYNTHETIC_DEMO"`、英文免责声明；
- 各报告/分析输出：`analysis/` 管线识别到 `data/demo` 或 `synthetic` 标记后，会在报告开头显著标注 SYNTHETIC；
- 数值字段里的 `note`：如“演示数据：……”字样。

## 目录结构

```
data/demo/
├── generate_demo.py                        # 数据生成脚本（可复现）
├── README.md
├── power/
│   └── 20261118_PC-20261118-001/           # PowerTrace 批次目录
│       ├── manifest.json
│       ├── session_IDLE.csv / VIDEO / NAVCAM / GAME   # 各 6 行
│       ├── campaign_PC-20261118-001.csv    # 24 行（4 工况 × 6 点）
│       ├── campaign_summary_PC-20261118-001.csv       # 4 行工况汇总
│       ├── raw_*.csv                       # 各 360 行 5 s 高频序列（演示“原始序列”开关）
│       └── session.log
├── wifi/
│   └── 20261118_WS-20261118-001/
│       ├── manifest.json
│       ├── survey_WS-20261118-001.csv      # 8 点位 × 3 次 = 24 行
│       ├── points.csv                      # 点位元数据（无照片）
│       ├── scan_WS-20261118-001_P01_r1.csv …（共 24 个扫描明细）
│       └── session.log
└── vibro/
    └── 20261118_VD-20261118-001/
        ├── manifest.json
        ├── dataset_index.csv               # 5 工况，每组 target_groups=3（演示规模）
        ├── blind_test_VD-20261118-001.csv  # 15 组盲测（3 组/工况）
        ├── C1_NORMAL_LOW/g01..g03/         # 每工况 3 组训练样本
        │   ├── g01_C1_NORMAL_LOW_audio.wav      # 3 s / 22050 Hz / 16-bit
        │   ├── g01_C1_NORMAL_LOW_accel.csv      # 200 Hz / 600 行
        │   ├── g01_C1_NORMAL_LOW_features.csv   # 38 列（32 特征 + 6 标签）
        │   └── g01_C1_NORMAL_LOW_meta.json
        ├── C2_NORMAL_HIGH/… C5_HAIRDRYER/…
        └── blind/<condition>/b01..b03/     # 盲测音频（不参与训练）
```

总体积约 **4–5 MB**（< 15 MB 限制）。WAV 使用 3 s / 22050 Hz 以控制仓库体积；
真机数据是 15 s / 44.1 kHz / 44.1k 单声道（数据契约）。

## 合成模型与“已知真值”

### 课题02 · 功耗（power）

按 4 工况顺序模拟 30 min × 4 的电池/温度/热节流过程：

| 工况 | 平均功率（约） | 亮度 | CPU 频率 | 网络 | 温度行为 |
|---|---|---|---|---|---|
| IDLE 待机 | 0.60 W | 0% | ~0.78 GHz | 极低 | 28.8 → 30.7 °C |
| VIDEO 短视频 | 2.5 W | 50% | ~1.5 GHz | 下行 ~0.9 Mbps | 31.6 → 34.6 °C |
| NAVCAM 导航拍摄 | 4.5 W | 60% | ~1.9 GHz | 上行 ~1.3 Mbps | 33.5 → 35.8 °C |
| GAME 高帧率游戏 | 5.9 W | 55% | ~2.0 GHz（后段降频） | 低 | 34 → 40 °C，thermal 2→4 |

- 电量从 100% 连续下降到约 59%（4 × 25 min 采样窗 + 30 min 工况的收尾区间）；
- 电池电压随电量下降（4.35 V → 4.0 V），电流符号取“放电为负”，验证分析脚本的绝对值处理；
- GAME 工况后段 thermal_status 上升 + CPU 平均频率下降 → 用于验证热节流检测；
- VIDEO 第 5 个采样点故意迟到 130 s（note 写 late_sample），用于验证迟到样本标记。

### 课题03 · 无线（wifi）

以 P01（走廊基准，距 AP 5 m，RSSI ≈ -49 dBm）为参考，按

`RSSI(d) = P0 - 20·log10(d/d0) - 材质附加衰减 + N(0, σ²)`

生成 8 点位 × 3 次。材质附加衰减（已知真值）：混凝土 **18 dB**、金属 **25 dB**、玻璃 **6 dB**、
混合 **14 dB**、无墙体 **0 dB**；σ 取 0.7–2.1 dB（金属/转角更大）。延迟、抖动、丢包随 RSSI 与遮挡恶化，
2.4G 服务信道固定 ch6，扫描明细中混入同信道/邻信道 AP 用于邻道干扰分析。
分析脚本应能恢复到 n ≈ 2、上述材质衰减量级（误差主要来自 3 次重复的随机波动）。

### 项目三 · 声振（vibro）

5 工况 × 3 训练组 + 3 盲测组的音频/加速度信号由正弦/谐波 + 噪声合成：

| 工况 | 音频合成 | 振动合成 |
|---|---|---|
| C1 低档正常 | 42 Hz 基频 + 强谐波（1/h 衰减）+ 少量宽带底噪 | 24 Hz 转频 + 2×/3× 谐波，RMS ~0.7 |
| C2 高档正常 | 66 Hz 基频 + 谐波，整体更响 | 33 Hz 转频，幅值约 C1 的 1.8 倍 |
| C3 轻微失衡 | 42 Hz + 1× 增强 + ±1.7 Hz 边带 | 24 Hz 1× 振幅显著增大 + 边带 |
| C4 风道遮挡 | 低通 1.5 kHz + 宽带噪声增强（高频衰减） | 转频减弱 + 湍流宽带振动 |
| C5 吹风机 | 230 Hz 电机音 + 高频宽带气流噪声 | 78 Hz + 强宽带振动 |

- 组间加入 3%–15% 的随机参数扰动（转速/电平/振动幅值），使 LOOCV 不是不真实的 100%
  （目前演示数据 LOOCV ≈ 80%、盲测 ≈ 93%，kNN 会混淆 C1/C3 与 C3/C4 的边界样本）；
- 盲测组参数再额外扰动（+40%），且不参与训练；
- features.csv 由 `analysis/vibro/features.py` 从落盘 WAV 重新提取后写入，
  分析脚本再次重算的交叉校验相对误差 < 1e-3，用于验证 App/Python 特征一致性。

## 已知局限（写报告时必须说明）

1. 这是合成数据：噪声/混响/多径/电池化学特性都比真机简单，结论只能证明“管线正确”，不能证明“测量正确”。
2. 演示规模被压缩：vibro 每工况仅 3 组（真机 15 组）、每次 3 s（真机 15 s）、22.05 kHz（真机 44.1 kHz）；
   dataset_index 的 target_groups 记为 3，分析脚本按该值校验完整性。
3. wifi 没有现场照片（photos 字段为空）；power 的 fps 只在演示里给 VIDEO/GAME 填了值
   （真实 App 后台无法测 fps，缺失是正常现象）。
4. power 的 fps/亮度/CPU 频率与工况高度相关，导致模块归因回归存在共线性；
   这是 4 工况实验设计的固有局限，报告里必须声明“活动指示量回归 ≠ 硬件电流计”。
5. 演示数据不包含真实实验的异常（掉采样、充电误触、传感器饱和、设备温漂等），
   这些异常的真机证据请写在 data/raw/<session>/NOTES.md 中。
