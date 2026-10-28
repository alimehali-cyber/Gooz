package com.alijafari.red.astronomy.ui.backdrop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The one live clock shared by the Home hero and the Live Sky backdrop.
 *
 * It ticks once a second, the same cadence the Home hero has always used, and it only ticks while at least one
 * collector is subscribed. With no subscriber there is no timer and no work. Both scenes read the same
 * [timeMs] value, so they show the same live instant.
 */
class SkyLiveClock(
    scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000L
) {
    val timeMs: StateFlow<Long> = flow {
        while (true) {
            emit(nowMs())
            delay(tickMs)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L),
        initialValue = nowMs()
    )
}
