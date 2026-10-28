package com.weile.liuzhou.mahjong.util

import android.content.Context
import android.provider.Settings

/** 是否拥有悬浮窗（SYSTEM_ALERT_WINDOW）权限。 */
fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)
