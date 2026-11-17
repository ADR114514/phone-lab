package com.mobilelab.powertrace.data

/**
 * 课题02 的四种测量工况。
 *
 * [id] / [label] 是 `docs/DATA_DICTIONARY.md` 1.1 的数据契约，属于对外接口，
 * 不得重命名；顺序通过 [defaultOrder] 表达，但实验允许在向导里调整（block 随之改变）。
 */
enum class ConditionId(
    val id: String,
    val label: String,
    val brief: String,
    val operatorTip: String,
    val coolingTip: String,
    val prepTip: String,
) {
    IDLE(
        id = "IDLE",
        label = "待机",
        brief = "熄屏静置，测量系统后台基线功耗",
        operatorTip = "点击开始后立即按电源键熄屏，手机平放桌面，不要移动、不要充电。",
        coolingTip = "两次待机测量间隔建议 ≥10 min，避免上一次的余温抬高温度基线。",
        prepTip = "建议关闭蓝牙/GPS 扫描，保留蜂窝与 WiFi 以贴近日常待机场景。",
    ),
    VIDEO(
        id = "VIDEO",
        label = "短视频",
        brief = "连续播放 1080p 短视频（30 min）",
        operatorTip = "打开短视频 App，全屏循环播放同一视频，音量固定 50%，期间不切换视频。",
        coolingTip = "播放前静置 5 min 降温，避免上一工况余热抬高起点温度。",
        prepTip = "确认 App 已登录、推荐流稳定、亮度已固定在目标值。",
    ),
    NAVCAM(
        id = "NAVCAM",
        label = "导航拍摄",
        brief = "导航 + 相机录像预览同时运行（30 min）",
        operatorTip = "开始导航并进入相机 1080p30 录像预览，屏幕保持常亮，路线固定。",
        coolingTip = "拍摄前检查机身温度 < 38 °C，否则先风冷 5 min。",
        prepTip = "确认定位已开启、导航已规划好路线、相机可正常预览。",
    ),
    GAME(
        id = "GAME",
        label = "高帧率游戏",
        brief = "高帧率游戏持续渲染（30 min）",
        operatorTip = "进入支持 60/90/120 fps 的游戏，同一画面对战或循环跑图，画质档位固定。",
        coolingTip = "游戏前静置降温，建议使用相同起始电量区间（例如 80% ±5%）。",
        prepTip = "确认帧率档位固定、开启免打扰、后台无其他重负载应用。",
    ),
    ;

    companion object {
        fun of(id: String): ConditionId? = entries.firstOrNull { it.id == id }

        /** 默认采集顺序：A:IDLE → B:VIDEO → C:NAVCAM → D:GAME。 */
        val defaultOrder: List<ConditionId> = listOf(IDLE, VIDEO, NAVCAM, GAME)

        /** 采集顺序决定 block 字母（A/B/C/D），用于抵消顺序与温度漂移效应。 */
        fun blockLetter(orderIndex: Int): Char = ('A' + orderIndex.coerceIn(0, 3))
    }
}
