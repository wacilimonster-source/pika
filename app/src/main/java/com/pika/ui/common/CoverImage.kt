package com.pika.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

/**
 * 封面图（失败可点击重试，S-5.14）：Coil 内置 3 次自动重试耗尽后，此前封面
 * 永久空白且没有任何用户可操作的恢复入口。本组件在失败时叠加半透明提示层，
 * 点击即重新请求；弱网/坏 CDN 下用户可自救，与阅读器单页重试（ReaderScreen）对齐。
 *
 * [coverUrl] 为 null/blank 时不渲染任何内容——首字母占位等空态由调用方承担
 * （各处占位样式不同，不做统一）。
 */
@Composable
fun CoverImage(
    coverUrl: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    if (coverUrl.isNullOrBlank()) return
    // 失败重试：改变 model（追加 fragment）强制 Coil 重新请求（与 ReaderScreen 同一手法）；
    // 缓存键固定为裸 URL，重试成功的结果仍可被其他位置复用
    var retryTick by remember(coverUrl) { mutableIntStateOf(0) }
    var failed by remember(coverUrl) { mutableStateOf(false) }

    Box(modifier) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(if (retryTick == 0) coverUrl else "$coverUrl#retry$retryTick")
                .memoryCacheKey(coverUrl)
                .diskCacheKey(coverUrl)
                .build(),
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.matchParentSize(),
            onState = { state ->
                when (state) {
                    is AsyncImagePainter.State.Error -> failed = true
                    // 重试成功（或复用了内存缓存）后必须撤掉失败提示层
                    is AsyncImagePainter.State.Success -> failed = false
                    else -> {}
                }
            },
        )
        if (failed) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable { retryTick++ },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "点击重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
