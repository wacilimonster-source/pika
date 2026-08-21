package com.pika.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val tag: String,
    val level: String,
    val message: String,
)

object LogStore {

    private const val MAX_ENTRIES = 500

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Synchronized
    fun log(tag: String, level: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, level, message)
        val current = _logs.value
        val updated = if (current.size >= MAX_ENTRIES) {
            current.drop(1) + entry
        } else {
            current + entry
        }
        _logs.value = updated
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun allText(): String {
        val sb = StringBuilder()
        for (e in _logs.value) {
            sb.appendLine("[${timeFmt.format(Date(e.timestamp))}] [${e.tag}] [${e.level}] ${e.message}")
        }
        return sb.toString().trimEnd()
    }
}
