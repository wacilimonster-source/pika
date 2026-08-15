package com.pika.core.download

import android.content.Context
import com.pika.core.source.SourceManager
import com.pika.network.BcTls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 下载任务状态 */
@Serializable
enum class DlStatus { PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELED }

/** 持久化任务描述 */
@Serializable
data class DownloadTask(
    val comicId: String,
    val comicTitle: String,
    val coverUrl: String,
    val order: Int,
    val epTitle: String,
    val pageCount: Int,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 运行时状态（含进度/速度/占用，不持久化） */
data class TaskRuntime(
    val task: DownloadTask,
    val status: DlStatus = DlStatus.PENDING,
    val downloadedPages: Int = 0,
    val totalBytes: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val error: String = "",
) {
    val key: String get() = "${task.comicId}#${task.order}"
    val progress: Int get() = if (task.pageCount > 0) (downloadedPages * 100 / task.pageCount) else 0
    val isFinished: Boolean get() = status == DlStatus.COMPLETED
}

/**
 * 下载管理器（单例）：
 * - 章节图片下载到 app 外部文件目录 downloads/{comicId}/{order}/page_*.jpg
 * - 任务列表持久化（manifest.json），重启恢复
 * - 进度 / 速度 / 存储占用实时上报；失败可重试；支持并发（默认 2 路）
 */
object DownloadManager {

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _tasks = MutableStateFlow<List<TaskRuntime>>(emptyList())
    val tasks: StateFlow<List<TaskRuntime>> = _tasks

    /** 全部下载内容占用空间（字节） */
    val totalBytes: Long get() = _tasks.value.sumOf { it.totalBytes }

    /** 当前总速度（字节/秒） */
    val totalSpeed: Long get() = _tasks.value.sumOf { it.bytesPerSecond }

    const val CONCURRENCY = 2

    fun init(context: Context) {
        appContext = context.applicationContext
        restoreTasks()
        startSpeedSampler()
    }

    // ── 路径 ──────────────────────────────────────────────────────────────
    fun rootDir(): File = File(appContext.getExternalFilesDir(null), "downloads")

    fun chapterDir(comicId: String, order: Int): File =
        File(rootDir(), "$comicId/$order")

    fun pageFile(comicId: String, order: Int, index: Int): File =
        File(chapterDir(comicId, order), "page_${index + 1}.jpg")

    /** 章节是否已有本地文件（离线可读） */
    fun isDownloaded(comicId: String, order: Int): Boolean {
        val dir = chapterDir(comicId, order)
        return dir.listFiles()?.any { it.name.startsWith("page_") && it.length() > 0 } == true
    }

    /** 某漫画的全部任务（按章节号排序） */
    fun comicTasks(comicId: String): List<TaskRuntime> =
        _tasks.value.filter { it.task.comicId == comicId }.sortedBy { it.task.order }

    /** 某漫画的下载进度摘要（已完成章数 / 总章数） */
    fun comicProgress(comicId: String): Pair<Int, Int> {
        val list = comicTasks(comicId)
        return list.count { it.isFinished } to list.size
    }

    // ── 任务管理 ──────────────────────────────────────────────────────────
    fun enqueue(
        comicId: String,
        comicTitle: String,
        coverUrl: String,
        order: Int,
        epTitle: String,
        pageCount: Int,
    ) {
        scope.launch {
            mutex.withLock {
                val list = _tasks.value.toMutableList()
                val idx = list.indexOfFirst { it.key == "$comicId#$order" }
                val task = DownloadTask(comicId, comicTitle, coverUrl, order, epTitle, pageCount)
                if (idx >= 0) {
                    val old = list[idx]
                    list[idx] = old.copy(
                        task = task,
                        status = DlStatus.PENDING,
                        downloadedPages = 0,
                        totalBytes = 0,
                        error = "",
                    )
                } else {
                    list.add(TaskRuntime(task))
                }
                _tasks.value = list
                persist()
            }
            pump()
        }
    }

    /** 批量入队整本漫画：一次加锁写入全部章节任务，跳过已下载章节 */
    fun enqueueAll(
        comicId: String,
        comicTitle: String,
        coverUrl: String,
        chapters: List<Pair<Int, String>>,
    ) {
        if (chapters.isEmpty()) return
        scope.launch {
            mutex.withLock {
                val list = _tasks.value.toMutableList()
                var changed = false
                for ((order, epTitle) in chapters) {
                    if (isDownloaded(comicId, order)) continue
                    val key = "$comicId#$order"
                    val idx = list.indexOfFirst { it.key == key }
                    if (idx >= 0) {
                        val old = list[idx]
                        if (old.isFinished) continue
                        list[idx] = old.copy(
                            task = old.task.copy(epTitle = epTitle),
                            status = DlStatus.PENDING,
                            error = "",
                        )
                        changed = true
                    } else {
                        list.add(
                            TaskRuntime(
                                DownloadTask(
                                    comicId = comicId,
                                    comicTitle = comicTitle,
                                    coverUrl = coverUrl,
                                    order = order,
                                    epTitle = epTitle,
                                    pageCount = 0,
                                ),
                            )
                        )
                        changed = true
                    }
                }
                if (changed) {
                    _tasks.value = list
                    persist()
                }
            }
            pump()
        }
    }

    fun retry(key: String) {
        scope.launch {
            mutex.withLock {
                _tasks.value = _tasks.value.map {
                    if (it.key == key && !it.isFinished) it.copy(status = DlStatus.PENDING, error = "") else it
                }
                persist()
            }
            pump()
        }
    }

    fun remove(key: String, deleteFiles: Boolean = true) {
        scope.launch {
            mutex.withLock {
                val target = _tasks.value.firstOrNull { it.key == key }
                _tasks.value = _tasks.value.filterNot { it.key == key }
                persist()
                if (deleteFiles && target != null) {
                    chapterDir(target.task.comicId, target.task.order).deleteRecursively()
                }
            }
        }
    }

    fun taskFor(comicId: String, order: Int): TaskRuntime? =
        _tasks.value.firstOrNull { it.key == "$comicId#$order" }

    // ── 调度 ──────────────────────────────────────────────────────────────
    private fun pump() {
        scope.launch {
            mutex.withLock {
                val running = _tasks.value.count { it.status == DlStatus.DOWNLOADING }
                if (running >= CONCURRENCY) return@withLock
                val next = _tasks.value.firstOrNull { it.status == DlStatus.PENDING }
                    ?: return@withLock
                _tasks.value = _tasks.value.map {
                    if (it.key == next.key) it.copy(status = DlStatus.DOWNLOADING) else it
                }
                persist()
            }
            val task = _tasks.value.firstOrNull { it.status == DlStatus.DOWNLOADING }
            if (task != null) runTask(task.key)
        }
    }

    private fun runTask(key: String) {
        scope.launch {
            val task = _tasks.value.firstOrNull { it.key == key } ?: return@launch
            val t = task.task
            try {
                val pages = SourceManager.current().chapterPages(t.comicId, t.order)
                // 真实页数在运行时才可知（整本批量入队时为 0），拉取后回填
                if (t.pageCount != pages.size) {
                    mutex.withLock {
                        _tasks.value = _tasks.value.map {
                            if (it.key == key) {
                                it.copy(task = it.task.copy(pageCount = pages.size))
                            } else it
                        }
                        persist()
                    }
                }
                val dir = chapterDir(t.comicId, t.order)
                dir.mkdirs()
                var bytes = 0L
                for ((i, page) in pages.withIndex()) {
                    // 已下载的页跳过
                    val file = pageFile(t.comicId, t.order, i)
                    if (file.exists() && file.length() > 0) {
                        bytes += file.length()
                        updateProgress(key, i + 1, bytes)
                        continue
                    }
                    val n = downloadFile(page.imageUrl, file)
                    bytes += n
                    updateProgress(key, i + 1, bytes)
                    // 支持取消：状态被外部改为 CANCELED 时停止
                    if (_tasks.value.firstOrNull { it.key == key }?.status == DlStatus.CANCELED) {
                        return@launch
                    }
                }
                mutex.withLock {
                    _tasks.value = _tasks.value.map {
                        if (it.key == key) it.copy(status = DlStatus.COMPLETED, error = "") else it
                    }
                    persist()
                }
            } catch (e: Exception) {
                mutex.withLock {
                    _tasks.value = _tasks.value.map {
                        if (it.key == key) it.copy(status = DlStatus.FAILED, error = e.message ?: "下载失败") else it
                    }
                    persist()
                }
            } finally {
                pump()
            }
        }
    }

    private suspend fun updateProgress(key: String, downloadedPages: Int, totalBytes: Long) {
        mutex.withLock {
            _tasks.value = _tasks.value.map {
                if (it.key == key) it.copy(downloadedPages = downloadedPages, totalBytes = totalBytes) else it
            }
        }
    }

    /** 下载单个文件（BouncyCastle TLS，绕 Cloudflare）。 */
    private fun downloadFile(urlStr: String, dest: File): Long {
        val url = URL(urlStr)
        val conn: HttpURLConnection = if (url.protocol == "https") {
            BcTls.openConnection(url)
        } else {
            url.openConnection() as HttpURLConnection
        }
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "okhttp/4.12.0")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val tmp = File(dest.parentFile, dest.name + ".part")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            return dest.length()
        } finally {
            conn.disconnect()
        }
    }

    // ── 持久化 ────────────────────────────────────────────────────────────
    private fun manifestFile(): File = File(rootDir(), "manifest.json")

    private fun persist() {
        runCatching {
            manifestFile().parentFile?.mkdirs()
            val tasks = _tasks.value
                .filter { it.status != DlStatus.CANCELED }
                .map { it.task }
            manifestFile().writeText(json.encodeToString(ListSerializer(DownloadTask.serializer()), tasks))
        }
    }

    private fun restoreTasks() {
        runCatching {
            val f = manifestFile()
            if (!f.exists()) return
            val saved: List<DownloadTask> =
                json.decodeFromString(ListSerializer(DownloadTask.serializer()), f.readText())
            _tasks.value = saved.map { task ->
                val dir = chapterDir(task.comicId, task.order)
                val pages = dir.listFiles()?.count { it.name.startsWith("page_") } ?: 0
                val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                val finished = pages >= task.pageCount
                TaskRuntime(
                    task = task,
                    status = if (finished) DlStatus.COMPLETED else DlStatus.FAILED,
                    downloadedPages = pages.coerceAtMost(task.pageCount),
                    totalBytes = bytes,
                    error = if (finished) "" else "上次未完成，可重试",
                )
            }
        }
    }

    // ── 速度采样 ──────────────────────────────────────────────────────────
    private fun startSpeedSampler() {
        scope.launch {
            var prevBytes = 0L
            var prevAt = 0L
            while (true) {
                val now = System.currentTimeMillis()
                val bytes = _tasks.value.sumOf { it.totalBytes }
                if (prevAt != 0L && now - prevAt >= 1500) {
                    val speed = ((bytes - prevBytes) * 1000L / (now - prevAt)).coerceAtLeast(0L)
                    mutex.withLock {
                        _tasks.value = _tasks.value.map {
                            if (it.status == DlStatus.DOWNLOADING) it.copy(bytesPerSecond = speed) else it.copy(bytesPerSecond = 0)
                        }
                    }
                    prevBytes = bytes
                    prevAt = now
                } else if (prevAt == 0L) {
                    prevBytes = bytes
                    prevAt = now
                }
                delay(1000)
            }
        }
    }
}

/** 列表序列化辅助 */
private fun <T> ListSerializer(serializer: kotlinx.serialization.KSerializer<T>) =
    kotlinx.serialization.builtins.ListSerializer(serializer)
