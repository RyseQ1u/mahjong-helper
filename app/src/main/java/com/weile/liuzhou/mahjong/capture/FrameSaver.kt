package com.weile.liuzhou.mahjong.capture

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * 单帧保存到本地调试目录（P0-4）。
 *
 * 关键约束（方案第 5 节）：采集状态/异常只记录元数据，不默认持续保存全屏内容；
 * 仅在用户主动触发时保存一帧 PNG 到应用私有目录（无需存储权限）。
 */
object FrameSaver {
    private const val TAG = "FrameSaver"
    private const val DEBUG_SUBDIR = "debug/frames"

    /** 调试帧保留上限。仅在用户主动保存时产生，但仍需防止长期使用后无限增长占满存储。 */
    private const val MAX_KEPT_FRAMES = 20

    /** 保存一帧，返回文件路径；调用方需保证 bitmap 不为 null。应在 IO 线程调用。 */
    fun saveFrame(context: Context, bitmap: Bitmap, tag: String = "frame"): String {
        val dir = File(context.filesDir, DEBUG_SUBDIR)
        if (!dir.exists()) dir.mkdirs()
        val name = "${tag}_${System.currentTimeMillis()}.png"
        val file = File(dir, name)
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        pruneOldFrames(dir)
        Log.i(TAG, "saved frame -> ${file.absolutePath} (${bitmap.width}x${bitmap.height})")
        return file.absolutePath
    }

    /** 只保留最近 [MAX_KEPT_FRAMES] 帧，删除更早的调试截图。 */
    private fun pruneOldFrames(dir: File) {
        val frames = dir.listFiles { f -> f.extension == "png" } ?: return
        if (frames.size <= MAX_KEPT_FRAMES) return
        frames.sortedByDescending { it.lastModified() }
            .drop(MAX_KEPT_FRAMES)
            .forEach { old -> runCatching { old.delete() } }
    }

    /** 列出已保存的调试帧（供导出/查看）。 */
    fun listSavedFrames(context: Context): List<File> {
        val dir = File(context.filesDir, DEBUG_SUBDIR)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "png" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }
}
