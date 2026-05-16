package com.example.dateapp.data

import java.time.ZoneId

object WuhanKnowledgeConfig {
    const val CITY = "武汉"
    const val CITY_FULL = "武汉市"
    const val AMAP_CITY = "武汉"

    const val WHU = "武汉大学"
    const val WHU_SHORT = "武大"
    const val HUBU = "湖北大学"
    const val HUBU_SHORT = "湖大"

    val whuCoreAreas = listOf(
        "街道口", "广埠屯", "珞珈", "珞喻路", "武大", "武汉大学"
    )

    val hubuCoreAreas = listOf(
        "湖大", "湖北大学", "徐东", "沙湖", "武昌万象城", "群星城", "水岸星城", "团结大道"
    )

    val cityCoreAreas = listOf(
        "楚河汉街", "汉街", "中南路", "洪山广场", "光谷"
    )

    val metroLines = listOf(
        "地铁", "metro", "2号线", "4号线", "7号线", "8号线"
    )

    val transitKeywords = listOf("地铁", "公交", "车站", "公交站", "BRT", "换乘") +
        whuCoreAreas + hubuCoreAreas + cityCoreAreas +
        listOf("群光", "银泰创意城", "凯德1818", "武汉天地", "光谷广场", "珞喻路", "中南路", "徐东大街")

    val remoteDistricts = listOf(
        "黄陂", "新洲", "蔡甸", "汉南",
        "郊野", "农庄", "度假村", "山庄", "村"
    )

    val tripWorthySignals = listOf(
        "东湖", "黄鹤楼", "湖北省博物馆", "省博", "美术馆", "博物馆",
        "江滩", "古德寺", "晴川阁", "园博园", "植物园",
        "汉口江滩", "武昌江滩", "汉阳造", "黎黄陂路", "武汉天地",
        "琴台", "归元寺", "昙华林"
    )

    val longPlaySignals = listOf(
        "黄鹤楼", "晴川阁", "古德寺", "江汉路", "武汉天地", "光谷",
        "剧场", "livehouse", "演出", "脱口秀", "展览", "美术馆", "艺术馆", "博物馆"
    )

    val aiAvoidDefaultSignals = listOf(
        "昙华林", "湖北省博物馆", "省博物馆", "湖北省科技馆",
        "东湖", "东湖绿道", "武汉大学万林", "万林艺术博物馆",
        "西西弗书店", "卓尔书店"
    )

    val specialtySnacks = listOf(
        "锅盔", "馄饨", "羊肉粉", "牛肉粉", "牛肉面",
        "水煎包", "烧鸭", "鱼鲜", "小吃", "热干面", "豆皮"
    )

    val studentFriendlyChains = listOf(
        "老乡鸡", "尊宝", "袁记", "塔斯汀", "简餐", "小吃",
        "家常", "砂锅", "牛肉粉", "热干面", "披萨", "云饺"
    )

    val luxuryTraps = listOf(
        "高端", "奢华", "会所", "私宴", "黑珍珠", "人均500", "人均800"
    )

    val mallKeywords = listOf(
        "万象城", "梦时代", "k11", "凯德", "群光", "银泰", "龙湖天街", "万达"
    )

    val genericPoiTitles = setOf(
        "武汉", "武汉市",
        "江岸区", "江汉区", "硚口区", "汉阳区", "武昌区", "青山区",
        "洪山区", "江夏区",
        "光谷", "街道口", "江汉路", "楚河汉街", "武汉天地",
        "汉口里", "广埠屯", "中南路"
    )

    const val EMERGENCY_LAT = 30.5609
    const val EMERGENCY_LNG = 114.3552
    const val EMERGENCY_LABEL = "武大与湖大之间"

    const val HUDA_MIXC_LAT = 30.5760
    const val HUDA_MIXC_LNG = 114.3435
    const val HUDA_MIXC_LABEL = "湖北大学-武昌万象城附近"

    val zoneId: ZoneId get() = ZoneId.of("Asia/Shanghai")

    fun isWuhanCoreArea(text: String): Boolean =
        whuCoreAreas.any(text::contains) ||
            hubuCoreAreas.any(text::contains) ||
            cityCoreAreas.any(text::contains)

    fun isRemoteDistrict(text: String): Boolean =
        remoteDistricts.any(text::contains)

    fun isSpecialtySnack(text: String): Boolean =
        specialtySnacks.any(text::contains)
}
