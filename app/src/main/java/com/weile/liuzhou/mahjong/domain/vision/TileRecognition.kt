package com.weile.liuzhou.mahjong.domain.vision

import com.weile.liuzhou.mahjong.domain.mahjong.Tile

/**
 * 牌面识别（vision-core，P1）的领域接口。
 *
 * 方案第 3.2 节：P1 抽取牌面模板、建立牌类及方向校正、识别「准备/和/正在游戏」状态；
 * 目标指标：独立验证集单牌识别准确率 ≥99%，整手牌识别准确率尽量接近 ≥95%。
 *
 * 重要边界：真实牌面模板必须由用户提供的《微乐广西麻将》截图生成。
 * 本框架只定义识别流程与算法骨架；模板库为空时匹配器必须返回 unknown，
 * 禁止用合成/占位数据冒充真实识别率。
 */

/** 一块待识别的牌面区域（来自视口内的牌区分割）。 */
data class TileRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}

/** 牌面区域来源（与 [TileLayout] 的牌区一一对应，供 game-state 适配）。 */
enum class TileSource {
    SELF_HAND,
    SELF_DRAWN,
    DISCARD_SELF,
    DISCARD_LEFT,
    DISCARD_TOP,
    DISCARD_RIGHT,
    UNKNOWN,
}

/** 单张牌的识别结果。tile 为 null 表示未知牌（P1 需显示「请确认」）。 */
data class TileMatch(
    val tile: Tile?,
    /** 匹配置信度 0..1（NCC 相似度）。 */
    val confidence: Float,
    val region: TileRegion,
    /** 该牌来自哪个牌区（ScreenRecognizer 回填）。 */
    val source: TileSource = TileSource.UNKNOWN,
) {
    val isUnknown: Boolean get() = tile == null || confidence <= 0f

    companion object {
        fun unknown(region: TileRegion, source: TileSource = TileSource.UNKNOWN): TileMatch =
            TileMatch(null, 0f, region, source)
    }
}

/** 牌面模板（灰度特征图）。 */
data class TileTemplate(
    val tile: Tile,
    val width: Int,
    val height: Int,
    /** 灰度像素，行优先，长度 = width*height，取值 0..255。 */
    val pixels: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is TileTemplate && tile == other.tile && width == other.width &&
            height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int =
        (tile.hashCode() * 31 + width) * 31 + height + pixels.contentHashCode()
}

/**
 * 牌面模板库。
 * 真实模板由样本截图离线生成后注入；P1 期间为空库时识别结果必须全部为 unknown。
 */
interface TileTemplateLibrary {
    val templates: Map<Tile, TileTemplate>
    val isEmpty: Boolean get() = templates.isEmpty()

    /** 构造一个加载了模板的库（便于测试注入合成模板）。 */
    companion object {
        fun of(vararg templates: TileTemplate): TileTemplateLibrary =
            object : TileTemplateLibrary {
                override val templates: Map<Tile, TileTemplate> =
                    templates.associateBy { it.tile }
            }

        val EMPTY: TileTemplateLibrary = of()
    }
}

/** 牌面模板匹配器（方案 3.2：模板匹配/特征比对）。 */
interface TileTemplateMatcher {
    /**
     * 在给定区域上匹配最相似的模板。
     * @param region 区域像素（灰度访问）
     * @param bounds 区域在整帧中的位置（回填到 [TileMatch.region]）
     * @param minConfidence 低于此置信度时返回 unknown（P1 建议 0.9 起调）
     * @param topN 返回前 N 个候选（调试/人工纠错用）
     */
    fun match(
        region: PixelAccessor,
        bounds: TileRegion,
        minConfidence: Float = 0.9f,
        topN: Int = 1,
    ): List<TileMatch>
}

/** 游戏画面状态分类（方案 3.1 状态机中的画面层状态）。 */
enum class ScreenState {
    /** 准备/等待开始（出现「准备」等提示）。 */
    PREPARING,

    /** 对局进行中（自己的出牌回合之外的正常打牌画面）。 */
    PLAYING,

    /** 轮到自己出牌（应触发完整识别与舍牌建议）。 */
    OWN_TURN,

    /** 动画/特效中（吃碰杠、胡牌动画等，暂停可疑数据更新）。 */
    ANIMATION,

    /** 结算/过渡画面（「和」等提示，不应触发实时舍牌）。 */
    SETTLEMENT,

    /** 无法判定（置信度不足，显示「请确认」）。 */
    UNKNOWN,
}

/** 画面状态分类器。 */
interface ScreenStateClassifier {
    fun classify(screen: PixelAccessor): ScreenState
}

/** 一次完整画面识别的输出（串联视口 + 状态 + 牌区）。 */
data class RecognitionResult(
    val viewport: ViewportResult,
    val screenState: ScreenState,
    /** 按阅读顺序排列的识别牌（位置 + 置信度）。 */
    val tiles: List<TileMatch>,
    /** 未知牌率（P1 指标之一）。 */
    val unknownRatio: Float,
    /** 模板库是否就绪（false 表示尚未注入真实模板）。 */
    val templateLibraryReady: Boolean,
) {
    val singleTileAccuracyEstimate: Float
        get() = if (tiles.isEmpty()) 0f else tiles.count { it.confidence > 0f }.toFloat() / tiles.size
}
