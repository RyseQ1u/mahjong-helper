package com.weile.liuzhou.mahjong.domain.capture

import android.graphics.Bitmap

/**
 * 采集源抽象（capture-android 模块接口占位）。
 *
 * P0 已由 [com.weile.liuzhou.mahjong.capture.CaptureService] 用 MediaProjection 实现，
 * 这里仅预留接口，便于后续把采集能力下沉为独立模块 / 独立进程。
 */
interface CaptureSource {
    /** 开始采集（需要已获得的 MediaProjection 令牌）。 */
    fun start()

    /** 停止并释放所有资源。 */
    fun stop()

    /** 暂停读取帧（保留投影与显示，仅停止取帧）。 */
    fun pause()

    /** 从暂停恢复。 */
    fun resume()

    /** 返回最近一帧的可复用 Bitmap（可能为 null）。调用方不应长期持有。 */
    fun latestFrame(): Bitmap?
}
