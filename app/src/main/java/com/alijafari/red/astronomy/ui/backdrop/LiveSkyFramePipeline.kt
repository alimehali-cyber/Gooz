package com.alijafari.red.astronomy.ui.backdrop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.sample

/** Minimum spacing between two sky computations, about 30 per second. Drags are coalesced inside this window. */
const val LIVE_SKY_FRAME_INTERVAL_MS: Long = 33L

/**
 * Turns a stream of frame requests into a stream of computed frames, with three guarantees:
 *  - Equal consecutive requests are dropped, so an unchanged sky is never recomputed.
 *  - At most one request is sampled per [intervalMs], so a 60 FPS drag does not run 60 astronomy passes per second.
 *  - [mapLatest] cancels the computation for a superseded request. A result that belongs to an older request is never
 *    emitted after a newer one, so a stale result cannot overwrite a newer frame.
 *
 * A computation that returns null is a failure. It is dropped, and the previous frame remains on screen.
 */
fun <R : Any, F : Any> Flow<R>.latestFrames(
    intervalMs: Long = LIVE_SKY_FRAME_INTERVAL_MS,
    compute: suspend (R) -> F?
): Flow<F> = distinctUntilChanged()
    .sample(intervalMs)
    .mapLatest(compute)
    .filterNotNull()
