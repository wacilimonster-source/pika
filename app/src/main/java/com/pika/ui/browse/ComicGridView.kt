package com.pika.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pika.core.model.ComicSummary

/** 卡片圆角（design-tokens layout.cardRadius） */
internal val CardCoverShape = RoundedCornerShape(10.dp)

/** 漫画网格：每行数量随设置（2/3，默认 2）+ 触底自动加载更多 */
@Composable
fun ComicGridView(
    comics: List<ComicSummary>,
    loading: Boolean = false,
    endReached: Boolean = false,
    listState: LazyGridState,
    onLoadMore: () -> Unit,
    onComicClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    /** 是否显示尾部加载转圈（与下拉刷新指示器重叠的页面传 false，避免双转圈） */
    showTailLoading: Boolean = true,
    /** 到底后是否显示「没有更多了」尾行（可分页的列表用，固定列表不受影响） */
    showEndHint: Boolean = false,
    /** 长按卡片回调（参数为作品标识）；null = 不响应长按（收藏页用于快捷取消收藏） */
    onComicLongClick: ((String) -> Unit)? = null,
    /** 「有更新」判定基线：条目 updatedAt 非空且大于该值时封面左上角显示圆点（关注流用） */
    newUntil: String = "",
    /** 真实榜单位次展示：仅当列表本身是服务端榜单（顺序即名次）时开启 */
    showRankNumber: Boolean = false,
) {
    val statusVersion by com.pika.data.ReaderStatus.version.collectAsState()
    val columns by com.pika.data.GridSettings.columnsFlow.collectAsState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = listState,
        modifier = modifier.fillMaxSize(),
        // 紧凑页边距 16dp；列间距 12dp、行间距 20dp（design-tokens layout）
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(comics, key = { _, comic -> comic.ref }) { index, comic ->
            val readStatus = remember(comic.ref, statusVersion) { com.pika.data.ReaderStatus.of(comic.ref) }
            val isNew = remember(comic.ref, newUntil) {
                newUntil.isNotBlank() && comic.updatedAt.isNotBlank() && comic.updatedAt > newUntil
            }
            // A5 增删动画：条目移除/位置变化时平滑收起（收藏页取消收藏、关注流刷新）
            Box(Modifier.animateItem()) {
                ComicCard(
                    comic = comic,
                    readStatus = readStatus,
                    largeTitle = columns == 2,
                    onClick = { onComicClick(comic.ref) },
                    onLongClick = onComicLongClick?.let { cb -> { cb(comic.ref) } },
                    isNew = isNew,
                    rankNumber = if (showRankNumber) index + 1 else 0,
                )
            }
        }
        if (loading && showTailLoading) {
            item(key = "tail_loading") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                }
            }
        }
        if (showEndHint && endReached && comics.isNotEmpty()) {
            item(key = "end_hint") {
                Text(
                    text = "— 没有更多了 —",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    // 触底预加载提前量保持约 2~3 行：2 列提前 6 个，3 列提前 4 个
    val preloadAhead = if (columns == 2) 6 else 4
    LaunchedEffect(listState, endReached, loading, columns) {
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - preloadAhead
        }.collect { shouldLoad ->
            if (shouldLoad && !loading && !endReached) {
                onLoadMore()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComicCard(
    comic: ComicSummary,
    readStatus: com.pika.data.ReadStatus?,
    /** 2 列大卡片时标题用更大字号 */
    largeTitle: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    /** 关注流「有更新」小圆点 */
    isNew: Boolean = false,
    /** 真实名次（>0 时封面左上角逐行叠加 01/02…）；0 = 不显示 */
    rankNumber: Int = 0,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(CardCoverShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            comic.coverUrl?.let { cover ->
                AsyncImage(
                    model = cover,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (comic.coverUrl.isNullOrBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = comic.title.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 真实榜单名次（仅服务端榜单流开启）：左上角衬线斜体数字，白字加投影保证可读
            if (rankNumber > 0) {
                Text(
                    text = "%02d".format(rankNumber),
                    style = TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 24.sp,
                        lineHeight = 26.sp,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color.Black.copy(alpha = 0.55f),
                            offset = androidx.compose.ui.geometry.Offset(0f, 2f),
                            blurRadius = 8f,
                        ),
                    ),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 4.dp),
                )
            }
            // 关注流「有更新」：右上角小圆点（与名次不同角，不与阅读状态角标重叠）
            if (isNew) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.error, CircleShape),
                )
            }
            // 阅读状态角标：右下角独立分区（上方留给封面人物面部），读完优先显示
            when (readStatus) {
                com.pika.data.ReadStatus.FINISHED -> ReadBadge(
                    text = "读完",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                com.pika.data.ReadStatus.READ -> ReadBadge(
                    text = "已读",
                    container = Color(0x99000000),
                    content = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                null -> {}
            }
        }
        // 两列标题 14sp/20·600，三列 12sp/18·600，最多两行
        Text(
            text = comic.title,
            style = if (largeTitle) {
                MaterialTheme.typography.titleSmall
            } else {
                TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                )
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (comic.author.isNotBlank()) {
            Text(
                text = comic.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp),
        ) {
            if (comic.updatedAt.isNotBlank()) {
                Text(
                    text = comic.updatedAt.take(10),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (comic.totalLikes > 0) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    modifier = Modifier.size(10.dp),
                )
                Text(
                    text = "${comic.totalLikes}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 2.dp),
                )
            }
        }
    }
}

/** 封面右下角阅读状态角标（10sp 药丸；状态指示，不是操作入口） */
@Composable
private fun ReadBadge(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = TextStyle(
            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            lineHeight = 14.sp,
        ),
        color = content,
        maxLines = 1,
        modifier = modifier
            .padding(6.dp)
            .background(container, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
