# 课题03 应用规格 / SignalMap

包名 `com.mobilelab.signalmap`；App 名称"校园信号测绘"；minSdk 31 / targetSdk 36 / arm64-v8a；Compose + core 库 + CameraX + OkHttp。

## 目标

8 个不同遮挡特征点位，每点连续 3 次测量信号强度与延迟，生成 24 行原始数据、点位照片与邻道扫描明细，
供 `analysis/wifi` 分析墙体材质与多径衰减规律。

## 权限（按需动态申请）

`ACCESS_WIFI_STATE`、`CHANGE_WIFI_STATE`、`ACCESS_NETWORK_STATE`、`INTERNET`、
`ACCESS_FINE_LOCATION`、`ACCESS_COARSE_LOCATION`、`NEARBY_WIFI_DEVICES`(33+，`neverForLocation`)、`CAMERA`。
定位开关必须打开（Android 12+ 读取 RSSI/扫描结果的前提），未开启时显示引导。

## 页面

1. **首页**：项目说明、点位完成度（8 个点 × 3 次进度环/条）、"开始/继续测量"、"查看结果"、"设置"。
   环境检查卡：WiFi 是否连接、SSID/RSSI、定位开关、定位权限、相机权限、附近 WiFi 权限（33+），每项红/绿状态 + 一键修复按钮。
2. **点位列表**：内置 8 个预设点位（见下），可编辑名称/类型/墙体材质/遮挡描述/估计距离/楼层；显示每个点已完成次数（0–3）。
3. **测量页**：顶部点位信息；照片区（CameraX 预览 + 拍照，照片存入 `photos/<pointId>_<stamp>.jpg`，可重拍/跳过）；
   "开始本点第 n/3 次测量"；测量过程分步进度（读取连接信息 → 扫描邻道 → 网关 RTT → 互联网 TCP RTT → HTTP TTFB → 写入）；
   实时 RSSI/延迟；完成后自动 +1 次；3 次完成给出"下一点位"引导。
4. **结果页**：24 行表格预览；按点位聚合的柱状图（RSSI 均值 / 延迟均值）；导出 CSV/ZIP；分享。
5. **设置**：HTTP 测试 URL（默认 `https://connectivitycheck.gstatic.com/generate_204`）、TCP 目标（默认 223.5.5.5:443）、
   每轮 RTT 次数（默认 20）、重复次数固定 3、是否测吞吐（默认关，512 KB 上限）、重置进度。

## 内置点位（可编辑）

| id | 名称 | type | wall_material |
|---|---|---|---|
| P01 | 教学楼 2F 走廊中段（基准点） | corridor | none |
| P02 | 走廊 90° 转角 | corner | concrete |
| P03 | 电梯口 | elevator | metal |
| P04 | 楼梯间 | stairwell | concrete |
| P05 | 宿舍门口（金属门） | dorm | metal |
| P06 | 教室靠窗（玻璃幕墙） | classroom | glass |
| P07 | 实验室设备间（机柜） | lab | mixed |
| P08 | 室外连廊/操场 | outdoor | none |

## 测量口径（必须与数据字典 2.3 一致）

- 每点 3 次重复，每次间隔 ≥30 s；每轮：
  1. `WifiManager.getConnectionInfo()` 取 rssi/linkSpeed/frequency/SSID/BSSID；
  2. `startScan()`（节流时用 `getScanResults()` 缓存）取邻道 AP，写 `scan_*.csv`；
  3. 网关：由 `dhcpInfo.gateway`（无则跳过）TCP 建连 5 次；
  4. 互联网 RTT：Socket 到 `223.5.5.5:443` 20 次，超时 1000 ms，统计 min/avg/max/median/p95/标准差/失败率；
  5. HTTP：OkHttp 请求测试 URL 5 次，记录 TTFB 与总耗时（含 DNS 解析时间）；
  6. 汇总行写入 `survey_<session>.csv`（追加，崩溃可恢复），同时写 `points.csv` 与 `manifest.json`。
- 位置/权限不满足时禁止开始测量并明确提示，不允许编造数据（缺失字段留空并写 note）。
- 吞吐测试（可选）：固定 512 KB 下载，记录 kbps；默认关闭。
- BSSID 在导出时默认保留原值，SOP 要求公开仓库前做哈希脱敏；App 设置里提供"导出时哈希 BSSID"开关（默认开）。

## 验收

- 8 点位 × 3 次 = 24 行；照片可拍、可重拍、路径写入 CSV；扫描明细文件齐全；导出 ZIP 内容完整。
- 拒绝定位权限/关闭定位/WiFi 未连接时，UI 给出明确修复路径且不产生假数据。
- 单元测试：RTT 统计（含丢包）、CSV 行、点位进度状态机、BSSID 哈希。

## 写入范围

只改 `apps/signalmap/**`。不要改 `core/`、`gradle/`、根构建文件、其他 App。需要 core/依赖变更时先告知 lead。
