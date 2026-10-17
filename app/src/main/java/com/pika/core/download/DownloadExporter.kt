package com.pika.core.download

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pika.core.log.LogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream/**
 * 已下载漫画 → ZIP 导出（SAF 流式写出，不落临时文件）。
 *
 * 用户诉求（报告 #3）：下载留存在应用私有目录，卸载即全部丢失，也无法分享/备份。
 * 本导出把某漫画的全部「已完成」章节打包为 zip 写入用户选择的目标
 * （SAF `CreateDocument("application/zip")`）。
 *
 * 产物布局（为将来的「导入/恢复」预留 schema）：
 * ```
 * {漫画标题}/pika.json                     ← 元数据（schema=1，含章节清单）
 * {漫画标题}/chapters/0001-话名/page_1.jpg ...
 * ```
 * 流式写出：不物化整本字节（长篇可能数 GB），逐文件拷贝，进度按章推进；
 * 同一时间仅允许一个导出任务（单例 Job，start 幂等）。
 *
 * 取消/失败语义：中途取消或失败，目标处会残留残缺 zip——SAF 文档调用方无法
 * 可靠回删（文件由用户端持有），UI 必须提示"产物不完整请重新导出"。为降低把
 * 半成品误当完整包的概率，pika.json 严格作为**首个**条目写出：导入校验读到它
 * 但流提前结束即视为无效产物。
 */
object DownloadExporter {

    /** 对外可见的导出状态（UI 唯一订阅源；null =无事发生/可发起新导出） */
    sealed class ExportState {
        /** [fileName] 为 SAF 目标文档显示名 */
        data class Running(
            val fileName: String,
            val comicTitle: String,
            /** 从 1 起，便于直接显示「第 x/N 话」 */
            val chapterIndex: Int,
            val chapterCount: Int,
            val chapterTitle: String,
        ) : ExportState()

        data class Done(val fileName: String, val chapterCount: Int) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    /** pika.json 元数据：为将来「导出的 zip 直接导入恢复」预留 */
    @Serializable
    data class ExportMeta(
        val app: String = "PiKA",
        /** 产物结构版本：后续结构变更时递增，导入侧据此分发解析器 */
        val schema: Int = 1,
        val comicId: String,
        val comicTitle: String,
        val source: String,
        val exportedAt: Long,
        val chapters: List<ExportChapterMeta>,
    )

    @Serializable
    data class ExportChapterMeta(
        val order: Int,
        val epTitle: String,
        val pageCount: Int,
        /** zip 内章节目录（相对产物根目录） */
        val dir: String,
    )

    private val json = Json { encodeDefaults = true; prettyPrint = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ExportState?>(null)
    val state: StateFlow<ExportState?> = _state.asStateFlow()

    private var job: Job? = null
    private val mutex = Mutex() // 同一时间只允许一个导出任务

    /**
     * 开始导出。返回 false = 已有导出在进行（忽略本次请求）。
     * [uri] 为 SAF CreateDocument 返回的目标；[comicId] 从任务表聚合已完成章节。
     */
    fun start(context: Context, comicId: String, uri: Uri): Boolean {
        if (job?.isActive == true) {
            LogStore.log("DownloadExporter", "W", "export ignored: busy")
            _state.value = ExportState.Error("已有导出任务在进行，请等它结束")
            return false
        }
        job = scope.launch {
            mutex.withLock {
                runExport(context.applicationContext, comicId, uri)
            }
        }
        return true
    }

    /** 取消导出。产物不完整（见类注释），UI 需明示用户重新导出。 */
    fun cancel() {
        job?.cancel()
    }

    private suspend fun runExport(context: Context, comicId: String, uri: Uri) {
        val tasks = DownloadManager.comicTasks(comicId).filter { it.isFinished }
        if (tasks.isEmpty()) {
            _state.value = ExportState.Error("没有已完成的章节可导出")
            return
        }
        val comicTitle = tasks.first().task.comicTitle
        val fileName = displayNameOf(context, uri)
        val rootInZip = sanitize(comicTitle).take(50).ifBlank { comicId }
        _state.value = ExportState.Running(fileName, comicTitle, 0, tasks.size, "准备中")

        try {
            val raw = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("无法写入所选位置，请换一个目录重试")
            raw.use { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zip ->
                    // ── 1. 元数据（首个条目）──────────────────────────────
                    val chaptersMeta = tasks.map { rt ->
                        ExportChapterMeta(
                            order = rt.task.order,
                            epTitle = rt.task.epTitle,
                            pageCount = rt.task.pageCount,
                            dir = chapterDirName(rt),
                        )
                    }
                    val meta = ExportMeta(
                        comicId = comicId,
                        comicTitle = comicTitle,
                        source = tasks.first().task.source,
                        exportedAt = System.currentTimeMillis(),
                        chapters = chaptersMeta,
                    )
                    zip.putNextEntry(ZipEntry("$rootInZip/pika.json"))
                    zip.write(
                        json.encodeToString(ExportMeta.serializer(), meta).toByteArray(Charsets.UTF_8),
                    )
                    zip.closeEntry()

                    // ── 2. 章节图片（按章推进；章内逐页，取消随时可插进来）──
                    tasks.forEachIndexed { index, rt ->
                        _state.value = ExportState.Running(
                            fileName,
                            comicTitle,
                            index + 1,
                            tasks.size,
                            rt.task.epTitle.ifBlank { "第 ${rt.task.order} 话" },
                        )
                        val dirName = chapterDirName(rt)
                        // 任务已是 COMPLETED；缺页说明目录被外部策略清理过，
                        // 导出不中断（更多数据比中断更有用），pika.json 页数仍在，
                        // 导入侧可据此报缺页
                        repeat(rt.task.pageCount) { pageIndex ->
                            // 取消检查：zip 拷贝块无挂起点，必须显式埋点响应 cancel()
                            coroutineContext.ensureActive()
                            val f = DownloadManager.pageFile(comicId, rt.task.order, pageIndex)
                            if (f.isFile && f.length() > 0) {
                                zip.putNextEntry(
                                    ZipEntry("$rootInZip/chapters/$dirName/page_${pageIndex + 1}.jpg"),
                                )
                                f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            _state.value = ExportState.Done(fileName, tasks.size)
        } catch (ce: CancellationException) {
            _state.value = ExportState.Error("导出已取消：产物不完整，请重新导出")
            throw ce
        } catch (e: Exception) {
            LogStore.log("DownloadExporter", "E", "export failed: ${e.message}")
            _state.value = ExportState.Error(
                when (e) {
                    is IOException -> "写入失败：${e.message ?: "目标可能已满"}"
                    else -> "导出失败：${e.message ?: "未知错误"}"
                },
            )
        }
    }

    /** SAF 文档显示名（文件管理器里叫什么就显示什么）；查询失败退回固定名 */
    private fun displayNameOf(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull().orEmpty().ifBlank { "PiKA 导出.zip" }

    private fun chapterDirName(rt: TaskRuntime): String {
        val ep = rt.task.epTitle.ifBlank { "第 ${rt.task.order} 话" }
        // %04d 保证按章序在文件管理器里天然有序（9999 话以内的现实场景足够）
        return "%04d".format(Locale.US, rt.task.order) + "-" + sanitize(ep).take(80)
    }

    /** zip 条目安全名：替换 Windows 禁用字符、路径分隔符与控制符（与 ImageSaver 同策略） */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\n\r\\x00-\\x1f]"), "_").trim().ifBlank { "pika" }
}
