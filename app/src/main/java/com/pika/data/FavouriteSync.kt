package com.pika.data

/** 收藏变更失效标记：详情页收藏/取消收藏成功后置位，收藏列表返回时据此静默刷新 */
object FavouriteSync {
    @Volatile var dirty: Boolean = false
}
