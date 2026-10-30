package com.example.videoplayer.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从 Compose 的 LocalContext 找出宿主 Activity。
 * LocalContext 可能被主题包装、被 Coil 包装，也可能是 ContextThemeWrapper，
 * 所以要一层层往上找（tailrec 保证不会无限递归：ContextWrapper 链总是有尽头的）。
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
