package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.TileMatch
import com.weile.liuzhou.mahjong.domain.vision.TileRegion
import com.weile.liuzhou.mahjong.domain.vision.TileTemplateLibrary
import com.weile.liuzhou.mahjong.domain.vision.TileTemplateMatcher
import kotlin.math.sqrt

/**
 * 基于 NCC（归一化互相关）的牌面模板匹配器（P1 实现）。
 *
 * 方案第 3.2 节要求「先采用模板匹配/特征比对」。这里不引入 OpenCV，
 * 用轻量 NCC 零均值归一化点积实现，接口已隔离，后续可替换为 OpenCV 引擎。
 *
 * - 区域会被重采样到各模板尺寸后比较，容忍轻微缩放误差；
 * - 平坦区域（标准差≈0，如纯黑/纯白）直接判 0 分，避免误匹配；
 * - **模板库为空时一律返回 unknown**——真实牌面模板必须由用户提供的
 *   《微乐广西麻将》截图生成，禁止用合成数据冒充真实识别率。
 */
class NccTileTemplateMatcher(
    val library: TileTemplateLibrary,
) : TileTemplateMatcher {

    override fun match(
        region: PixelAccessor,
        bounds: TileRegion,
        minConfidence: Float,
        topN: Int,
    ): List<TileMatch> {
        if (library.isEmpty) {
            return listOf(TileMatch.unknown(bounds))
        }
        if (region.width <= 0 || region.height <= 0) {
            return listOf(TileMatch.unknown(bounds))
        }

        val scores = mutableListOf<Pair<Tile, Float>>()
        for ((tile, template) in library.templates) {
            val ncc = computeNcc(region, template)
            scores += tile to ncc
        }
        scores.sortByDescending { it.second }

        val best = scores.first()
        if (best.second < minConfidence) {
            return listOf(TileMatch.unknown(bounds))
        }
        return scores.take(topN).map { (tile, score) ->
            TileMatch(tile, score, bounds)
        }
    }

    /** 将区域重采样到模板尺寸后计算 NCC。 */
    private fun computeNcc(region: PixelAccessor, template: com.weile.liuzhou.mahjong.domain.vision.TileTemplate): Float {
        val tw = template.width
        val th = template.height
        if (tw <= 0 || th <= 0) return 0f

        val regionSamples = sampleGrayscale(region, tw, th)
        val rMean = regionSamples.average()
        var rSq = 0.0
        for (v in regionSamples) {
            val d = v - rMean
            rSq += d * d
        }
        val rNorm = sqrt(rSq)
        // 平坦区域（纯黑/纯白）没有可区分特征，直接 0 分
        if (rNorm < FLAT_EPSILON) return 0f

        val tMean = template.pixels.average()
        var dot = 0.0
        var tSq = 0.0
        for (i in template.pixels.indices) {
            val rd = regionSamples[i] - rMean
            val td = template.pixels[i] - tMean
            dot += rd * td
            tSq += td * td
        }
        val tNorm = sqrt(tSq)
        if (tNorm < FLAT_EPSILON) return 0f

        return (dot / (rNorm * tNorm)).coerceIn(-1.0, 1.0).toFloat()
    }

    /** 双线性-ish 最近邻重采样到目标尺寸，返回灰度数组。 */
    private fun sampleGrayscale(region: PixelAccessor, tw: Int, th: Int): DoubleArray {
        val out = DoubleArray(tw * th)
        val sx = region.width.toDouble() / tw
        val sy = region.height.toDouble() / th
        for (ty in 0 until th) {
            val y = ((ty + 0.5) * sy).toInt().coerceIn(0, region.height - 1)
            for (tx in 0 until tw) {
                val x = ((tx + 0.5) * sx).toInt().coerceIn(0, region.width - 1)
                out[ty * tw + tx] = region.luminance(x, y).toDouble()
            }
        }
        return out
    }

    private companion object {
        /** 低于此标准差视为平坦区域。 */
        const val FLAT_EPSILON = 1e-6
    }
}
