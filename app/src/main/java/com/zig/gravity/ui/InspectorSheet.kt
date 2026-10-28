package com.zig.gravity.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.ui.theme.squircleShape
import com.zig.gravity.physics.BodyType
import com.zig.gravity.physics.EngineConstants
import com.zig.gravity.sim.BodyCatalog
import com.zig.gravity.sim.CameraState
import com.zig.gravity.sim.SimulationViewModel
import com.zig.gravity.ui.theme.LocalGravityColors
import com.zig.gravity.util.SandboxFormat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * §3.11 Inspector — a contextual bottom sheet, so the tabletop stays visible behind it.
 *
 * Every control here mutates real simulation state through the ViewModel, which recomputes
 * accelerations and invalidates the prediction on the way out (§3.5 synchronisation rule).
 * Nothing in this sheet changes a displayed value only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectorSheet(vm: SimulationViewModel, onDismiss: () -> Unit) {
    val c = LocalGravityColors.current
    val fa = vm.persian
    // §4 ROOT CAUSE FIX. `snapshot` is a plain DoubleArray holder, not Compose state, so reading
    // it alone never re-runs this composable and every slider was permanently stale. Touching the
    // frame tick here subscribes the sheet to the simulation, so a slider's numeric readout, its
    // thumb and the tabletop all move together.
    @Suppress("UNUSED_VARIABLE") val tick = vm.frameTick
    val snap = vm.snapshot
    val slot = snap.slotOfId(vm.selectedId)
    if (slot < 0) {
        // The body was merged or removed while the sheet was open: close on the next frame rather
        // than writing state during composition.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val type = snap.typeOf(slot)
    val key = snap.catalogKey[slot]
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.chrome,
        contentColor = c.onSurface,
        modifier = Modifier.testTag("inspector_sheet")
    ) {
        // §16 ROOT CAUSE FIX. This was a plain Column with no scroll modifier, so everything past
        // the fold was simply unreachable: the content was measured, laid out, and clipped by the
        // sheet. It now scrolls, keeps clear of the keyboard (imePadding) and of the system
        // navigation area (navigationBarsPadding), and ends with a spacer so the very last control
        // can always be scrolled fully clear of the gesture strip.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp)
                .imePadding()
                .navigationBarsPadding()
                .testTag("inspector_scroll"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ---- header ---------------------------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(c.bodyTone(BodyCatalog.colorOf(key, type)))
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = BodyCatalog.nameOf(key, type, fa),
                        color = c.onSurface,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = BodyCatalog.let { if (fa) it.typeNameFa(type) else it.typeNameEn(type) },
                        color = c.onSurfaceDim,
                        fontSize = 12.sp
                    )
                }
                IconAction(Icons.Filled.ContentCopy, if (fa) "تکثیر" else "Duplicate", "inspector_duplicate") {
                    vm.duplicateSelected()
                }
                IconAction(Icons.Filled.Delete, if (fa) "حذف" else "Remove", "inspector_remove") {
                    vm.removeSelected()
                    onDismiss()
                }
            }

            // ---- §7/§12 follow -------------------------------------------------------------
            //
            // A wormhole mouth is scenery, not a body you can ride, so it is the one thing here
            // that offers no Follow control (§27).
            if (type != BodyType.WORMHOLE_MOUTH) {
                val following = vm.followTargetId == vm.selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(squircleShape(14.dp))
                        .background(
                            if (following) c.accent.copy(alpha = 0.18f)
                            else c.onSurface.copy(alpha = 0.05f)
                        )
                        .border(
                            1.dp,
                            if (following) c.accent.copy(alpha = 0.55f) else c.chromeBorder,
                            squircleShape(14.dp)
                        )
                        .clickableTag("inspector_follow") { vm.toggleFollow(vm.selectedId) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (following) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                        contentDescription = null,
                        tint = if (following) c.accent else c.onSurfaceDim,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (following) {
                                if (fa) "دنبال نکن" else "Unfollow"
                            } else {
                                if (fa) "دنبال کن" else "Follow"
                            },
                            color = if (following) c.accent else c.onSurface,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (fa) {
                                "دوربین این جسم را دنبال می‌کند. حرکت خودِ جسم تغییر نمی‌کند."
                            } else {
                                "The camera tracks this body. Its motion is unchanged."
                            },
                            color = c.onSurfaceDim,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Divider()

            // ---- §3 physical mass ---------------------------------------------------------------
            SectionTitle(if (fa) "جرم فیزیکی" else "Physical mass")
            if (type.massEditable) {
                val range = BodyType.massRange(type, snap.mass[slot])
                val lo = log10(maxOf(range.start, 1.0e12))
                val hi = log10(maxOf(range.endInclusive, 1.0e13))
                val current = log10(maxOf(snap.mass[slot], 1.0e12)).coerceIn(lo, hi)
                LabelRow(
                    title = if (fa) "جرم" else "Mass",
                    value = SandboxFormat.mass(snap.mass[slot], fa),
                    hint = SandboxFormat.kilograms(snap.mass[slot], fa)
                )
                SandboxSlider(
                    value = current.toFloat(),
                    onValueChange = { v -> vm.setMass(vm.selectedId, 10.0.pow(v.toDouble())) },
                    valueRange = lo.toFloat()..hi.toFloat(),
                    tag = "inspector_mass_slider"
                )
            } else {
                LabelRow(
                    title = if (fa) "جرم" else "Mass",
                    value = if (fa) "بی‌جرم" else "Massless",
                    hint = if (fa) "گرانش را حس می‌کند اما خودش گرانش ندارد." else "Feels gravity, exerts none."
                )
            }

            // ---- §3 physical radius (read-only: the catalog owns it) ----------------------------
            Divider()
            SectionTitle(if (fa) "شعاع فیزیکی" else "Physical radius")
            LabelRow(
                title = if (fa) "شعاع واقعی جسم" else "Real body radius",
                value = SandboxFormat.distance(BodyCatalog.realRadiusOf(key, type, snap.mass[slot]), fa),
                hint = (if (fa) "شعاع برخورد در صحنه: " else "Collision radius in the scene: ") +
                        SandboxFormat.distance(snap.radiusDp[slot] * vm.arrays.metersPerDp, fa)
            )

            // ---- §3/§11 visual size, kept explicitly separate from the physical radius ----------
            Divider()
            SectionTitle(if (fa) "اندازه نمایشی" else "Visual size")
            Text(
                text = if (fa)
                    "اندازه نمایشی فقط برای دیده‌شدن است و شعاع برخورد فیزیکی را تغییر نمی‌دهد."
                else
                    "Visual size is for legibility only; it does not change the physical collision radius.",
                color = c.onSurfaceDim.copy(alpha = 0.75f),
                fontSize = 10.sp
            )
            LabelRow(
                title = if (fa) "اندازه نمایشی" else "Visual size",
                value = SandboxFormat.fixed(snap.radiusDp[slot], 1, fa) + " dp",
                hint = (if (fa) "شعاع فیزیکی واقعی: " else "Real physical radius: ") +
                        SandboxFormat.distance(BodyCatalog.realRadiusOf(key, type, snap.mass[slot]), fa)
            )
            SandboxSlider(
                value = snap.radiusDp[slot].toFloat().coerceIn(type.minDp.toFloat(), type.maxDp.toFloat()),
                onValueChange = { v -> vm.setRadiusDp(vm.selectedId, v.toDouble()) },
                valueRange = type.minDp.toFloat()..type.maxDp.toFloat(),
                tag = "inspector_size_slider"
            )

            // ---- §3/§20 position ---------------------------------------------------------------
            // Editing these is *exactly* a drag: same ViewModel path, position only, velocity and
            // mass untouched. The range is the framed view, so a body can never be flung off-table.
            Divider()
            SectionTitle(if (fa) "موقعیت" else "Position")
            val spanM = (EngineConstants.SCENE_WIDTH_AU * EngineConstants.AU) /
                    vm.camera.zoom.coerceAtLeast(CameraState.MIN_ZOOM)
            val posLoX = (vm.camera.panX - spanM).toFloat()
            val posHiX = (vm.camera.panX + spanM).toFloat()
            val posLoY = (vm.camera.panY - spanM).toFloat()
            val posHiY = (vm.camera.panY + spanM).toFloat()
            SandboxSlider(
                value = snap.x[slot].toFloat().coerceIn(posLoX, posHiX),
                onValueChange = { v -> vm.setPosition(vm.selectedId, v.toDouble(), snap.y[slot]) },
                valueRange = posLoX..posHiX,
                label = if (fa) "موقعیت افقی (X)" else "Position X",
                readout = SandboxFormat.distance(snap.x[slot], fa),
                tag = "inspector_position_x_slider"
            )
            SandboxSlider(
                value = snap.y[slot].toFloat().coerceIn(posLoY, posHiY),
                onValueChange = { v -> vm.setPosition(vm.selectedId, snap.x[slot], v.toDouble()) },
                valueRange = posLoY..posHiY,
                label = if (fa) "موقعیت عمودی (Y)" else "Position Y",
                readout = SandboxFormat.distance(snap.y[slot], fa),
                tag = "inspector_position_y_slider"
            )

            // ---- §3/§21 velocity -------------------------------------------------------------
            Divider()
            SectionTitle(if (fa) "سرعت" else "Velocity")
            val vxNow = snap.vx[slot]
            val vyNow = snap.vy[slot]
            val speedNow = sqrt(vxNow * vxNow + vyNow * vyNow)
            val guidance = vm.velocityGuidance(vm.selectedId)
            LabelRow(
                title = if (fa) "سرعت" else "Velocity",
                value = SandboxFormat.speed(speedNow, fa),
                hint = (if (fa) "سقف پیشنهادی: " else "Suggested cap: ") + SandboxFormat.speed(guidance, fa)
            )
            // §21 — magnitude and heading, never raw vector components. Changing either alters the
            // trajectory from where the body already is; it never teleports it.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SandboxSlider(
                    value = speedNow.toFloat().coerceIn(0f, guidance.toFloat()),
                    onValueChange = { v -> vm.setSpeedMagnitude(vm.selectedId, v.toDouble()) },
                    valueRange = 0f..guidance.toFloat(),
                    label = if (fa) "تندی" else "Speed",
                    readout = SandboxFormat.speed(speedNow, fa),
                    tag = "inspector_speed_slider",
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(12.dp))
                DirectionDial(
                    angleRad = if (speedNow > 0.0) atan2(vyNow, vxNow) else 0.0,
                    onAngle = { a -> vm.setDirection(vm.selectedId, a) }
                )
            }

            // ---- orbit helper ----------------------------------------------------------------------
            TextAction(
                text = if (fa) "مدار دایره‌ای کن" else "Make orbit circular",
                tag = "inspector_orbit_helper"
            ) { vm.applyOrbitHelper(vm.selectedId) }

            TextAction(
                text = if (fa) "آماده پرتاب (قلاب‌سنگ)" else "Arm slingshot",
                tag = "inspector_slingshot",
                icon = Icons.AutoMirrored.Filled.Send
            ) {
                vm.armSlingshot(vm.selectedId)
                onDismiss()
            }

            // ---- §3 type and collision behaviour --------------------------------------------------
            Divider()
            SectionTitle(if (fa) "نوع و رفتار برخورد" else "Type and collision behaviour")
            LabelRow(
                title = if (fa) "نوع" else "Type",
                value = if (fa) BodyCatalog.typeNameFa(type) else BodyCatalog.typeNameEn(type),
                hint = collisionBehaviourText(type, vm.marbleBounce, fa)
            )

            // ---- type-specific honesty notes ---------------------------------------------------------
            when (type) {
                BodyType.BLACK_HOLE -> {
                    Divider()
                    val rs = EngineConstants.schwarzschildRadius(snap.mass[slot])
                    LabelRow(
                        title = if (fa) "افق رویداد واقعی" else "Real event horizon",
                        value = SandboxFormat.distance(rs, fa),
                        hint = if (fa)
                            "این عدد از r_s = 2GM/c² می‌آید. حلقه‌ای که می‌بینی یک قرارداد نمایشی است و همان مرز گرفته‌شدن در این شبیه‌سازی است؛ محاسبه‌های ما کاملاً نیوتنی است."
                        else
                            "From r_s = 2GM/c². The drawn ring is a display convention and is this sandbox's capture boundary; our maths is purely Newtonian."
                    )
                }
                BodyType.WORMHOLE_MOUTH -> {
                    Divider()
                    LabelRow(
                        title = if (fa) "کرم‌چاله (فرضی)" else "Wormhole (hypothetical)",
                        value = if (fa) "بدون جرم، بدون گرانش" else "Massless, no gravity",
                        hint = if (fa)
                            "یک مدل آموزشی است، نه پدیده‌ای اثبات‌شده. جسم از مرکز یک دهانه وارد و با همان سرعت از دهانه جفت خارج می‌شود."
                        else
                            "A teaching model, not an established phenomenon. A body entering one mouth's centre leaves the partner with the same velocity."
                    )
                }
                BodyType.TEST_MARBLE -> {
                    Divider()
                    ToggleRow(
                        title = if (fa) "برخورد کشسان بین جسم‌های آزمایشی" else "Bounce between test marbles",
                        checked = vm.marbleBounce,
                        tag = "inspector_bounce"
                    ) { vm.setMarbleBounce(it) }
                }
                else -> Unit
            }

            Divider()
            ToggleRow(
                title = if (fa) "نمایش بردارها" else "Show vectors",
                checked = vm.showVectors,
                tag = "inspector_vectors"
            ) { vm.toggleVectors() }
            ToggleRow(
                title = if (fa) "نمایش مرکز جرم" else "Show barycentre",
                checked = vm.showBarycenter,
                tag = "inspector_barycenter"
            ) { vm.toggleBarycenter() }

            if (vm.predictionApproximate) {
                Text(
                    text = if (fa)
                        "پیش‌نمایش تقریبی: این جسم سهم زیادی از جرم سامانه دارد، پس مسیر نقطه‌چین فقط یک تخمین است."
                    else
                        "Approximate preview: this body carries a large share of the system mass, so the dotted path is an estimate.",
                    color = c.onSurfaceDim,
                    fontSize = 11.sp
                )
            }
            // Reach clearance so the bottom-most control clears the gesture strip.
            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun DirectionDial(angleRad: Double, onAngle: (Double) -> Unit) {
    val c = LocalGravityColors.current
    Canvas(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(c.onSurface.copy(alpha = 0.06f))
            .border(1.dp, c.chromeBorder, CircleShape)
            .testTag("inspector_direction_dial")
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = (change.position.x - cx).toDouble()
                    val dy = (cy - change.position.y).toDouble()
                    if (dx != 0.0 || dy != 0.0) onAngle(atan2(dy, dx))
                }
            }
    ) {
        val r = size.minDimension / 2f - 6f
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(c.onSurfaceDim.copy(alpha = 0.35f), r, Offset(cx, cy), style = Stroke(width = 1f))
        val ex = cx + (cos(angleRad) * r).toFloat()
        val ey = cy - (sin(angleRad) * r).toFloat()
        drawLine(c.accent, Offset(cx, cy), Offset(ex, ey), strokeWidth = 2.5f)
        drawCircle(c.accent, 3.5f, Offset(ex, ey))
    }
}

@Composable
private fun LabelRow(title: String, value: String, hint: String? = null) {
    val c = LocalGravityColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = c.onSurfaceDim, fontSize = 12.sp)
            Text(value, color = c.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        if (hint != null) {
            Text(hint, color = c.onSurfaceDim.copy(alpha = 0.75f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    val c = LocalGravityColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = c.onSurface, fontSize = 13.sp)
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.accent,
                checkedTrackColor = c.accent.copy(alpha = 0.35f)
            ),
            modifier = Modifier.testTag(tag)
        )
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    onClick: () -> Unit
) {
    val c = LocalGravityColors.current
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickableTag(tag, onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = c.onSurfaceDim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun TextAction(
    text: String,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.RadioButtonChecked,
    onClick: () -> Unit
) {
    val c = LocalGravityColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(squircleShape(12.dp))
            .background(c.accent.copy(alpha = 0.10f))
            .clickableTag(tag, onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Divider() {
    val c = LocalGravityColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(c.chromeBorder)
    )
}

@Composable
private fun SectionTitle(text: String) {
    val c = LocalGravityColors.current
    Text(
        text = text,
        color = c.accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold
    )
}

/** §3/§12 — plain words for what this body does on contact. */
private fun collisionBehaviourText(type: BodyType, marbleBounce: Boolean, fa: Boolean): String =
    when (type) {
        BodyType.BLACK_HOLE ->
            if (fa) "هر جسمی که به حلقه برسد گرفته و حذف می‌شود؛ خودِ سیاه‌چاله برخورد نمی‌کند."
            else "Anything reaching the ring is captured and removed; the hole itself never collides."
        BodyType.WORMHOLE_MOUTH ->
            if (fa) "برخورد نمی‌کند؛ جسم را به دهانه جفت منتقل می‌کند."
            else "Does not collide; it hands the body to its partner mouth."
        BodyType.TEST_MARBLE ->
            if (marbleBounce)
                (if (fa) "با جسم آزمایشی دیگر کمانه می‌کند، وگرنه بر پایه انرژی برخورد ادغام یا متلاشی می‌شود."
                 else "Bounces off other test marbles; otherwise merges or shatters by impact energy.")
            else
                (if (fa) "بر پایه انرژی برخورد: کم‌انرژی کمانه، متوسط ادغام، پرانرژی متلاشی."
                 else "By impact energy: low bounces, moderate merges, high shatters.")
        else ->
            if (fa) "بر پایه انرژی برخورد: کم‌انرژی کمانه، متوسط ادغام، پرانرژی متلاشی."
            else "By impact energy: low bounces, moderate merges, high shatters."
    }
