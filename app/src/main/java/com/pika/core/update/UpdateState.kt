package com.pika.core.update

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 全局更新状态：App 启动时自动检查一次（update.json），
 * 有新版时供首页横幅 / 我的角标消费。
 */
object UpdateState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val PREFS = "pika_update_state"
    private const val KEY_SNOOZED = "snoozed_version"
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
    }

    private val _updateInfo = MutableStateFlow<UpdateManager.UpdateInfo?>(null)

    /** null=无更新或未检查；非 null=有新版本 */
    val updateInfo: StateFlow<UpdateManager.UpdateInfo?> = _updateInfo.asStateFlow()

    /** 最近一次手动检查的失败原因（用于向用户如实反馈"检查失败"而非"已是最新"） */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    /** 检查一次（幂等：已有结果不重复弹） */
    fun checkOnce() {
        scope.launch {
            if (_updateInfo.value != null) return@launch
            val result = runCatching { UpdateManager.checkResult() }.getOrNull() ?: return@launch
            when (result) {
                is UpdateManager.CheckResult.Available -> {
                    // 用户对该版本选择过「稍后再说」则不再自动展示（手动检查更新不受影响）
                    if (!isSnoozed(result.info.version)) {
                        _updateInfo.value = result.info
                    }
                    _lastError.value = null
                }
                UpdateManager.CheckResult.UpToDate -> {
                    _lastError.value = null
                }
                is UpdateManager.CheckResult.Failed -> {
                    // 保留原因：判为"无更新"会让用户误以为已是最新
                    _lastError.value = result.reason
                }
            }
        }
    }

    /** 手动检查（无论结果都更新状态，并透传失败原因） */
    fun checkManual() {
        scope.launch {
            when (val result = runCatching { UpdateManager.checkResult() }.getOrNull()) {
                is UpdateManager.CheckResult.Available -> {
                    _updateInfo.value = result.info
                    _lastError.value = null
                }
                UpdateManager.CheckResult.UpToDate -> {
                    _updateInfo.value = null
                    _lastError.value = null
                }
                is UpdateManager.CheckResult.Failed -> {
                    _updateInfo.value = null
                    _lastError.value = result.reason
                }
                null -> {
                    _updateInfo.value = null
                    _lastError.value = "网络异常或服务器未就绪"
                }
            }
        }
    }

    fun dismiss() {
        _updateInfo.value = null
    }

    /** 「稍后再说」：本版本内不再自动弹横幅/提示（手动检查更新不受影响） */
    fun snooze() {
        _updateInfo.value?.let { info ->
            runCatching { prefs?.edit()?.putString(KEY_SNOOZED, info.version)?.apply() }
        }
        _updateInfo.value = null
    }

    private fun isSnoozed(version: String): Boolean =
        runCatching { prefs?.getString(KEY_SNOOZED, null) == version }.getOrDefault(false)
}
