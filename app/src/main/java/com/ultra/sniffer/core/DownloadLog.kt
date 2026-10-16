package com.ultra.sniffer.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-URL download status shown in the list + notifications. */
object DownloadLog {

    enum class Phase { QUEUED, DOWNLOADING, MERGING, SAVING, DONE, ERROR }

    data class Entry(
        val url: String,
        val phase: Phase,
        val percent: Int = 0,
        val message: String = "",
        val savedTo: String? = null
    )

    private val _state = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val state: StateFlow<Map<String, Entry>> = _state.asStateFlow()

    @Synchronized
    fun update(url: String, phase: Phase, percent: Int = 0, message: String = "", savedTo: String? = null) {
        val map = _state.value.toMutableMap()
        val old = map[url]
        map[url] = Entry(url, phase, percent, message, savedTo ?: old?.savedTo)
        _state.value = map
    }

    fun get(url: String): Entry? = _state.value[url]

    @Synchronized
    fun remove(url: String) {
        _state.value = _state.value - url
    }
}
