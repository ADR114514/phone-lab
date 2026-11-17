# analysis/ · Python 分析管线

三个实验 App 的数据分析端：**只依赖 numpy / pandas / matplotlib**，不依赖 scipy / sklearn；
FFT、滤波、最小二乘、kNN、统计量全部用 numpy 自行实现。数据契约以
[`docs/DATA_DICTIONARY.md`](../docs/DATA_DICTIONARY.md) 为准，声振特征以
[`docs/feature_spec.md`](../docs/feature_spec.md) 为准（与 App 的 Kotlin 实现逐条对齐）。

## 目录结构

```
analysis/
├── common/               # 公共模块
│   ├── io_utils.py       # 兼容 BOM/编码的 CSV 读写、字段校验、缺失值处理
│   ├── stats_utils.py    # 均值/分位数/相关系数/最小二乘/非负消除/路径损耗拟合
│   ├── plot_utils.py     # matplotlib Agg 后端 + 中文字体 + 统一风格
│   └── md_utils.py       # Markdown 表格/图片/报告拼装
├── power/analyze.py      # 课题02：功耗、发热、模块归因、热节流、省电建议
├── wifi/analyze.py       # 课题03：RSSI/延迟/丢包、路径损耗、材质衰减、多径、干扰、盲区分级
├── vibro/
│   ├── features.py       # 32 维声振特征（严格对齐 feature_spec 第 4 节，可单独自检）
│   ├── model.py          # z-score + kNN(k=3)、LOOCV、白/粉红噪声、谱减法去噪
│   └── analyze.py        # 特征复核、LOOCV/混淆矩阵、盲测、抗噪曲线、报告
├── report/build_report.py# 合并三份分报告为课程总报告草稿
├── tests/run_all.py      # 用 data/demo 端到端自检（一键可执行）
├── out/                  # 生成的图表/表格/报告（.gitignore，不入库）
├── requirements.txt
└── README.md
```

## 安装

```powershell
python -m pip install -r analysis/requirements.txt
# 若无网络/公司代理，见文末“常见问题”
```

Python 3.10+（本项目在 3.14 上验证）。**不需要 scipy / scikit-learn**；如果环境里装了也不影响，
代码不会 import 它们（`tests/run_all.py` 会断言这一点）。

## 一键运行（推荐）

在仓库根目录执行：

```powershell
# 1) 端到端自检：数据缺失时自动生成 data/demo，跑三条管线 + 合并报告 + 数值断言
python analysis/tests/run_all.py

# 也可以强制重新生成演示数据
python analysis/tests/run_all.py --regenerate
```

输出示例：

```
[run ] 课题02 功耗管线: ...analysis/power/analyze.py --data ... --out ...analysis/out/power
[PASS] 24 行完整性 / 平均功率排序 IDLE < VIDEO < NAVCAM < GAME / GAME 热节流 ...
结果：全绿 ✅
```

## 三条管线单独运行

```powershell
# 课题02 功耗（默认自动找 data/demo/power 或 data/raw/power 下最新会话）
python analysis/power/analyze.py --data data/raw/power/<session> --out analysis/out/power

# 课题03 无线
python analysis/wifi/analyze.py --data data/raw/wifi/<session> --out analysis/out/wifi
#   --reference-point P01     指定路径损耗参考点
#   --n-pathloss 2.0          强制指定路径损耗指数 n（默认用无遮挡点位拟合）

# 项目三 声振
python analysis/vibro/analyze.py --data data/raw/vibro/<session> --out analysis/out/vibro
#   --k 3                      kNN 的 k
#   --snr 20,10,5,0,-5         抗噪评估的 SNR 列表
#   --noise-types white,pink   噪声类型
#   --no-denoise               跳过谱减法去噪对比（更快）

# 合并课程总报告草稿
python analysis/report/build_report.py
```

所有脚本都支持 `--data` 省略：省略时会在 `data/demo/<app>` 与 `data/raw/<app>` 下寻找最新会话目录。

## 输出结构

```
analysis/out/
├── power/
│   ├── power_report.md
│   ├── figures/{battery_curve,power_current,temperature,condition_compare,
│   │            module_attribution,thermal_throttle}.png
│   └── tables/{sample_quality,condition_metrics,energy_crosscheck,
│               module_attribution,attribution_fit,thermal_analysis,
│               increment_decomposition}.csv
├── wifi/
│   ├── wifi_report.md
│   ├── figures/{rssi_bar,latency_dist,attenuation_material_box,pathloss_fit,
│   │            blind_zone_map,interference}.png
│   └── tables/{survey_quality,point_metrics,pathloss_points,pathloss_fit,
│               material_attenuation,multipath_correlation,interference,
│               channel_occupancy,blind_zone_grades}.csv
├── vibro/
│   ├── vibro_report.md
│   ├── evaluation_<session>.json
│   ├── figures/{confusion_matrix,spectrum_compare,feature_distribution,
│   │            noise_accuracy,blind_test}.png
│   └── tables/{features_all,dataset_check,loocv_confusion,per_condition_accuracy,
│               blind_test_results,noise_accuracy,feature_crosscheck}.csv
└── report/course_report_draft.md
```

## 方法口径与关键约定

### 课题02 功耗

- 指标：平均/峰值功率、采样窗积分能量、掉电率（%/h）、温升速率（°C/min）、thermal 峰值与上升次数；
  能量与 `charge_counter_uah` 差分、App `campaign_summary` 三方交叉校验；
- 模块归因 = **活动指示量最小二乘回归**（亮度、CPU 频率、网络吞吐、帧率、相机/导航工况指示量），
  不是硬件电流计；共线指示量无法唯一分解，报告必须声明这一点，并给出“工况增量分解”作为测量值对照；
- 热节流 = thermal_status 上升 **且** CPU 平均频率后半段较前半段下降 ≥5%；
- 数据质量：24 行完整性、缺失采样点、迟到样本（间隔 >390 s）、充电异常、电量回升、功率重算一致性。

### 课题03 无线

- 每点位 3 次重复：RSSI 均值/标准差、延迟均值/P95、抖动、丢包、稳定性评分；
- 路径损耗 `RSSI(d) = P0 − 10n·log10(d/d0)`：以基准点（默认 P01）为 (P0, d0)，
  只用无墙体点位拟合 n，避免把墙体损耗混进 n；
- 等效附加衰减 = 自由空间预测 − 实测；按材质分组统计并与经验值（混凝土 18 / 金属 25 / 玻璃 6 / 混合 14 dB）对照；
- 多径：RSSI 标准差、延迟抖动与遮挡复杂度/附加衰减的 Pearson + Spearman 相关；
- 邻道干扰：从 `scan_*.csv` 统计同信道 AP 数与最强同信道 RSSI；
- 盲区分级：强 / 中 / 弱 / 盲区，并给出补点、信道规划、频段引导建议。

### 项目三 声振

- 递归扫描 `<session>/<conditionId>/g<NN>/*_meta.json`（排除 `blind/`）；
- 特征严格按 `docs/feature_spec.md` 第 4 节：Hann(4096)、帧起点 0/2048/…、`P=|FFT|²/N`、
  频带分母 20 Hz–20 kHz、`audio_peak_mag=sqrt(max P)`、谐波 `round(f0/bins)±2` 去重、
  振动 `fftSize=min(4096,nextPow2(N))`、hop=fftSize/2、不可算值写空字段；
- 与 App 的 `FeatureExtractor.kt` 逐条对齐；`tests/run_all.py` 用合成 1 kHz 正弦做自检；
- 模型：z-score（每折重拟合，std=0 置 1）+ kNN(k=3) + 1/(d+eps) 加权投票 + 平票取最近邻；
- 盲测：解析 `blind_test_*.csv`，并按 `features_file` 重新提取特征复核预测（表内预测 vs 脚本重算一致率）；
- 抗噪：白/粉红噪声按目标 SNR 叠加（峰值超满量程时整体缩放以保持 SNR），重新提特征分类；
  可选谱减法去噪，输出“去噪前/后”两条准确率曲线。

## 合成演示数据 vs 真机数据

- `data/demo/` 是 **synthetic** 合成数据（见 [data/demo/README.md](../data/demo/README.md)），
  用于验证管线；三条管线检测到 `data/demo` 或 manifest 里的 `synthetic: true` 时，
  会在报告开头标注 SYNTHETIC；
- 课程报告必须使用 `data/raw/<app>/<session>` 的真机导出数据；`docs/report/` 里的模板
  预留了与 `analysis/out/*/figures` 对应的图片占位与表格结构。

## 常见问题

1. **pip 安装失败（网络/代理）**：本机试验过的代理方式
   `python -m pip install --proxy http://<proxy-host>:<port> -r analysis/requirements.txt`；
   若 pip 因权限/临时目录失败，可手动下载 cp3xx 轮子解压后设置 `PYTHONPATH` 指向解压目录；
2. **中文字体显示为方块**：`plot_utils.setup()` 会优先使用 Microsoft YaHei / SimHei；
   Linux 服务器可安装 `fonts-noto-cjk`，或用 `MPLCONFIGDIR` 指定缓存目录；
3. **没有显示器/CI 环境**：所有图都用 `matplotlib.use("Agg")`，不会弹窗；
4. **WAV 缺失/损坏**：vibro 管线会报出具体文件；真机数据请按 SOP 重录而不是伪造；
5. **只跑某一类演示数据**：`python data/demo/generate_demo.py --only power`；
6. **内存/时间**：抗噪评估会对每个样本做多次 FFT，演示数据（15 组 × 3 s）约 12 s；
   真机数据（75 组 × 15 s）会显著变慢（预计 3–6 min），脚本已按“外层遍历样本、内层遍历噪声组合”
   控制内存（一次只保留一段音频）。赶时间可用 `--snr 20,0,-5` 或 `--no-denoise` 加速。

## 约束

- 原始数据只读：清洗/剔除只输出到 `analysis/out`，不覆盖 `data/raw`；
- 不修改 `core/`、`apps/`、根构建文件；本目录只依赖 numpy/pandas/matplotlib；
- 报告里出现的每个数字都应能由 `analysis/out/` 的表格或本文件描述的口径复现。