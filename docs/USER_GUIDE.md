# 用户使用指南（三部 App 真机操作）

> 安装包：`apps/<app>/build/outputs/apk/debug/<app>-debug.apk`（Android 12+，arm64 真机）。
> 安装后按本文操作；每步的数据都会落在 App 私有目录，导出用 App 内"导出/分享"按钮。

## 通用准备

1. 打开开发者选项 + USB 调试（可选，用于 `tools/pull-data.ps1` 直接取数据）。
2. 首次打开 App 会请求所需权限，逐项允许；拒绝会导致对应功能不可用（App 会提示原因）。
3. 建议关闭省电模式，并在系统设置里给实验 App 关闭"电池优化"。
4. 导出文件默认 CSV（UTF-8 BOM，Excel 可直接打开）；WAV/ZIP 通过系统分享或文件选择器保存。
5. 数据也可用电脑拉取：`tools/pull-data.ps1 -Package com.mobilelab.powertrace -AppTag power`。

---

## 一、PowerTrace 功耗热测仪（课题02）

### 首次配置
1. 首页 → 设置：允许通知；点击"电池优化豁免"→ 允许；点击"精确闹钟"→ 允许；可选"使用情况访问"。
2. 确认手机未充电、自动亮度关闭、亮度固定。

### 跑一个批次（4 工况 × 30 min）
1. 首页 → 新建批次 → 填备注（环境温度、WiFi 状态）→ 确认工况顺序（默认 待机→短视频→导航拍摄→游戏）。
2. 每个工况开始前，按提示把手机置于对应场景（如待机就锁屏放桌上），点"开始本工况"。
3. 采集页会显示剩余时间与下一次采样倒数；中途可看实时电流/温度；**不要退出 App**（可锁屏）。
4. 30 min 后自动完成，提示"静置 10 min 降温"；然后点"下一工况"。
5. 四个工况跑完 → 结果页 → "导出批次 ZIP"，把目录拷到电脑 `data/raw/power/`。

### 数据检查
- 结果页应显示 24 行；每个工况 6 行。
- 若某工况标记"充电/异常/迟到"，结果页会标红 —— 按 SOP 重跑该工况。

---

## 二、SignalMap 校园信号测绘（课题03）

### 首次配置
1. 连接校园 WiFi（或要测的目标 SSID）。
2. **打开系统定位开关**；App 首页环境检查卡应全绿（定位权限、附近 WiFi 权限、WiFi 已连接）。
3. 允许相机权限用于点位拍照。

### 测一个点位（每点 3 次）
1. 首页 → 开始/继续测量 → 选中 P01…P08（可先编辑名称/墙体材质/遮挡/距离）。
2. 在点位站定（建议地面做标记），手机竖屏朝固定方向。
3. 首次到该点：拍照（拍墙体与 AP 方向）；然后点"开始第 1 次测量"。
4. 等待 30–60 s（自动完成扫描/RTT/HTTP）→ 自动记录；重复 3 次（间隔 ≥30 s）。
5. 8 个点位全部完成后 → 结果页 → 导出 ZIP（含 survey CSV、照片、扫描明细）。

### 注意
- 定位关掉 / 未连 WiFi 时 App 不允许开始，这是 Android 12 的限制，不是 Bug。
- 扫描结果被系统节流时（部分机型）会记录"受限"，可在 note 里注明。

---

## 三、VibroDiag 声振诊断仪（项目三）

### 首次配置
1. 允许录音权限（以及通知权限）；确认手机壳不遮挡麦克风。
2. 首页会显示麦克风能力（是否支持 UNPROCESSED、加速度最高采样率）。

### 采集一组（每工况 15 组）
1. 固定手机：平放在设备正前方 30 cm，麦克风朝向设备（用胶带/书做位置标记）。
2. 选择工况（C1…C5）→ 组号自动 → 点"录制" → 3-2-1 倒计时 → 15 s 保持安静。
3. 录完自动保存 WAV/CSV/特征，并显示本组 RMS、峰值频率、加速度 RMS。
4. 异常组（削波/中断/明显走动）点"重录本组"。
5. 5 工况 × 15 组完成后：评估页 → 生成评估报告（LOOCV、混淆矩阵、抗噪曲线）→ 导出 ZIP。

### 盲测（录视频用）
1. 首页 → 盲测 → 按提示每工况 3 组（共 15 组），录制时**不显示真实工况**。
2. 每组录完点"预测"（显示预测与置信度）→ 点"揭示真实标签"→ 自动记录对错。
3. 15 组结束后显示准确率与混淆矩阵；**从开始盲测就录屏**，这就是交付的"盲测准确率视频"。

### 抗噪评估
1. 评估页 → 抗噪 → 选白噪声/粉红噪声与 SNR（20/10/5/0/-5 dB）→ 开始。
2. 得到准确率-SNR 表与曲线 → 导出进 ZIP / 复制到报告。

---

## 数据交给分析管线

```bash
# 把手机导出的目录放到：
data/raw/power/<session>/     # PowerTrace ZIP 解压后
data/raw/wifi/<session>/      # SignalMap ZIP 解压后
data/raw/vibro/<session>/     # VibroDiag ZIP 解压后

# 跑分析
python analysis/power/analyze.py --data data/raw/power/<session> --out analysis/out/power
python analysis/wifi/analyze.py  --data data/raw/wifi/<session>  --out analysis/out/wifi
python analysis/vibro/analyze.py --data data/raw/vibro/<session> --out analysis/out/vibro
```

分析输出：`analysis/out/<x>/figures/*.png`、`tables/*.csv`、`*_report.md`；把图表插入 `docs/report/` 下的报告模板即可。
