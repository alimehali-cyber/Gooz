package com.zig.chal.render

import com.zig.chal.config.ChalCameraConfig
import com.zig.chal.config.ChalMotion
import com.zig.chal.config.ChalRenderPolicy
import com.zig.chal.config.ChalMouseState
import com.zig.chal.config.ChalSimulationParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.pow
import java.io.Serializable

/**
 * Spherical camera with momentum, auto-pan, and the two "Director Mode" cinematics.
 *
 * Adapted from the reference animation loop, with time-based rather than frame-based motion. The camera state
 * is the source of truth (as the reference keeps it in a ref) and is exposed to the renderer as the
 * `u_mouse` uniform pair: `x = theta / (2*PI)`, `y = phi / PI`.
 *
 * All angles are radians. Zoom is in solar Schwarzschild scene units and is stored on the simulation params
 * (the reference mutates `params.zoom` for exactly the same reason: the shader consumes it as
 * `u_zoom = zoom * 2`).
 */
class ChalCamera {

    /** Camera state with spherical coordinates and velocity for momentum. */
    data class CameraState(
        /** Azimuthal angle (0 - 2*PI). */
        var theta: Double = PI,
        /** Polar angle (0 - PI). */
        var phi: Double = ChalCameraConfig.DEFAULT_VERTICAL_ANGLE,
        /** Velocity for theta (momentum). */
        var thetaVelocity: Double = 0.0,
        /** Velocity for phi (momentum). */
        var phiVelocity: Double = 0.0,
        /** Velocity for zoom (momentum). */
        var zoomVelocity: Double = 0.0,
        /** Damping factor for momentum decay. */
        var damping: Double = ChalCameraConfig.DAMPING
    ) : Serializable {
        fun copyState(): CameraState = CameraState(
            theta = theta,
            phi = phi,
            thetaVelocity = thetaVelocity,
            phiVelocity = phiVelocity,
            zoomVelocity = zoomVelocity,
            damping = damping
        )
    }

    /** Cinematic mode names, matching the reference's `"orbit" | "dive"`. */
    enum class CinematicMode { ORBIT, DIVE }

    private class CinematicState(
        var active: Boolean = false,
        var mode: CinematicMode? = null,
        var elapsedSeconds: Double = 0.0,
        var startAutoSpin: Double = ChalCameraConfig.DEFAULT_AUTO_SPIN,
        var startTheta: Double = 0.0,
        var startPhi: Double = 0.0,
        var startZoom: Double = 0.0,
        var velocity: Double = 0.0,
        var angularMomentum: Double = 0.0,
        var recovering: Boolean = false,
        var recoverySeconds: Double = 0.0
    )

    data class CinematicProgress(
        val active: Boolean, val mode: CinematicMode?, val elapsedSeconds: Double,
        val startAutoSpin: Double, val startTheta: Double, val startPhi: Double, val startZoom: Double,
        val velocity: Double, val angularMomentum: Double, val recovering: Boolean, val recoverySeconds: Double
    ) : Serializable

    data class Session(val pose: CameraState, val cinematic: CinematicProgress) : Serializable

    fun saveSession(): Session = Session(state.copyState(), CinematicProgress(
        cinematic.active, cinematic.mode, cinematic.elapsedSeconds, cinematic.startAutoSpin,
        cinematic.startTheta, cinematic.startPhi, cinematic.startZoom, cinematic.velocity,
        cinematic.angularMomentum, cinematic.recovering, cinematic.recoverySeconds
    ))

    fun restoreSession(session: Session?, params: ChalSimulationParams) {
        restore(params)
        if (session == null) return
        val pose = session.pose
        if (listOf(pose.theta, pose.phi, pose.thetaVelocity, pose.phiVelocity, pose.zoomVelocity, pose.damping).all { it.isFinite() }) {
            state = pose.copyState()
            state.damping = state.damping.coerceIn(0.0, 1.0)
            if (!session.cinematic.active && !session.cinematic.recovering) state.theta = ChalRenderPolicy.normalizeYaw(state.theta / (2.0 * PI)) * 2.0 * PI
            state.phi = state.phi.coerceIn(0.001, PI - 0.001)
        }
        val c = session.cinematic
        if (!params.reducedMotion && listOf(c.elapsedSeconds, c.recoverySeconds, c.startTheta, c.startPhi,
                c.startZoom, c.startAutoSpin, c.velocity, c.angularMomentum).all { it.isFinite() }) {
            cinematic = CinematicState(c.active, c.mode, c.elapsedSeconds.coerceAtLeast(0.0),
                c.startAutoSpin, c.startTheta, c.startPhi, c.startZoom, c.velocity, c.angularMomentum,
                c.recovering, c.recoverySeconds.coerceAtLeast(0.0))
        }
    }

    private var state = CameraState()
    private var cinematic = CinematicState()

    /** True while a drag gesture owns the camera (blocks auto-spin, exactly like `isDragging`). */
    var isDragging: Boolean = false
        private set

    /** Number of active pointers; auto-spin yields while touches are down. */
    var touchCount: Int = 0
        private set

    private var lastDragX = 0.0
    private var lastDragY = 0.0
    private var lastPinchDistance = 0.0

    val isCinematic: Boolean get() = cinematic.active || cinematic.recovering
    val cinematicMode: CinematicMode? get() = cinematic.mode
    val isRecovering: Boolean get() = cinematic.recovering

    /** Snapshot for the UI thread. */
    fun snapshot(): CameraState = state.copyState()

    /** `mouse = { x: theta / (2*PI), y: phi / PI }`. */
    fun mouseState(): ChalMouseState = ChalMouseState(
        x = state.theta / (2.0 * PI),
        y = state.phi / PI
    )

    // ---------------------------------------------------------------------------------------
    // Interaction
    // ---------------------------------------------------------------------------------------

    fun onPointerDown(x: Double, y: Double) {
        // Only stop cinematic if we are NOT in a controlled dive
        if (cinematic.mode != CinematicMode.DIVE) stopCinematic()

        isDragging = true
        touchCount = 1
        lastDragX = x
        lastDragY = y

        // Kill momentum on grab
        state.thetaVelocity = 0.0
        state.phiVelocity = 0.0
    }

    fun onPointerMove(x: Double, y: Double) {
        if (!isDragging) return
        val deltaX = x - lastDragX
        val deltaY = y - lastDragY

        val sensitivity = 0.005

        // Directly mutate physics state
        // Note: We add to position AND set velocity (for "throw" momentum on release)
        state.theta += deltaX * sensitivity
        state.phi = (state.phi + deltaY * sensitivity).coerceIn(0.001, PI - 0.001)
        state.thetaVelocity = deltaX * sensitivity * 0.5
        state.phiVelocity = deltaY * sensitivity * 0.5

        lastDragX = x
        lastDragY = y
    }

    fun onPointerUp() {
        isDragging = false
        touchCount = 0
    }

    fun clearMomentum() {
        state.thetaVelocity = 0.0
        state.phiVelocity = 0.0
        state.zoomVelocity = 0.0
    }

    fun setTouchCount(count: Int) { touchCount = count.coerceAtLeast(0) }

    /** Two-finger pan (the reference applies the same sensitivity to the touch centre delta). */
    fun onPan(deltaX: Double, deltaY: Double) {
        val sensitivity = 0.003
        state.theta += deltaX * sensitivity
        state.phi = (state.phi + deltaY * sensitivity).coerceIn(0.001, PI - 0.001)
    }

    fun onPinchStart(distance: Double) {
        lastPinchDistance = distance
    }

    /** Pinch zoom: `zoomDelta = (1 - ratio) * 2.0`, exactly as the touch handler computes it. */
    fun onPinch(distance: Double, currentZoom: Double): Double {
        if (lastPinchDistance <= 0.0 || distance <= 0.0) {
            lastPinchDistance = distance
            return currentZoom
        }
        val ratio = distance / lastPinchDistance
        val zoomDelta = (1.0 - ratio) * 2.0
        lastPinchDistance = distance
        return clampZoom(currentZoom + zoomDelta, currentZoom)
    }

    /** Wheel / scroll zoom (`sensitivity = 0.005`). */
    fun onScrollZoom(scrollDelta: Double, currentZoom: Double): Double {
        if (cinematic.mode != CinematicMode.DIVE) stopCinematic()

        val sensitivity = 0.005
        val zoomDelta = scrollDelta * sensitivity
        state.zoomVelocity = zoomDelta * 0.3
        return clampZoom(currentZoom + zoomDelta, currentZoom)
    }

    /** Keyboard-style nudge: adds directly to the angular momentum. */
    fun nudge(dTheta: Double, dPhi: Double) {
        stopCinematic()
        state.thetaVelocity += dTheta
        state.phiVelocity += dPhi
    }

    // ---------------------------------------------------------------------------------------
    // Cinematic director
    // ---------------------------------------------------------------------------------------

    /**
     * Start a director-mode cinematic.
     *
     * @param reducedMotion honored like the reference's `prefers-reduced-motion` guard: cinematic
     *   auto-orbit is decorative, so the caller's motion preference silently no-ops here.
     */
    fun startCinematic(mode: CinematicMode, params: ChalSimulationParams, reducedMotion: Boolean) {
        if (reducedMotion || params.reducedMotion) return

        // 1. Clean up existing state (Force Stop any previous cinematic)
        stopCinematic()

        // 2. Clear interaction state to prevent "Phantom Drag" blocking the animation
        isDragging = false
        touchCount = 0

        // 3. Clear velocities to prevent "Phantom Momentum" jitter
        state.thetaVelocity = 0.0
        state.phiVelocity = 0.0
        state.zoomVelocity = 0.0

        // 4. Capture current state as start state
        cinematic = CinematicState(
            active = true,
            mode = mode,
            elapsedSeconds = 0.0,
            startAutoSpin = params.autoSpin,
            startTheta = state.theta,
            startPhi = state.phi,
            startZoom = params.zoom,
            velocity = 0.0,
            angularMomentum = 0.0,
            recovering = false,
            recoverySeconds = 0.0
        )

        if (mode == CinematicMode.DIVE) {
            // Angular Momentum: L = r^2 * omega
            // omega ~0.3 for a wide, dramatic spiral
            val initialOmega = 0.3
            val r = params.zoom
            cinematic.angularMomentum = r * r * initialOmega

            // Barely a nudge inward. Act 1 ("The Hesitation") handles the
            // slow buildup. This just tips you over the edge.
            cinematic.velocity = -0.15

            // Start above the disk for the establishing wide shot.
            // ~54 degrees from pole -> looking down at the disk
            state.phi = PI * 0.3

            // Offset theta for a dynamic spiral entry
            state.theta += PI * 0.25
        }
    }

    fun stopCinematic(): Double? {
        val restoreAutoSpin = if (isCinematic) cinematic.startAutoSpin else null
        cinematic.recovering = false
        cinematic.active = false
        cinematic.mode = null
        cinematic.velocity = 0.0
        cinematic.angularMomentum = 0.0
        return restoreAutoSpin
    }

    // ---------------------------------------------------------------------------------------
    // Animation loop
    // ---------------------------------------------------------------------------------------

    /**
     * Advance the camera one frame.
     *
     * @param dtSeconds frame delta in seconds, already capped at 0.1 (lagguard).
     * @param params current simulation parameters.
     * @param applyParams sink for parameter mutations (zoom, auto-spin, paused) performed by the
     *   cinematic, mirroring `setParams` in the reference.
     */
    fun update(
        dtSeconds: Double,
        params: ChalSimulationParams,
        applyParams: (ChalSimulationParams.() -> ChalSimulationParams) -> Unit
    ) {
        // PAUSE GUARD: Skip all physics when simulation is paused
        if (params.paused) { clearMomentum(); return }
        if (params.reducedMotion) clearMomentum()
        val dt = dtSeconds.coerceIn(0.0, 0.1)
        val frameUnits = dt * ChalMotion.REFERENCE_FPS
        if (cinematic.active) cinematic.elapsedSeconds += dt
        if (cinematic.recovering) cinematic.recoverySeconds += dt

        // --- RECOVERY PHASE: Smooth emergence back to pre-cinematic position ---
        // Like waking from a dream -- initially fast pullback, then gentle settling.
        if (cinematic.recovering) {
            val recoverDuration = 3.5 // seconds (longer = more dramatic)
            val elapsed = cinematic.recoverySeconds
            val progress = min(elapsed / recoverDuration, 1.0)

            // Quartic ease-out: stronger initial pull, very gentle deceleration
            val ease = 1.0 - Math.pow(1.0 - progress, 4.0)

            val targetTheta = cinematic.startTheta
            val targetPhi = cinematic.startPhi
            val targetZoom = cinematic.startZoom

            // Accelerating convergence rate
            val lerpRate = 1.0 - (1.0 - (0.05 + ease * 0.06)).pow(frameUnits)
            state.theta += (targetTheta - state.theta) * lerpRate
            state.phi += (targetPhi - state.phi) * lerpRate

            // Interpolate zoom
            val currentZoom = params.zoom
            val zoomDelta = (targetZoom - currentZoom) * lerpRate
            if (abs(zoomDelta) > 0.001) {
                applyParams { copy(zoom = zoom + zoomDelta) }
            }

            // Check if recovery is complete
            val thetaDist = abs(targetTheta - state.theta)
            val phiDist = abs(targetPhi - state.phi)
            val zoomDist = abs(targetZoom - params.zoom)

            if ((thetaDist < 0.01 && phiDist < 0.01 && zoomDist < 0.1) || progress >= 1.0) {
                state.theta = targetTheta
                state.phi = targetPhi
                cinematic.recovering = false
                applyParams {
                    copy(
                        zoom = targetZoom,
                        autoSpin = cinematic.startAutoSpin
                    )
                }
            }

            state.phi = max(0.001, min(PI - 0.001, state.phi))
            return
        }

        if (cinematic.active && cinematic.mode != null) {
            if (cinematic.mode == CinematicMode.ORBIT) {
                updateOrbit(dt, params, applyParams)
            } else {
                updateDive(dt, params, applyParams)
            }

            // Apply Damping for user input during cinematic
            val cinematicDecay = 0.95.pow(frameUnits)
            val cinematicTravel = momentumTravel(0.95, frameUnits)
            state.theta += state.thetaVelocity * cinematicTravel
            state.phi += state.phiVelocity * cinematicTravel
            state.thetaVelocity *= cinematicDecay
            state.phiVelocity *= cinematicDecay

            state.phi = max(0.001, min(PI - 0.001, state.phi))
            return
        }

        // --- INTERACTIVE MODE: USER CONTROL ---
        // Apply Drag Inertia / Momentum
        val decay = state.damping.pow(frameUnits)
        val travel = momentumTravel(state.damping, frameUnits)
        val zoomTravel = state.zoomVelocity * travel
        state.theta += state.thetaVelocity * travel
        state.phi += state.phiVelocity * travel
        state.thetaVelocity *= decay
        state.phiVelocity *= decay
        state.zoomVelocity *= decay

        // Auto-Spin
        val spinSpeed = params.autoSpin
        if (!params.reducedMotion && !isDragging && touchCount == 0 && abs(state.thetaVelocity) < 0.0001) {
            state.theta += ChalMotion.radiansPerSecond(spinSpeed) * dt
        }

        // Constraints
        state.phi = max(0.001, min(PI - 0.001, state.phi))
        state.theta %= 2.0 * PI
        if (state.theta < 0.0) state.theta += 2.0 * PI

        // Deadzone
        if (abs(state.thetaVelocity) < 0.00001) state.thetaVelocity = 0.0
        if (abs(state.phiVelocity) < 0.00001) state.phiVelocity = 0.0
        if (abs(state.zoomVelocity) < 0.00001) state.zoomVelocity = 0.0

        // Zoom momentum is applied to the params (interactive mode only)
        if (abs(state.zoomVelocity) > 0.0001) {
            val delta = zoomTravel
            applyParams { copy(zoom = clampZoom(zoom + delta, zoom)) }
        }
    }

    /**
     * ORBIT TOUR: "THE GRAND SURVEY"
     * 4-Act cinematic orbit with Keplerian speed variation, dramatic vertical sweeps, and zoom
     * breathing.
     */
    private fun updateOrbit(
        dtSeconds: Double,
        params: ChalSimulationParams,
        applyParams: (ChalSimulationParams.() -> ChalSimulationParams) -> Unit
    ) {
        val t = cinematic.elapsedSeconds
        val orbitMaxDuration = 120.0

        if (t > orbitMaxDuration) {
            cinematic.active = false
            cinematic.mode = null
            cinematic.recovering = true
            cinematic.recoverySeconds = 0.0
            return
        }

        // --- Act boundaries ---
        val act1End = 15.0 // The Reveal
        val act2End = 45.0 // The Descent & Sweep
        val act3End = 75.0 // The Close Pass
        // ACT4: 75 - 120       // The Contemplation

        // --- Smooth blending function ---
        // Hermite smoothstep for act transitions (no pops or sudden changes)
        fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
            val v = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
            return v * v * (3.0 - 2.0 * v)
        }

        // Each act has a characteristic distance. We crossfade between them.
        var targetDist: Double
        var targetPhi: Double
        var orbitSpeed: Double

        val mass = params.mass
        val minSafe = max(15.0, mass * 3.5)

        if (t < act1End) {
            // ACT 1: THE REVEAL -- Pull back to wide establishing shot
            // Camera rises above the disk (low phi = looking down)
            val actProgress = t / act1End
            val easeIn = smoothstep(0.0, 1.0, actProgress)

            // Zoom out from current to a commanding wide shot
            targetDist = minSafe + 18.0 + easeIn * 8.0 // ~33 -> 41 Rs
            // Rise above the disk: from default angle toward ~70 deg (more overhead)
            val overheadAngle = 70.0 * PI / 180.0
            targetPhi = ChalCameraConfig.DEFAULT_VERTICAL_ANGLE +
                (overheadAngle - ChalCameraConfig.DEFAULT_VERTICAL_ANGLE) * easeIn
            // Slow, majestic rotation
            orbitSpeed = 0.15 + easeIn * 0.1 // 0.15 -> 0.25 rad/s
        } else if (t < act2End) {
            // ACT 2: THE DESCENT & SWEEP -- Drop back to disk level, speed up
            val actProgress = (t - act1End) / (act2End - act1End)
            val ease = smoothstep(0.0, 1.0, actProgress)

            // Come closer and back to disk plane
            targetDist = minSafe + 18.0 - ease * 10.0 // 41 -> 23 Rs
            // Descend back toward equatorial view
            val overheadAngle = 70.0 * PI / 180.0
            targetPhi = overheadAngle +
                (ChalCameraConfig.DEFAULT_VERTICAL_ANGLE - overheadAngle) * ease
            // Speed up as we get closer (Kepler's 2nd law feel)
            orbitSpeed = 0.25 + ease * 0.3 // 0.25 -> 0.55 rad/s
        } else if (t < act3End) {
            // ACT 3: THE CLOSE PASS -- Skim near the photon sphere
            val actProgress = (t - act2End) / (act3End - act2End)

            // Elliptical path: close periapsis at center of act, wider at edges
            // Use a smooth pulse that dips close then pulls back
            val periapsisPhase = sin(actProgress * PI) // 0->1->0
            targetDist = minSafe + 8.0 - periapsisPhase * 6.0 // 23 -> 17 -> 23
            // Slight dip below equatorial for drama, then back
            val equatorialTilt = 3.0 * PI / 180.0
            targetPhi = ChalCameraConfig.DEFAULT_VERTICAL_ANGLE -
                equatorialTilt * sin(actProgress * PI * 2.0)
            // Fastest at periapsis (whip effect)
            orbitSpeed = 0.55 + periapsisPhase * 0.35 // 0.55 -> 0.90 -> 0.55
        } else {
            // ACT 4: THE CONTEMPLATION -- Wide, reverent, slow
            val actProgress = (t - act3End) / (orbitMaxDuration - act3End)
            val ease = smoothstep(0.0, 1.0, actProgress)

            // Pull way back for the "big picture" moment
            targetDist = minSafe + 10.0 + ease * 18.0 // 25 -> 43 Rs
            // Gentle rise for a slightly elevated perspective
            val contemplativeAngle = 85.0 * PI / 180.0
            targetPhi = ChalCameraConfig.DEFAULT_VERTICAL_ANGLE +
                (contemplativeAngle - ChalCameraConfig.DEFAULT_VERTICAL_ANGLE) * ease * 0.5
            // Decelerate to a meditative pace
            orbitSpeed = 0.55 - ease * 0.35 // 0.55 -> 0.20 rad/s
        }

        // --- Apply with smooth interpolation (no pops) ---
        val currentZoom = params.zoom
        val frameUnits = dtSeconds * ChalMotion.REFERENCE_FPS
        val zoomLerp = currentZoom + (targetDist - currentZoom) * (1.0 - 0.975.pow(frameUnits))
        if (abs(zoomLerp - currentZoom) > 0.001) {
            applyParams { copy(zoom = zoomLerp) }
        }

        // Subtle "breathing" -- micro zoom oscillation for organic feel
        val breathe = sin(t * 0.7) * 0.3

        // "Handheld" micro-wobble (2-axis) for realism
        val wobbleX = sin(t * 0.31) * 0.008 + cos(t * 0.17) * 0.005
        val wobbleY = cos(t * 0.23) * 0.006 + sin(t * 0.41) * 0.004

        if (!isDragging && touchCount == 0) {
            // Smooth phi tracking with wobble
            state.phi += (targetPhi + wobbleY - state.phi) * (1.0 - 0.982.pow(frameUnits))
            // Orbit rotation with Keplerian speed variation
            state.theta += dtSeconds * orbitSpeed + wobbleX * frameUnits
        }

        // Apply breathing to zoom
        if (abs(breathe) > 0.01) {
            applyParams { copy(zoom = max(minSafe, zoom + breathe * 0.01 * frameUnits)) }
        }
    }

    /**
     * INFALL DIVE: "THE DESCENT INTO GARGANTUA"
     * 3-Act geodesic plunge with dramatic pacing.
     *   Act 1: The Hesitation (0-8s)  -- Deep breath before the fall
     *   Act 2: The Commitment (8-20s) -- Point of no return
     *   Act 3: The Maelstrom (20s+)   -- Physics takes over
     */
    private fun updateDive(
        dtSeconds: Double,
        params: ChalSimulationParams,
        applyParams: (ChalSimulationParams.() -> ChalSimulationParams) -> Unit
    ) {
        val t = cinematic.elapsedSeconds
        val r = params.zoom

        // --- Act-dependent gravity ---
        // Each act has escalating gravitational intensity.
        // This creates the "Nolan pacing" -- deliberate, then overwhelming.
        val act1End = 8.0
        val act2End = 20.0

        var gravityScale: Double
        var phiDriftRate: Double // How fast camera aligns to equatorial
        var thetaWobble: Double // Organic camera shake

        if (t < act1End) {
            // ACT 1: THE HESITATION
            // Almost no gravity. Camera floats. You feel weightless.
            // A slight pull-back even, for the "establishing wide."
            val actProgress = t / act1End
            gravityScale = 3.0 + actProgress * 3.0 // 3 -> 6 (gentle)
            phiDriftRate = 0.05 // Very slow tilt

            // Subtle "last look" wobble -- the camera is studying the black hole
            thetaWobble = sin(t * 0.4) * 0.003
        } else if (t < act2End) {
            // ACT 2: THE COMMITMENT
            // Gravity becomes real. The spiral tightens. No turning back.
            val actProgress = (t - act1End) / (act2End - act1End)
            gravityScale = 6.0 + actProgress * 12.0 // 6 -> 18
            phiDriftRate = 0.1 + actProgress * 0.15 // Accelerating tilt

            // Growing unease - camera vibration increases
            thetaWobble = sin(t * 0.8) * 0.005 * (1.0 + actProgress)
        } else {
            // ACT 3: THE MAELSTROM
            // Full Newtonian fury. The vortex consumes everything.
            // Gravity is overwhelming. Spin is frantic.
            val actDuration = t - act2End
            val intensity = min(actDuration * 0.15, 1.0) // Ramps over ~7s
            gravityScale = 18.0 + intensity * 20.0 // 18 -> 38
            phiDriftRate = 0.25 + intensity * 0.25 // Fast equatorial lock

            // Violent camera shake near the horizon
            val shakeIntensity = 0.008 + intensity * 0.015
            thetaWobble = sin(t * 2.1) * shakeIntensity + cos(t * 3.7) * shakeIntensity * 0.6
        }

        // --- Radial Physics ---
        val gravity = -gravityScale / (r * r + 0.1)
        cinematic.velocity += gravity * dtSeconds
        val newR = r + cinematic.velocity * dtSeconds

        // --- Angular Physics (Conservation of Momentum: L = r^2 * omega) ---
        val l = cinematic.angularMomentum
        val omegaProp = l / (newR * newR + 0.1)
        state.theta += omegaProp * dtSeconds + thetaWobble * dtSeconds * ChalMotion.REFERENCE_FPS

        // --- Inclination: Drift toward equatorial plane ---
        val distToEquator = PI * 0.5 - state.phi
        state.phi += distToEquator * dtSeconds * phiDriftRate

        // Subtle phi wobble for "tumbling through spacetime" feel
        if (t > act2End) {
            val phiWobble = sin(t * 1.7) * 0.004 + cos(t * 2.9) * 0.003
            state.phi += phiWobble * dtSeconds * ChalMotion.REFERENCE_FPS
        }

        // --- HORIZON CROSSING LOGIC ---
        if (newR < 2.0) {
            // Horizon crossing -> Begin smooth recovery to pre-dive position
            cinematic.active = false
            cinematic.mode = null
            cinematic.velocity = 0.0
            cinematic.angularMomentum = 0.0
            cinematic.recovering = true
            cinematic.recoverySeconds = 0.0

            // Clear velocities so recovery isn't fighting residual momentum
            state.thetaVelocity = 0.0
            state.phiVelocity = 0.0
            state.zoomVelocity = 0.0

            // Restore autoSpin and unpause
            applyParams {
                copy(
                    autoSpin = cinematic.startAutoSpin,
                    paused = false
                )
            }
        } else {
            // Apply Zoom (only during active dive)
            applyParams { copy(zoom = max(0.2, newR)) }
        }
    }

    /** Full reset: stop the cinematic, clear momentum, restore the default framing. */
    fun reset(applyParams: (ChalSimulationParams.() -> ChalSimulationParams) -> Unit) {
        // 1. Force Stop Cinematic (Full cleanup)
        cinematic = CinematicState()

        // 2. Reset Physics State
        state = CameraState()

        // 3. Reset Simulation Params (Zoom, AutoSpin, Pause)
        applyParams {
            copy(
                zoom = ChalCameraConfig.DEFAULT_ZOOM,
                autoSpin = ChalCameraConfig.DEFAULT_AUTO_SPIN,
                cameraYaw = 0.5,
                verticalAngle = ChalCameraConfig.DEFAULT_VERTICAL_ANGLE * 180.0 / PI,
                paused = false // Guarantee simulation runs after reset
            )
        }
    }

    /** Restore a saved pose once, before the surface's first frame. */
    fun restore(params: ChalSimulationParams) {
        stopCinematic()
        state = CameraState(
            theta = ChalRenderPolicy.normalizeYaw(params.cameraYaw) * 2.0 * PI,
            phi = (params.verticalAngle * PI / 180.0).coerceIn(0.001, PI - 0.001)
        )
        isDragging = false
        touchCount = 0
    }

    /** Scenarios reset inclination, not yaw, consistently with the native camera. */
    fun resetScenarioPitch() {
        stopCinematic()
        state.phi = ChalCameraConfig.DEFAULT_VERTICAL_ANGLE
        state.thetaVelocity = 0.0
        state.phiVelocity = 0.0
        state.zoomVelocity = 0.0
    }

    /** Sum the reference's damp-then-move steps, including fractional 60 Hz steps. */
    private fun momentumTravel(damping: Double, frameUnits: Double): Double = when {
        damping == 1.0 -> frameUnits
        damping <= 0.0 -> 0.0
        else -> damping * (1.0 - damping.pow(frameUnits)) / (1.0 - damping)
    }

    private fun clampZoom(value: Double, fallback: Double): Double {
        if (!value.isFinite()) return fallback
        return max(ChalCameraConfig.MIN_ZOOM, min(ChalCameraConfig.MAX_ZOOM, value))
    }
}
