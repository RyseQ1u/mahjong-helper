# 模块说明（仓库文档）

本工程按《MVP 开发计划》第 3 节的架构拆分模块。当前进度：
**P0–P5 全部完成，storage 持久化增强项也已实现（设置/局历史/样本元数据）。**

## 目录与模块映射

| 计划模块 | 本仓库位置 | 状态 |
| --- | --- | --- |
| app-android（Compose UI / 权限 / 设置） | `ui/`、`MainActivity.kt`、`util/` | ✅ 已实现（采集 UI + 对局配置） |
| capture-android（MediaProjection 前台服务） | `capture/CaptureService.kt`、`CaptureStateHolder.kt`、`FrameSaver.kt` | ✅ 已实现 + 模拟器验收 |
| vision-core（视口 / 模板匹配 / 状态分类） | `vision/BlackBarViewportDetector.kt`、`vision/NccTileTemplateMatcher.kt`、`vision/ScreenRecognizer.kt`、`vision/HeuristicScreenStateClassifier.kt`、`vision/SubRegionPixelAccessor.kt`、`domain/vision/*` | ✅ 框架已实现（真实模板待样本） |
| vision-weile（微乐界面适配：牌面模板/布局校准） | `domain/vision/TileLayout.kt`（默认布局为估算值） | 🟡 框架就绪，**待真实截图校准** |
| game-state（状态机 / 事件时序） | `domain/game/GameModels.kt`、`GameStateMachine.kt`、`TileLedger.kt` | ✅ 已实现 + 15 个单测 |
| mahjong-core（牌编码 / 向听 / 胡牌 / 进张） | `domain/mahjong/Tile.kt`、`TileCounts.kt`、`HandEvaluator.kt`、`MahjongCore.kt` | ✅ 计算引擎已实现 + 29 个单测 |
| analysis-engine（候选舍牌排序） | `domain/analysis/AnalysisEngine.kt`、`analysis/AnalysisPipeline.kt` | ✅ 已实现 + 13 个单测 |
| rules-liuzhou（柳州规则） | `domain/rules/RulesLiuzhou.kt` | ✅ 胡型判定已实现 + 8 个单测 |
| settlement（事件计账 / 计分策略） | `domain/settlement/Settlement.kt`、`ScoringPolicy.kt`、`ActionLedger.kt` | ✅ 已实现 + 24 个单测（冲突分支暂不支持） |
| storage（Room：偏好 / 事件 / 样本） | `storage/AppDatabase.kt`、`RoomRepositories.kt`、`domain/storage/Repository.kt` | ✅ 已实现 + 8 个单测（模拟器验收持久化） |
| overlay-android（悬浮窗） | `overlay/FloatingOverlay.kt`、`domain/overlay/*` | ✅ 已实现（建议展示 + 自掩码） |
| tests | `app/src/test/...` | ✅ 110 个纯 JVM 单测全绿（双变体） |

## mahjong-core 设计要点（P1/P2）

- **牌编码**：`Tile` 为 0..33 的 value class（0–8 万 / 9–17 筒 / 18–26 条 / 27–30 风 / 31–33 箭）；
  `hasDragons=false` 时仅使用 0..30（124 张 / 31 种），切换牌集必须清空本局状态。
- **守恒校验**：`TileCounts.validate(melds)` 校验每种牌 ≤4 张、手牌张数为 `13-3*melds` 或 `14-3*melds`
  （副露会从手牌拿走牌，注意不是加）。
- **向听**：日麻定义（-1 已和 / 0 听牌 / n n 向听），标准形递归 + 七对形取最小。
- **判型**：基本胡 / 七对 / 碰碰胡 / 清一色；七对采用宽松口径（是否禁止四张同牌凑对待规则核实）。
- **隔离**：`HandEvaluation`（本模块）与 `ScoringPolicy`、`ActionLedger` 彻底隔离——
  计划 2.3 节歧义未核实前不输出任何确定性收益数字。
- **舍牌排序**：向听升序 → 有效进张数降序；进张按剩余可见张数过滤
  （`remainingVisible = max(0, 4 - 自家 - 弃牌 - 副露 - 其他已知)`）。

## vision-core 设计要点（P1）

- **接口隔离**：`ViewportDetector`（Bitmap）/ `PixelViewportDetector`（PixelAccessor）双入口，
  使视口检测、模板匹配、画面识别全链路可在无 Android 运行时的 JVM 测试中验证。
- **NCC 匹配**：`NccTileTemplateMatcher` 零均值归一化互相关，区域重采样到模板尺寸后比较；
  平坦区域（纯黑/纯白）直接判 0 分；**模板库为空时一律返回 unknown**。
- **状态分类**：`HeuristicScreenStateClassifier` 目前只覆盖「整屏近黑 → UNKNOWN」（防截图保护场景），
  PREPARING / OWN_TURN / ANIMATION / SETTLEMENT 的精确判定需要真实截图中的文字/锚点特征后补齐。
- **布局**：`TileLayout.DEFAULT` 为基于单张截图描述的估算布局（归一化坐标），
  **尚未在多设备真实样本上校准**；接入真实截图后必须逐区域核对。

## game-state 设计要点（P3）

- **帧差事件推导**（`DefaultGameStateMachine.observe`）：
  1. 弃牌区多重集合差异 → 新弃牌（持续出现的弃牌不重复计数，天然去抖）；
  2. 副露对账 → 新副露从其他座位**最近的未消耗弃牌**查找来源（后进先出），
     来源弃牌标记 `consumedByMeld`，弃牌区与副露不重复计数；
  3. 自家手牌差异 → 新增=摸牌，减少=出牌；属于本帧新副露的消失、或已由弃牌区差异记录的
     同一次出牌，不重复记事件。
- **冻结闸门**：ANIMATION / UNKNOWN / 未知牌率 > 50% / 等待人工确认 → 冻结，状态不变
  （方案 3.1：动画和 UNKNOWN 期间停止自动更新可疑数据）。
- **守恒校验与回滚**：每帧结束后校验「自家手牌 + 有效弃牌 + 副露」每种 ≤ 4 张；
  失败即回滚到最后可靠状态（`lastReliableSnapshot`）并进入等待确认。
- **人工介入**：`correctOwnHand`（修正手牌）、`confirmState`（解除冻结）。
- **自动重置**：PREPARING / SETTLEMENT / WIN → 新一局（局数 +1，状态清空，重新采纳基线）；
  同一阶段连续帧不重复重置。
- **基线采纳**：首帧带数据的观测只作基线（可能是牌局中途进入），不产生事件。
- **已知近似（文档化）**：其他三家暗牌张数按休息位 `13 - 3*副露数` 估算，不追踪其摸牌
  （不可观测）；只影响 informational 的 handCounts，不影响守恒校验与进张计算。
- **单帧不推断来源与先后**（方案 3.2 第 3 点）：事件只来自帧间差异与去抖。

## analysis-engine 设计要点（P4）

- **管线**（`analysis/AnalysisPipeline.kt`）：帧 → ScreenRecognizer → `toFrameObservation`
  → GameStateMachine.observe → RecommendationEngine.advise → 悬浮窗。
- **节流**：按 `AnalysisSettings.analysisIntervalMs`（250/500/1000ms 三档）触发分析，
  不在每帧上跑识别。
- **自掩码**（方案 3.2 第 6 点）：分析前隐藏自己的悬浮窗，并丢弃随后
  `framesToSkipAfterMask` 帧（这些帧在隐藏前渲染、仍含悬浮窗），之后取到的第一帧
  才保证干净，避免推荐文字污染截图识别。
- **停止即停荐**：采集停止/无权限时 `onCaptureStopped()` 清空状态机与建议。
- **引擎**（`domain/analysis/AnalysisEngine.kt`）：
  - 守恒冲突 / 未识别牌 → 「等待确认」，不带着错误状态继续推算；
  - 自己回合且已和牌 → 「可以和牌（自摸）」；
  - 否则按模式给最多 3 个候选：SPEED（进张最广）/ BALANCED（进张+安全加权）/
    SAFETY（已见张数多者优先，仅有限信息估计，不读对手暗牌）；
  - `recognitionConfidence` 只表示识别置信度，UI 明示「非胜率」。
- **设置**（`AnalysisSettings`）：hasDragons（136/124，**切换即清空本局**，
  由 MainActivity 在变更时调用 `resetRound()`）、模式、分析频率。

## rules-liuzhou / settlement 设计要点（P5）

严格三层隔离（方案 2.3 实现要求）：**HandEvaluation**（`LiuzhouHandEvaluator`）→
**ScoringPolicy**（`LiuzhouScoringPolicy`）→ **ActionLedger**（事件账本），
由 `LiuzhouSettlement` 按「胡牌分 → 吃三比分 → 杠牌分 → 钓鱼分」编排。

**已确认给分**（2.2 表格直述且无冲突）：基本胡点炮 1 / 自摸每家 1；对对胡、清一色、
小七对点炮 3；暗杠每家 1；抢杠基本胡 ×2；杠上花/海底捞月 ×2 倍率（需可靠事件判定）。

**一律「暂不支持」**（2.3 冲突，`points=null` + 原因）：门清分值、门清对对胡/清一色 4、
小七对自摸每家 4（与每家 2 分上限冲突）、杠上炮 +3、吃三比支付对象与倍率
（次数由账本可靠统计）、全求人具体分值、补杠分、钓鱼。

**关键机制**：
- `ActionLedger` 只记录已确认发生的事件；`meldCountFromSameSource` 支撑吃三比计数；
  `isLastTileWinReliable` 仅在摸牌紧邻和牌时返回非 null（海底捞月前提）；
- `SettlementReport.confirmedTotal` 在存在暂不支持项时为 null——不展示虚假精确总分；
- 全求人 = 4 副露 + 手牌 1 张，基础胡型按副露推导（全刻→对对胡；同花色→清一色）。

## storage 设计要点（增强项）

- **Room 数据库**（`storage/AppDatabase.kt`，KSP 注解处理）：`settings`（单行偏好）、
  `rounds` + `round_events`（局历史与事件流，外键级联）、`debug_samples`（样本元数据）。
- **隐私铁律**：调试样本**只记元数据**（路径/标签/时间），图像内容不进数据库；
  全部本地存储，无上传。
- **持久化接线**：
  - MainActivity 启动时加载偏好（`lifecycleScope` + `StorageModule.settings`），
    变更时保存；箭牌开关切换额外触发 `resetRound`（方案 2.1）；
  - AnalysisPipeline 在检测到 `RoundReset` 事件时，把**该局重置前**的状态快照
    （完整事件流 + 起止时间）经 `onRoundEnded` 交给 CaptureService 持久化；
  - 保存调试帧时同步写入样本元数据。
- **事件映射**：`GameEvent`（密封接口）↔ `StoredEvent`（展平结构）纯函数映射，
  阶段变化存「FROM->TO」、副露存来源与类型；无效牌码/类型返回 null 不崩溃。
- **构建注意**：`settings.gradle.kts` 的 google() 仓库过滤器必须
  `excludeGroup("com.google.devtools.ksp")`——KSP 只在 Maven Central，
  而 AGP 的传递依赖（com.google.testing.platform 等）只在 Google 仓库。

## 关键约束（来自计划）

- **不越界**：P0 不做麻将推理；P1 不做实时牌局更新（单帧不能判断事件先后，见计划 3.2 第 3 点）。
- **安全边界**：不 Root、不 Hook 微信、不读内存/协议、不自动代打；小程序保护内容造成黑屏时报告不支持，不绕过。
- **隐私**：默认本地处理，不上传屏幕/昵称/头像/牌局；仅用户主动触发时保存单帧到应用私有目录。
- **Android 14+**：每次新投屏会话需重新授权，令牌不可复用；横竖屏变化优先 `resize` / `setSurface`。
- **采集自掩码**：P4 实时辅助 UI 上线时必须对自己的悬浮窗区域加掩码或采集时隐藏（计划 3.2 第 6 点），
  否则推荐文字会污染截图识别。

## 待核实（计划第 2.3 节，禁止脑补）

`rules-liuzhou` 中的所有计分冲突点（如“每家 2 分上限”适用范围、直杠杠上花算式笔误、
杠上炮 +3 含义、门清与封顶先后、吃三比明杠计数、钓鱼规则缺失）在确认前不得给出确定性收益数字。

## P1 验收前置依赖（阻塞项）

计划 P1 目标「独立验证集单牌识别准确率 ≥99%」需要用户提供：
1. 轮到自己出牌、无弹窗遮挡的正常手牌截图（多分辨率）；
2. 含吃/碰/杠副露的对局截图；
3. 玩法设置页截图（核对箭牌与规则开关）。
当前模板库为空，识别结果全部为 unknown——这是有意的安全默认，不是缺陷。
