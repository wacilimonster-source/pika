package com.pika.ui.theme

import com.pika.network.ImageDetail

/**
 * 把哔咔图片地址转成可加载 URL。
 * 先直连；后续按源配置切换代理。
 */
fun imageUrl(detail: ImageDetail): String = detail.directUrl