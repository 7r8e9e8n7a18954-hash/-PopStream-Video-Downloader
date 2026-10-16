package com.ultra.sniffer.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.channels.BufferOverflow

/** Events pushed from the sniffer to the UI / overlay. */
sealed interface SnifferEvent {
    data class Detected(val media: SniffedMedia) : SnifferEvent
    data class VpnState(val running: Boolean) : SnifferEvent
}

/**
 * In-memory store of everything the sniffer has detected so far.
 * Thread-safe: add() is called from packet parsing threads.
 */
object SnifferRepository {

    private const val MAX_ITEMS = 200

    private val _media = MutableStateFlow<List<SniffedMedia>>(emptyList())
    val media: StateFlow<List<SniffedMedia>> = _media.asStateFlow()

    private val _events = MutableSharedFlow<SnifferEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<SnifferEvent> = _events.asSharedFlow()

    @Synchronized
    fun add(media: SniffedMedia): Boolean {
        val current = _media.value
        if (current.any { it.url == media.url }) return false
        val updated = (listOf(media) + current).take(MAX_ITEMS)
        _media.value = updated
        _events.tryEmit(SnifferEvent.Detected(media))
        return true
    }

    @Synchronized
    fun remove(url: String) {
        _media.value = _media.value.filterNot { it.url == url }
    }

    @Synchronized
    fun clear() {
        _media.value = emptyList()
    }

    @Synchronized
    fun latestDownloadable(): SniffedMedia? = _media.value.firstOrNull { it.downloadable }

    fun notifyVpnState(running: Boolean) {
        _events.tryEmit(SnifferEvent.VpnState(running))
    }
}
