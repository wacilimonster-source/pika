package com.pika.core.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 全局更新状态：App 启动时自动检查一次（update.json），
 * 有新版时供首页横幅 / 我的角标消费。
 */
object UpdateState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** null=无更新或未检查；非 null=有新版本 */
    val updateInfo: StateFlow<UpdateManager.UpdateInfo?> =
        MutableStateFlow(null)

    /** 检查一次（幂等：已有结果不重复弹） */
    fun checkOnce() {
        if ((updateInfo as MutableStateFlow<UpdateManager.UpdateInfo?>).value != null) return
        scope.launch {
            runCatching { UpdateManager.check() }
                .getOrNull()
                ?.let { (updateInfo as MutableStateFlow<UpdateManager.UpdateInfo?>).value = it }
        }
    }

    /** 手动检查（无论结果都更新状态） */
    fun checkManual() {
        scope.launch {
            val info = runCatching { UpdateManager.check() }.getOrNull()
            (updateInfo as MutableStateFlow<UpdateManager.UpdateInfo?>).value = info
        }
    }

    fun dismiss() {
        (updateInfo as MutableStateFlow<UpdateManager.UpdateInfo?>).value = null
    }
}
