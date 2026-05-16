# 佳期 Jiaqi

佳期是一个面向情侣约会的 Android App。两个人每次出门前不知道今天要做什么，于是把"去哪儿、吃什么、怎么走"变成一个温柔、轻量、可刮开的决策流程。

它不是重后台的路线平台，也不是把 AI 摆在台前的聊天工具。核心理念：

- **真实可达**：跳过 AI 编造地名，直接从高德 POI 出发，每一个推荐都是可导航的真实地点。
- **隐形 AI**：AI 负责理解偏好、精选候选和润色文案，但不打断体验。
- **环境感知**：结合时间、位置、天气和路线，让推荐更像"现在就适合去"。
- **本地优先**：心愿、反馈和画像都保存在设备本地。
- **温润极简**：界面接近 iOS 式的干净、柔和、低负担。

## 推荐链路（AMap-First）

```
用户点击刮卡
    │
    ▼
环境快照（时间 / 定位 / 天气）
    │
    ▼
RecommendationTopicProvider（按画像和时间挑选主题）
    │
    ▼
RoutePlanningRepository（高德 POI 关键词搜索）  ← 所有候选都来自真实 POI
    │
    ▼
DecisionCandidateScorer（时间/天气/距离/画像/学生情侣 五维评分）
    │
    ▼
AiRepository.chooseDecisionPoiFromCandidates（AI 从候选中精选最优）
    │
    ▼
AiRepository.polishDecisionPoi（AI 润色 tag/intro 文案）
    │
    ▼
DecisionCardUiModel（刮刮乐结果卡）
    │
    ▼
DecisionReadyPool（后台空闲预取缓存）
```

旧版是先让 AI 想地名 → 再拿高德去校验，但 `deepseek-v4-flash` 编造的地名有 92% 无法在高德找到。现在反过来了：**高德 POI 是唯一真实源**，AI 退到精选和润色环节，推荐质量从根源上得到保证。

## 评分体系

每个 POI 候选经过本地五维评分，总分决定排序：

| 维度 | 负责组件 | 说明 |
|---|---|---|
| **基础分** | `buildBaseScore` | 时间匹配 ×3 + 天气匹配 ×3 + 置信度 + 距离 |
| **个性化分** | `personalizationScore` | 基于本地画像的特征匹配（江边/live/博物馆/手作/电玩/桌游……） |
| **区域偏好** | `diningAreaPreferenceScore` | 湖大万象城/户部巷/武汉特色小吃区域加成 |
| **约会吸引力** | `dateAppealScore` | DIY 手作/展览/密室等具体体验加分，通用街区扣分 |
| **学生情侣画像** | `studentCoupleProfileScore` | 公交可达性 + 经典浪漫 + 惊喜感 + 校园深度 + 学生预算 |

## 个性化画像

佳期在本地维护个性化画像，不依赖外部用户画像平台：

- **特征提取**：从推荐文案、地点名、POI 类型中提取特征（江边、livehouse、手作、桌游、猫咖、书店、展览……）
- **正反馈**：保存到心愿池、点击导航、点击小红书搜索 → +1~+3
- **负反馈**：右滑不感兴趣 → -3，长期衰减
- **时间加权**：最近操作权重更高，旧数据逐渐衰减
- **同名去重**：近期推荐过的地点进入避用列表（最多 36 个软避用 + 64 个硬避用）

所有画像数据保存在 `SharedPreferences`，不上传任何服务器。

## 架构概览

```
Compose UI (WishList / Decision / Timeline)
    │
    ▼
ViewModels (StateFlow)
    │
    ├── WishRepository (Room)
    ├── DecisionEngine (AMap-First 决策引擎)
    │   ├── AiRepository (OpenAI-compatible API)
    │   ├── RoutePlanningRepository (高德 POI/路线/地理编码)
    │   ├── DecisionCandidateScorer (五维评分)
    │   ├── DecisionPlacePolicy (距离/时间/营业策略)
    │   └── WuhanKnowledgeConfig (武汉地理知识)
    ├── DecisionEnvironmentRepository (定位 + 天气)
    └── RecommendationFeedbackStore (SharedPreferences 画像)
```

项目没有自定义后端服务器。外部依赖仅有：AI API（DeepSeek）、高德定位/搜索/路线、天气接口。

## 技术栈

- Kotlin + Jetpack Compose
- Material 3
- Room + Flow
- ViewModel + StateFlow
- Retrofit + OkHttp + Gson
- 高德地图 3D 地图/定位/搜索整合包
- Coil Compose
- Java 17 / Min SDK 26 / Target SDK 36

## 目录结构

```text
app/src/main/java/com/example/dateapp
├── AppContainer.kt                    # 手动依赖注入
├── MainActivity.kt                    # Compose 主入口
├── data
│   ├── local                          # Room entity / dao / database
│   ├── remote                         # AI、天气、高德 HTTP 接口
│   ├── decision                       # 决策引擎、评分、策略
│   ├── environment                    # 时间、位置、天气上下文
│   ├── place                          # 推荐地点解析
│   ├── route                          # 高德路线、POI 搜索
│   └── recommendation                 # 本地画像、主题池
└── ui
    ├── wish                           # 心愿池
    ├── decision                       # 刮刮乐决策、预取池
    ├── timeline                       # 行程/导航
    ├── components                     # 通用组件
    └── theme                          # 温润极简主题
```

## 本地配置

项目读取根目录 `local.properties`（已 `.gitignore`，不提交密钥）：

```properties
sdk.dir=C\:\\Users\\your-name\\AppData\\Local\\Android\\Sdk

ai.baseUrl=https://your-openai-compatible-endpoint/
ai.apiKey=YOUR_AI_API_KEY
ai.model=deepseek-v4-flash
ai.decisionModel=deepseek-v4-flash

amap.apiKey=YOUR_AMAP_API_KEY
```

- `ai.baseUrl` 需要以 `/` 结尾，代码会兜底补齐。
- AI 接口使用 Chat Completions 风格，Bearer Token 认证。
- `amap.apiKey` 注入到 `AndroidManifest.xml` 的高德 `meta-data`。

## 构建与运行

Android Studio 打开项目根目录，Gradle Sync 完成后运行 `app`。

命令行：

```powershell
./gradlew.bat assembleDebug
```

安装到设备：

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r "app\build\outputs\apk\debug\app-debug.apk"
```

## 关键日志

联调推荐链路时可关注这些 tag：

| Tag | 内容 |
|---|---|
| `DecisionEngine` | `ENGINE_AMAP_FIRST` 启动、`ENGINE_SELECTED` 结果、评分详情 |
| `RoutePlanning` | POI 搜索关键词、返回结果、过滤原因 |
| `AiRepository` | AI 精选候选 (`poi_choice`) 和文案润色 (`polish`) |
| `DecisionViewModel` | 刮卡、缓存命中、用户反馈、画像更新 |

关键信号：

```text
decision source=ENGINE_AMAP_FIRST timeout=9000ms targetCategory=play topic=街机电玩城
decision source=ENGINE_SCORE name=XX base=14 personal=6 student=12 final=32
decision source=AI_CHOICE_SELECTED name=XX base=26 boosted=34
decision source=ENGINE_SELECTED mode=AMAP_FIRST name=XX score=34 confidence=HIGH distance=1.4km
```

## 设计原则

Warm Minimalism：

- 大圆角、柔和背景、轻阴影。
- 刮刮乐、页面切换使用弹簧或缓动动画。
- 加载时卡片边缘呼吸发光，不用生硬转圈。
- AI 隐身，用户看到的是地点、路线和下一步行动。

## 隐私边界

- 心愿池保存在本地 Room。
- 个性化画像保存在 SharedPreferences。
- 没有自建后端，不上传用户数据。
- 第三方 API 仅用于用户主动触发的推荐、定位、天气和路线。

## 当前验证

最近一次真机验证（2026-05-16）：

- 设备：`24053PY09C`（Redmi）
- 构建：`assembleDebug`，Kotlin 编译通过，无 lint 错误
- 安装：`adb install -r app-debug.apk` 成功
- 启动：成功，无 `FATAL EXCEPTION`
- 推荐链路：AMap-First 全链路打通，连续推荐全部命中真实 POI
- 环境链路：高德定位与天气接口返回有效上下文
- AI 链路：`deepseek-v4-flash` 在精选和润色环节稳定工作
- 低质量过滤：已过滤休息亭/凉亭/纪念林/公厕/停车场等非约会场所

示例推荐结果：

| 主题 | 推荐 | 距离 |
|---|---|---|
| 脱口秀/小剧场 | 果然戏剧小剧场(汉街万达店) | 2.2km |
| 街机电玩城 | 汉街次元疯爆电玩社 | 1.4km |
| 江边散步 | 水果湖码头 | 1.0km |
| 唱片店 | 小宋唱片(汉街总店) | 1.8km |
| 异国料理 | 印度玛咖(银泰创意城店) | 137m |
| 日料 | 九久煮艺(湖大店) | 654m |

## 后续方向

- 整理包名和签名配置为正式发布形态。
- 增加画像解释页，让用户看到"佳期为什么这样推荐"。
