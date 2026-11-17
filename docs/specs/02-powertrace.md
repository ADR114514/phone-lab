# 课题02 应用规格 / PowerTrace

包名 `com.mobilelab.powertrace`；App 名称"功耗热测仪"；minSdk 31 / targetSdk 36 / arm64-v8a；Compose + core 库。

## 目标

30 分钟内每 5 分钟采一次电量与发热数据，覆盖 4 种工况（待机 / 短视频 / 导航拍摄 / 高帧率游戏），
自动生成 6 行/工况的会话 CSV、24 行批次 CSV 与工况汇总，并导出给 `analysis/power`。

## 权限（按需，全部在代码中使用前动态申请或引导）

`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`(33+)、`WAKE_LOCK`、
`ACCESS_NETWORK_STATE`、`SCHEDULE_EXACT_ALARM`(特殊)、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`(特殊)、
`PACKAGE_USAGE_STATS`(appop，可选，用于记录前台包名)。不需要存储/定位/相机权限。

## 页面

1. **首页**：项目说明卡片；当前批次状态（批次号、已完成工况、当前工况进度）；按钮"新建批次/继续批次/查看数据/设置"。
   设备信息卡（型号/Android/SoC/刷新率/电池设计容量）。四工况说明列表（含操作提示与降温建议）。
2. **批次向导**：输入实验备注、选择工况顺序（默认 A:IDLE → B:VIDEO → C:NAVCAM → D:GAME，可换序），
   显示 30 min/工况与总时长；开始前检查：未充电、屏幕亮度固定、定位/WiFi 状态（按工况提示）、
   电池优化豁免、精确闹钟权限、通知权限（逐项状态 + 一键跳转）。
3. **采集页**：大号指标（当前电流 mA、功率 W、电池温度 °C、thermal_status、CPU 温度/频率、剩余时间）；
   实时折线图（最近 6 个采样点：电流/温度两张）；进度 "第 k/6 个采样点，距下次 04:59"；
   前台服务常驻通知显示工况与进度；"暂停/继续"（暂停不写入）、"提前结束本工况"（二次确认，保留已采数据）、"放弃批次"。
   每到一个 5 分钟点：短暂唤醒采样 → 写 CSV + session.log → 更新 UI/通知。
4. **结果页**：4 个工况卡片（平均功率、能量 Wh、掉电 %、温升、thermal 最高）；24 行合并表预览；
   导出（批次 CSV / 汇总 CSV / 全部 ZIP）、分享、逐文件导出；重新运行某工况。
5. **设置**：采样间隔固定 300 s（仅展示）；原始 5 s 序列开关（默认关）；通知权限、电池优化、精确闹钟、使用情况访问入口；关于/版本。

## 采样实现

- 前台服务 `SamplingService`（`foregroundServiceType="dataSync"`）承载会话；`Handler` + `AlarmManager.setExactAndAllowWhileIdle` 双保险
  （无精确闹钟权限时退化为 Handler 并在 UI 提示误差风险）。
- 采样时获取一次 `PARTIAL_WAKE_LOCK`（≤8 s），采完释放；**不得整场持锁**（会污染待机功耗测量）。
- 每次采样写一行到 `session_<cond>.csv`（追加写，崩溃可恢复）；同时更新 `manifest.json` 与 `session.log`。
- 状态持久化到 `SharedPreferences`（批次/工况/已采样本数/开始时间），进程被杀后可恢复并补采。
- 采样内容严格按 `docs/DATA_DICTIONARY.md` 1.1 列；不可用字段写空。
- 计算 `power_w = voltage_mv * |current_now_ua| / 1e9`；`net_rx_kbps` 用 `TrafficStats` 差分 / 采样间隔。
- `fps`：仅当 App 在前台时用 Choreographer 统计最近 2 s 帧率；后台为空。
- `foreground_pkg`：若已授权使用情况访问，取最近 10 s 内 `ACTIVITY_RESUMED` 的包名；否则空。
- 采样点缺失（如设备休眠导致延迟 >90 s）时照常写入并 note 标记 `late_sample`，不得静默丢弃。

## 验收

- `assembleDebug` 通过；无未处理异常；权限拒绝时降级并给出说明。
- 完整跑 4×30 min 后导出目录符合数据字典，`campaign_*.csv` 恰好 24 行。
- 待机工况屏幕熄灭 30 min 仍能采到 6 个点（允许 ±20 s 误差）。
- 单元测试：`power_w` 计算、能量积分、CSV 行数、批次状态机。

## 写入范围

只改 `apps/powertrace/**`。不要改 `core/`、`gradle/`、根构建文件、其他 App。需要 core/依赖变更时先告知 lead。
