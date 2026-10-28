package com.zig.gargantua.worldline

import com.zig.gargantua.geodesic.PhotonState4D
import com.zig.gargantua.physics.KerrSchildSpacetime
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

/**
 * Unit tests for [RelativisticObjectIntersection] and [RelativisticObject].
 */
class RelativisticObjectIntersectionTest {

    private val spacetime = KerrSchildSpacetime(1.0, 0.8)

    @Test
    fun intersectionDetectedWhenRayPassesThroughObjectWorldTube() {
        val rOrbit = 6.5
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.5)

        // Evaluate object position at T = 2.0
        val objPos = worldline.positionAt(2.0)

        // Construct backward photon ray segment crossing right through the object center at T = 2.0
        val p1 = PhotonState4D(
            t = 2.1,
            x = objPos[0] + 0.3,
            y = objPos[1] + 0.3,
            z = objPos[2],
            p_t = -1.0,
            p_x = -0.5,
            p_y = -0.5,
            p_z = 0.0
        )
        val p2 = PhotonState4D(
            t = 1.9,
            x = objPos[0] - 0.3,
            y = objPos[1] - 0.3,
            z = objPos[2],
            p_t = -1.0,
            p_x = -0.5,
            p_y = -0.5,
            p_z = 0.0
        )

        val hit = RelativisticObjectIntersection.checkIntersection(
            previous = p1,
            current = p2,
            spacetime = spacetime,
            obj = obj,
            camX = 30.0,
            camY = 0.0,
            camZ = 5.0
        )

        assertNotNull("Intersection must be detected when ray passes through world-tube", hit)
        assertTrue("Hit distance must be <= radius", hit!!.hitDistance <= obj.radius)
        assertEquals(2.0, hit.hitT, 0.05)
        assertTrue("Observed radiance must be positive", hit.observedRadiance > 0.0)
        assertTrue("Frequency shift must be strictly positive", hit.frequencyShift > 0.0)
    }

    @Test
    fun intersectionRejectedWhenRayPassesOutsideObjectRadius() {
        val rOrbit = 6.5
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.4)

        val objPos = worldline.positionAt(2.0)

        // Ray passes at distance 2.0M from object (well outside radius 0.4M)
        val p1 = PhotonState4D(
            t = 2.1,
            x = objPos[0] + 2.0,
            y = objPos[1] + 0.3,
            z = objPos[2],
            p_t = -1.0,
            p_x = 0.0,
            p_y = -0.5,
            p_z = 0.0
        )
        val p2 = PhotonState4D(
            t = 1.9,
            x = objPos[0] + 2.0,
            y = objPos[1] - 0.3,
            z = objPos[2],
            p_t = -1.0,
            p_x = 0.0,
            p_y = -0.5,
            p_z = 0.0
        )

        val hit = RelativisticObjectIntersection.checkIntersection(
            previous = p1,
            current = p2,
            spacetime = spacetime,
            obj = obj,
            camX = 30.0,
            camY = 0.0,
            camZ = 5.0
        )

        assertNull("Ray outside object radius must not produce an intersection", hit)
    }

    @Test
    fun intersectionRejectedWhenObjectIsDisabled() {
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = 6.5, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.5, enabled = false)

        val objPos = worldline.positionAt(2.0)
        val p1 = PhotonState4D(t = 2.1, x = objPos[0] + 0.1, y = objPos[1], z = 0.0, p_x = -0.1, p_y = 0.0, p_z = 0.0)
        val p2 = PhotonState4D(t = 1.9, x = objPos[0] - 0.1, y = objPos[1], z = 0.0, p_x = -0.1, p_y = 0.0, p_z = 0.0)

        val hit = RelativisticObjectIntersection.checkIntersection(
            previous = p1,
            current = p2,
            spacetime = spacetime,
            obj = obj,
            camX = 30.0,
            camY = 0.0,
            camZ = 5.0
        )

        assertNull("Disabled object must never produce an intersection", hit)
    }

    @Test
    fun relativisticDopplerBeamingProducesApproachingBlueshiftAndRecedingRedshift() {
        val rOrbit = 7.0
        val worldline = CircularOrbitWorldline(spacetime, rOrbit = rOrbit, phi0 = 0.0)
        val obj = RelativisticObject(worldline, radius = 0.5)

        // At T = 0, object is on +X axis at (7.0, 0, 0) moving in +Y direction (v_Y > 0)
        val objPos = worldline.positionAt(0.0)

        // 1. Observer at +Y (camY = +30.0): object is approaching the observer.
        // Backwards camera ray moves in -Y direction (p_y < 0):
        val pApp1 = PhotonState4D(t = 0.05, x = objPos[0], y = objPos[1] + 0.1, z = 0.0, p_x = 0.0, p_y = -0.4, p_z = 0.0)
        val pApp2 = PhotonState4D(t = -0.05, x = objPos[0], y = objPos[1] - 0.1, z = 0.0, p_x = 0.0, p_y = -0.4, p_z = 0.0)

        val hitApp = RelativisticObjectIntersection.checkIntersection(
            previous = pApp1,
            current = pApp2,
            spacetime = spacetime,
            obj = obj,
            camX = 7.0,
            camY = 30.0,
            camZ = 0.0
        )
        assertNotNull(hitApp)

        // 2. Observer at -Y (camY = -30.0): object is receding from the observer.
        // Backwards camera ray moves in +Y direction (p_y > 0):
        val pRec1 = PhotonState4D(t = 0.05, x = objPos[0], y = objPos[1] - 0.1, z = 0.0, p_x = 0.0, p_y = 0.4, p_z = 0.0)
        val pRec2 = PhotonState4D(t = -0.05, x = objPos[0], y = objPos[1] + 0.1, z = 0.0, p_x = 0.0, p_y = 0.4, p_z = 0.0)

        val hitRec = RelativisticObjectIntersection.checkIntersection(
            previous = pRec1,
            current = pRec2,
            spacetime = spacetime,
            obj = obj,
            camX = 7.0,
            camY = -30.0,
            camZ = 0.0
        )
        assertNotNull(hitRec)

        // Approaching Doppler shift must strictly exceed receding Doppler shift
        assertTrue("Approaching frequency shift g_app (${hitApp!!.frequencyShift}) must exceed receding g_rec (${hitRec!!.frequencyShift})",
            hitApp.frequencyShift > hitRec.frequencyShift)

        // Relativistic beaming: approaching radiance must be higher than receding
        assertTrue("Approaching radiance (${hitApp.observedRadiance}) must exceed receding (${hitRec.observedRadiance})",
            hitApp.observedRadiance > hitRec.observedRadiance)

        // Verify that in the moderate/weak gravity regime where orbital velocity dominates gravitational redshift,
        // an approaching emitter produces net blueshift g > 1.0:
        val rModerate = 15.0
        val modWorldline = CircularOrbitWorldline(spacetime, rOrbit = rModerate, phi0 = 0.0)
        val modObj = RelativisticObject(modWorldline, radius = 0.5)
        val modPos = modWorldline.positionAt(0.0)
        val pBlue1 = PhotonState4D(t = 0.05, x = modPos[0], y = modPos[1] + 0.1, z = 0.0, p_x = 0.0, p_y = -0.8, p_z = 0.0)
        val pBlue2 = PhotonState4D(t = -0.05, x = modPos[0], y = modPos[1] - 0.1, z = 0.0, p_x = 0.0, p_y = -0.8, p_z = 0.0)
        val hitBlue = RelativisticObjectIntersection.checkIntersection(
            pBlue1, pBlue2, spacetime, modObj, camX = modPos[0], camY = 40.0, camZ = 0.0
        )
        assertNotNull(hitBlue)
        assertTrue("Approaching emitter at r=15M with line-of-sight photon must produce net blueshift g > 1.0, got ${hitBlue!!.frequencyShift}",
            hitBlue.frequencyShift > 1.0)
    }
}
