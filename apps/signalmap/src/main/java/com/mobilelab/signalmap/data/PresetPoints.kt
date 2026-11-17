package com.mobilelab.signalmap.data

/**
 * The eight built-in stations from the spec. They describe eight distinct
 * obstruction profiles so the Python pipeline can attribute attenuation to
 * wall material and multipath instead of random geometry.
 */
object PresetPoints {

    fun defaults(): List<SurveyPoint> = listOf(
        SurveyPoint(
            id = "P01",
            name = "教学楼 2F 走廊中段（基准点）",
            type = PointType.CORRIDOR,
            wallMaterial = WallMaterial.NONE,
            obstruction = "走廊两侧开放，无承重墙遮挡（基准点）",
            distanceM = 8.0,
            floor = 2,
        ),
        SurveyPoint(
            id = "P02",
            name = "走廊 90° 转角",
            type = PointType.CORNER,
            wallMaterial = WallMaterial.CONCRETE,
            obstruction = "两侧承重墙，转角 90°，视线被墙体遮挡",
            distanceM = 12.0,
            floor = 2,
        ),
        SurveyPoint(
            id = "P03",
            name = "电梯口",
            type = PointType.ELEVATOR,
            wallMaterial = WallMaterial.METAL,
            obstruction = "电梯金属门与井道形成金属遮挡与强反射",
            distanceM = 10.0,
            floor = 2,
        ),
        SurveyPoint(
            id = "P04",
            name = "楼梯间",
            type = PointType.STAIRWELL,
            wallMaterial = WallMaterial.CONCRETE,
            obstruction = "上下楼板与混凝土墙形成穿层衰减",
            distanceM = 15.0,
            floor = 3,
        ),
        SurveyPoint(
            id = "P05",
            name = "宿舍门口（金属门）",
            type = PointType.DORM,
            wallMaterial = WallMaterial.METAL,
            obstruction = "宿舍金属防盗门近距离反射与吸收",
            distanceM = 6.0,
            floor = 4,
        ),
        SurveyPoint(
            id = "P06",
            name = "教室靠窗（玻璃幕墙）",
            type = PointType.CLASSROOM,
            wallMaterial = WallMaterial.GLASS,
            obstruction = "玻璃幕墙透射，反射弱但存在多径",
            distanceM = 9.0,
            floor = 2,
        ),
        SurveyPoint(
            id = "P07",
            name = "实验室设备间（机柜）",
            type = PointType.LAB,
            wallMaterial = WallMaterial.MIXED,
            obstruction = "机柜金属面板、设备噪声与密集多径",
            distanceM = 5.0,
            floor = 1,
        ),
        SurveyPoint(
            id = "P08",
            name = "室外连廊/操场",
            type = PointType.OUTDOOR,
            wallMaterial = WallMaterial.NONE,
            obstruction = "室外开阔，仅远处建筑反射",
            distanceM = 30.0,
            floor = 1,
        ),
    )
}
