package com.pika.ui.comments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.MyComicComment
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MyCommentsViewModel : ViewModel() {

    private val _comments = MutableStateFlow<List<MyComicComment>>(emptyList())
    val comments: StateFlow<List<MyComicComment>> = _comments

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var page: Int = 1
        private set

    var endReached: Boolean = false
        private set

    fun load(p: Int) {
        if (_loading.value || (p > 1 && endReached)) return
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = SourceManager.current().myComments(p)
                _comments.value = if (p == 1) result.items else _comments.value + result.items
                endReached = p >= result.pages
                page = p
            } catch (e: UnsupportedOperationException) {
                _error.value = "当前源不支持我的评论"
            } catch (e: Exception) {
                _error.value = e.message ?: "加载失败"
            } finally {
                _loading.value = false
            }
        }
    }
}
