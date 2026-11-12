package com.pika.util

import android.content.Context
import android.net.ConnectivityManager

/**
 * 当前活动网络是否按流量计费。
 *
 * 「仅 Wi-Fi 下载」的调度器与下载页的提示必须用同一判定：无网络时按计费处理
 * （保守策略，避免在计费网络上悄悄开跑）。
 */
fun isNetworkMetered(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return true
    return cm.isActiveNetworkMetered
}
