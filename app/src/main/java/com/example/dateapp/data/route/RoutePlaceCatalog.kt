package com.example.dateapp.data.route

import com.amap.api.services.core.PoiItem
import java.util.Locale

data class LandmarkCoordinate(
    val latitude: Double,
    val longitude: Double
)

object RoutePlaceCatalog {
    val mallSearchQueries = listOf(
        "购物中心",
        "商场",
        "百货",
        "mall"
    )

    val fallbackLandmarks = linkedMapOf(
        "江汉路" to LandmarkCoordinate(latitude = 30.5800, longitude = 114.2917),
        "万松园" to LandmarkCoordinate(latitude = 30.5922, longitude = 114.2761),
        "街道口" to LandmarkCoordinate(latitude = 30.5274, longitude = 114.3550),
        "珞喻路" to LandmarkCoordinate(latitude = 30.5254, longitude = 114.3658),
        "珞狮路" to LandmarkCoordinate(latitude = 30.5241, longitude = 114.3492),
        "中北路" to LandmarkCoordinate(latitude = 30.5588, longitude = 114.3392),
        "武大" to LandmarkCoordinate(latitude = 30.5367, longitude = 114.3638),
        "武汉大学" to LandmarkCoordinate(latitude = 30.5367, longitude = 114.3638),
        "东湖" to LandmarkCoordinate(latitude = 30.5544, longitude = 114.4031),
        "凌波门" to LandmarkCoordinate(latitude = 30.5397, longitude = 114.3640),
        "楚河汉街" to LandmarkCoordinate(latitude = 30.5615, longitude = 114.3352),
        "昙华林" to LandmarkCoordinate(latitude = 30.5529, longitude = 114.3095),
        "黎黄陂路" to LandmarkCoordinate(latitude = 30.5864, longitude = 114.3035),
        "吉庆街" to LandmarkCoordinate(latitude = 30.5807, longitude = 114.2894),
        "江滩" to LandmarkCoordinate(latitude = 30.5968, longitude = 114.3092),
        "光谷" to LandmarkCoordinate(latitude = 30.5066, longitude = 114.4110),
        "洪山广场" to LandmarkCoordinate(latitude = 30.5450, longitude = 114.3379),
        "黄鹤楼" to LandmarkCoordinate(latitude = 30.5446, longitude = 114.3048),
        "湖北省博物馆" to LandmarkCoordinate(latitude = 30.5635, longitude = 114.3717),
        "东湖绿道" to LandmarkCoordinate(latitude = 30.5580, longitude = 114.3940),
        "中山公园" to LandmarkCoordinate(latitude = 30.5814, longitude = 114.2748),
        "沙湖公园" to LandmarkCoordinate(latitude = 30.5722, longitude = 114.3511),
        "古德寺" to LandmarkCoordinate(latitude = 30.6187, longitude = 114.3073),
        "晴川阁" to LandmarkCoordinate(latitude = 30.5556, longitude = 114.2848),
        "江汉关博物馆" to LandmarkCoordinate(latitude = 30.5761, longitude = 114.2973),
        "辛亥革命博物院" to LandmarkCoordinate(latitude = 30.5392, longitude = 114.3056),
        "武汉革命博物馆" to LandmarkCoordinate(latitude = 30.5537, longitude = 114.3004),
        "湖锦酒楼" to LandmarkCoordinate(latitude = 30.5925, longitude = 114.2768)
    )

    val genericBrandStopWords = listOf(
        "附近",
        "餐厅",
        "美食",
        "公园",
        "景点",
        "打卡",
        "散步",
        "找一家",
        "找一个"
    )

    fun isShoppingMallPoi(poi: PoiItem): Boolean {
        val title = poi.title.orEmpty().lowercase(Locale.ROOT)
        val type = poi.typeDes.orEmpty().lowercase(Locale.ROOT)
        val text = poi.searchableText()

        val hasStrongMallSignal = mallStrongKeywords.any(title::contains) ||
            mallOfficialTypeKeywords.any(type::contains)
        val hasGenericMallSignal = mallGenericKeywords.any(title::contains) &&
            mallGenericQualifiers.any(title::contains)

        return (hasStrongMallSignal || hasGenericMallSignal) &&
            mallNegativeKeywords.none(text::contains) &&
            smallRetailKeywords.none(title::contains)
    }

    fun isBadDestinationPoi(poi: PoiItem): Boolean {
        val text = poi.searchableText()
        return badDestinationPoiKeywords.any(text::contains)
    }

    fun isBadPlayDestinationPoi(poi: PoiItem): Boolean {
        val text = poi.searchableText()
        val protectedPublicPlace = playPublicPlaceKeywords.any(text::contains)
        return !protectedPublicPlace && badPlayDestinationPoiKeywords.any(text::contains)
    }

    fun isPoiTypeCompatibleWithTarget(
        poi: PoiItem,
        targetText: String,
        category: String
    ): Boolean {
        if (category != "play") {
            return true
        }

        val target = targetText.lowercase(Locale.ROOT)
        val poiText = poi.searchableText()

        fun targetHasAny(keywords: List<String>) = keywords.any(target::contains)
        fun poiHasAny(keywords: List<String>) = keywords.any(poiText::contains)
        val targetLooksLikeSpecificShop = target.contains("店") ||
            target.contains("stand") ||
            target.contains("coffee", ignoreCase = true) ||
            target.contains("cafe", ignoreCase = true)
        val shopOrCafePoiKeywords = shopPlayPoiKeywords +
            cafePlayPoiKeywords +
            bookstorePoiKeywords +
            theaterPoiKeywords +
            mallPlayPoiKeywords

        return when {
            targetHasAny(parkTargetKeywords) -> poiHasAny(parkPoiKeywords) &&
                !poiHasAny(nonScenicPoiKeywords) &&
                !poiHasAny(foodServicePoiKeywords)
            targetHasAny(museumTargetKeywords) -> poiHasAny(museumPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(galleryTargetKeywords) -> poiHasAny(galleryPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(theaterTargetKeywords) -> poiHasAny(theaterPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(bookstoreTargetKeywords) -> poiHasAny(bookstorePoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(scenicTargetKeywords) -> poiHasAny(scenicPoiKeywords) &&
                !poiHasAny(nonScenicPoiKeywords) &&
                !poiHasAny(foodServicePoiKeywords)
            targetLooksLikeSpecificShop && poiHasAny(shopOrCafePoiKeywords) -> !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(creativeBlockTargetKeywords) -> poiHasAny(creativeBlockPoiKeywords) &&
                !poiHasAny(nonScenicPoiKeywords) &&
                !poiHasAny(foodServicePoiKeywords)
            targetHasAny(shopPlayTargetKeywords) -> poiHasAny(shopPlayPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(mallPlayTargetKeywords) -> poiHasAny(mallPlayPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(cafePlayTargetKeywords) -> poiHasAny(cafePlayPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            targetHasAny(religiousTargetKeywords) -> poiHasAny(religiousPoiKeywords) && !poiHasAny(nonScenicPoiKeywords)
            else -> !poiHasAny(nonScenicPoiKeywords)
        }
    }

    private fun PoiItem.searchableText(): String {
        return buildString {
            append(title.orEmpty())
            append(' ')
            append(snippet.orEmpty())
            append(' ')
            append(typeDes.orEmpty())
        }.lowercase(Locale.ROOT)
    }

    private val mallStrongKeywords = listOf(
        "购物中心",
        "商场",
        "商业中心",
        "mall",
        "万象城",
        "梦时代",
        "k11",
        "凯德",
        "群光",
        "银泰",
        "龙湖天街",
        "万达"
    )

    private val mallGenericKeywords = listOf(
        "百货",
        "广场"
    )

    private val mallGenericQualifiers = listOf(
        "购物",
        "商场",
        "商业",
        "中心",
        "大楼",
        "mall",
        "plaza"
    )

    private val mallOfficialTypeKeywords = listOf(
        "购物中心",
        "商场",
        "百货商场",
        "综合商场"
    )

    private val mallNegativeKeywords = listOf(
        "停车场",
        "出入口",
        "公交",
        "地铁",
        "小区",
        "住宅",
        "写字楼",
        "商行",
        "便利",
        "超市",
        "副食",
        "烟酒",
        "五金",
        "汽配",
        "洗车",
        "车洁",
        "网购",
        "维修",
        "批发",
        "专卖"
    )

    private val smallRetailKeywords = listOf(
        "商行",
        "便利店",
        "超市",
        "副食",
        "烟酒",
        "五金",
        "汽配",
        "洗车",
        "网购",
        "维修",
        "批发",
        "专卖"
    )

    private val badDestinationPoiKeywords = listOf(
        "公厕",
        "厕所",
        "卫生间",
        "停车场",
        "停车位",
        "出入口",
        "入口",
        "出口",
        "售票处",
        "游客中心",
        "服务区",
        "服务站",
        "公交站",
        "地铁站",
        "充电站",
        "警务室",
        "管理处"
    )

    private val playPublicPlaceKeywords = listOf(
        "公园",
        "景区",
        "景点",
        "绿道",
        "江滩",
        "广场",
        "博物馆",
        "美术馆",
        "艺术馆",
        "展览",
        "书店",
        "剧场",
        "影院",
        "livehouse",
        "街区",
        "历史文化",
        "商场",
        "购物中心",
        "商业中心",
        "百货",
        "买手店",
        "主理人",
        "杂货",
        "唱片",
        "黑胶",
        "潮玩",
        "盲盒",
        "玩具",
        "手作",
        "陶艺",
        "文创",
        "市集",
        "集市",
        "咖啡",
        "甜品",
        "奶茶",
        "生活方式",
        "中古",
        "快闪",
        "凌波门",
        "黄鹤楼"
    )

    private val badPlayDestinationPoiKeywords = listOf(
        "酒店",
        "宾馆",
        "民宿",
        "公寓",
        "客栈",
        "诊所",
        "医院",
        "医疗",
        "药房",
        "餐厅",
        "中餐厅",
        "小吃",
        "大烩菜",
        "火锅",
        "烤肉",
        "烧烤",
        "银行",
        "营业厅",
        "公司",
        "写字楼",
        "住宅",
        "小区",
        "足浴",
        "按摩"
    )

    private val parkTargetKeywords = listOf(
        "公园",
        "绿道",
        "江滩",
        "湖",
        "湖畔",
        "湖边"
    )

    private val parkPoiKeywords = listOf(
        "公园",
        "绿道",
        "江滩",
        "景区",
        "风景区",
        "景点",
        "广场",
        "游园",
        "湖"
    )

    private val museumTargetKeywords = listOf(
        "博物馆",
        "博物院",
        "纪念馆"
    )

    private val museumPoiKeywords = listOf(
        "博物馆",
        "博物院",
        "纪念馆",
        "展馆",
        "文化馆"
    )

    private val galleryTargetKeywords = listOf(
        "美术馆",
        "艺术馆",
        "画廊",
        "展览"
    )

    private val galleryPoiKeywords = listOf(
        "美术馆",
        "艺术馆",
        "画廊",
        "展览",
        "展馆"
    )

    private val theaterTargetKeywords = listOf(
        "影院",
        "电影院",
        "剧场",
        "剧院",
        "livehouse"
    )

    private val theaterPoiKeywords = listOf(
        "影院",
        "电影院",
        "剧场",
        "剧院",
        "livehouse",
        "演出",
        "演艺"
    )

    private val bookstoreTargetKeywords = listOf(
        "书店",
        "书城",
        "图书"
    )

    private val bookstorePoiKeywords = listOf(
        "书店",
        "书城",
        "图书",
        "阅读"
    )

    private val scenicTargetKeywords = listOf(
        "景点",
        "景区",
        "风景区",
        "名胜",
        "地标",
        "打卡点",
        "观景",
        "黄鹤楼",
        "晴川阁",
        "古德寺",
        "凌波门"
    )

    private val scenicPoiKeywords = listOf(
        "景点",
        "景区",
        "风景区",
        "名胜",
        "地标",
        "旅游",
        "观景",
        "公园",
        "江滩",
        "绿道",
        "黄鹤楼",
        "晴川阁",
        "古德寺",
        "凌波门"
    )

    private val creativeBlockTargetKeywords = listOf(
        "街区",
        "历史文化",
        "文创",
        "创意园",
        "昙华林",
        "黎黄陂路",
        "武汉天地"
    )

    private val creativeBlockPoiKeywords = listOf(
        "街区",
        "历史文化",
        "文化街",
        "文创",
        "创意园",
        "旅游",
        "商业街",
        "步行街",
        "昙华林",
        "黎黄陂路",
        "武汉天地"
    )

    private val shopPlayTargetKeywords = listOf(
        "小店",
        "买手店",
        "主理人",
        "杂货",
        "唱片",
        "黑胶",
        "潮玩",
        "盲盒",
        "玩具",
        "手作",
        "陶艺",
        "香薰",
        "文创",
        "市集",
        "集市",
        "中古",
        "生活方式",
        "快闪"
    )

    private val shopPlayPoiKeywords = listOf(
        "购物服务",
        "专卖店",
        "商店",
        "礼品饰品",
        "文化用品",
        "玩具",
        "音像",
        "书店",
        "文创",
        "手作",
        "陶艺",
        "市集",
        "集市",
        "展览",
        "生活服务",
        "休闲娱乐",
        "生活方式",
        "快闪"
    )

    private val mallPlayTargetKeywords = listOf(
        "商场",
        "购物中心",
        "商业中心",
        "百货",
        "mall",
        "k11",
        "万象城",
        "梦时代",
        "凯德",
        "群光",
        "银泰",
        "龙湖天街",
        "万达"
    )

    private val mallPlayPoiKeywords = listOf(
        "购物中心",
        "商场",
        "商业中心",
        "百货商场",
        "综合商场",
        "购物服务",
        "专卖店",
        "电影院",
        "休闲娱乐",
        "游乐",
        "电玩",
        "书店",
        "咖啡",
        "甜品"
    )

    private val cafePlayTargetKeywords = listOf(
        "咖啡",
        "甜品",
        "奶茶",
        "茶饮",
        "茶馆",
        "下午茶"
    )

    private val cafePlayPoiKeywords = listOf(
        "咖啡",
        "甜品",
        "奶茶",
        "茶饮",
        "茶馆",
        "冷饮店",
        "休闲餐饮",
        "餐饮服务"
    )

    private val foodServicePoiKeywords = listOf(
        "餐饮服务",
        "餐厅",
        "中餐厅",
        "外国餐厅",
        "小吃",
        "火锅",
        "烤肉",
        "烧烤",
        "茶艺馆",
        "冷饮店"
    )

    private val religiousTargetKeywords = listOf(
        "寺",
        "寺庙",
        "教堂",
        "道观"
    )

    private val religiousPoiKeywords = listOf(
        "寺",
        "寺庙",
        "教堂",
        "宗教",
        "道观"
    )

    private val nonScenicPoiKeywords = listOf(
        "酒店",
        "宾馆",
        "公寓",
        "民宿",
        "住宅",
        "小区",
        "写字楼",
        "公司",
        "商务",
        "产业园",
        "停车场",
        "出入口"
    )
}
