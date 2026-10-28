package com.zig.gargantua.renderer

/** Pure frame-decision logic for the animated-disk cache and material pass. */
class AnimationGate(
    private val settleNanos: Long = GargantuaAnimation.CAMERA_SETTLE_MS * 1_000_000L
) {
    /**
     * REBUILD: trace the scene with the animation build program (HDR + base/corner hit records).
     * COMPLETE_CACHE: once per build, after the settle time, write the remaining ray records and the
     * build-time emission; the cached frame is presented unchanged.
     * MODULATE: re-shade the cached disk hits (material pass only, no geodesic integration).
     * PLAIN: present the plain geodesic path.
     */
    enum class Action { REBUILD, COMPLETE_CACHE, MODULATE, PLAIN }

    enum class State { BUILD, SETTLE, ACTIVE, PLAIN }

    data class Input(
        val animationRequested: Boolean,
        val resourcesReady: Boolean,
        val cacheValid: Boolean,
        val sceneDirty: Boolean,
        val signatureChangedThisFrame: Boolean,
        val nowNanos: Long,
        val lastChangeNanos: Long,
        val amplitudePercent: Int,
        /** All ray records and the build-time emission of the current cache exist. */
        val cacheComplete: Boolean = true
    )

    data class Decision(
        val action: Action,
        val state: State,
        val cameraStable: Boolean,
        val elapsedSinceChangeNanos: Long
    )

    fun decide(input: Input): Decision {
        val elapsed = (input.nowNanos - input.lastChangeNanos).coerceAtLeast(0L)
        val stable = elapsed >= settleNanos
        return when {
            !input.animationRequested -> Decision(Action.PLAIN, State.PLAIN, stable, elapsed)
            input.amplitudePercent <= 0 -> Decision(Action.PLAIN, State.PLAIN, stable, elapsed)
            !input.resourcesReady -> Decision(Action.PLAIN, State.BUILD, stable, elapsed)
            input.signatureChangedThisFrame || input.sceneDirty || !input.cacheValid ->
                Decision(Action.REBUILD, State.BUILD, stable, elapsed)
            !stable -> Decision(Action.PLAIN, State.SETTLE, false, elapsed)
            !input.cacheComplete -> Decision(Action.COMPLETE_CACHE, State.SETTLE, true, elapsed)
            else -> Decision(Action.MODULATE, State.ACTIVE, true, elapsed)
        }
    }

    companion object {
        /** Ray records written per animation cache pass (draw buffers 1-3). */
        const val RECORDS_PER_CACHE_PASS = 3

        /** Cache passes per build: pass 0 with the HDR, then COMPLETE_CACHE passes 1 and 2 (9 M7 rays). */
        const val CACHE_PASS_COUNT = 3

        /**
         * Number of full-screen geodesic (RK4) passes the renderer issues for a frame with this action.
         * A MODULATE frame never integrates geodesics; the renderer asserts this against its draw counter.
         */
        fun geodesicPassesFor(action: Action, sceneDirty: Boolean): Int = when (action) {
            Action.REBUILD -> 1
            Action.COMPLETE_CACHE -> CACHE_PASS_COUNT - 1
            Action.MODULATE -> 0
            Action.PLAIN -> if (sceneDirty) 1 else 0
        }
    }
}
