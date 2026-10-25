package com.alijafari.red.astronomy.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.data.database.UserOccasionEntity
import com.alijafari.red.astronomy.domain.*
import com.alijafari.red.astronomy.ui.theme.*
import com.alijafari.red.astronomy.ui.theme.squircleShape
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeMachineControlBar(
    state: TimeMachineState,
    isFa: Boolean,
    calendarSystem: CalendarSystem,
    userOccasions: List<UserOccasionEntity> = emptyList(),
    onSimulatedTimeChange: (Long, String?, Boolean) -> Unit,
    onModeChange: (TimeMachineMode) -> Unit,
    onTogglePlay: () -> Unit,
    onSpeedChange: (TimeSimulationSpeed) -> Unit,
    onToggleReverse: () -> Unit,
    onToggleExpanded: () -> Unit,
    onReturnToLive: () -> Unit,
    onSaveOccasion: (String?, String, Long, (Boolean) -> Unit) -> Unit = { _, _, _, cb -> cb(true) },
    onDeleteOccasion: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showOccasionsModal by remember { mutableStateOf(false) }
    var showBirthdayModal by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }

    val activeTime = state.activeTimeMs

    // Format current simulated date & time
    val formattedDate = remember(activeTime, calendarSystem, isFa) {
        TimeEngine.formatDate(activeTime, calendarSystem, isFa)
    }
    val formattedTime = remember(activeTime, isFa) {
        TimeEngine.formatTime24h(activeTime, isFa)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("time_machine_container")
    ) {
        // --- Information Banner & Main Pill ---
        LiquidGlassSurface(
            shape = squircleShape(RedCornerRadius.window),
            style = LiquidGlassDefaults.Card,
            fallbackColor = if (state.mode == TimeMachineMode.SIMULATION) RedTheme.colors.surfaceElevated else RedTheme.colors.surface,
            fallbackBorder = BorderStroke(
                width = 1.dp,
                color = if (state.mode == TimeMachineMode.SIMULATION) RedTheme.colors.accentRed.copy(alpha = 0.6f) else RedTheme.colors.border
            ),
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, squircleShape(RedCornerRadius.window))
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                // Top Banner Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Mode Indicator Badge
                    LiquidGlassSurface(
                        shape = squircleShape(RedCornerRadius.window),
                        fallbackColor = if (state.mode == TimeMachineMode.SIMULATION) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else StatusGood.copy(alpha = 0.15f),
                        fallbackBorder = BorderStroke(1.dp, if (state.mode == TimeMachineMode.SIMULATION) MaterialTheme.colorScheme.primary else StatusGood),
                        style = LiquidGlassDefaults.Card,
                        fallbackShadowElevation = RedElevation.none,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.mode == TimeMachineMode.SIMULATION) MaterialTheme.colorScheme.primary else StatusGood)
                            )
                            Text(
                                text = if (state.mode == TimeMachineMode.SIMULATION) {
                                    if (isFa) "شبیه‌سازی" else "SIMULATION"
                                } else {
                                    if (isFa) "اکنون زنده" else "LIVE NOW"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (state.mode == TimeMachineMode.SIMULATION) MaterialTheme.colorScheme.primary else StatusGood
                            )
                        }
                    }

                    // Simulation Info Header (Date & Time display)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.isBirthdayMode) {
                                if (isFa) "✨ آسمان روز تولد شما" else "✨ Your Birthday Sky"
                            } else {
                                state.eventName ?: "$formattedDate • $formattedTime"
                            },
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (state.mode == TimeMachineMode.SIMULATION) {
                            Text(
                                text = if (isFa) "زمان: $formattedTime • گرینویچ+۳:۳۰ (${if (state.speed.multiplier >= 3600) if (isFa) state.speed.labelFa else state.speed.labelEn else if (isFa) state.speed.labelFa else state.speed.labelEn})"
                                else "Time: $formattedTime • UTC+03:30 (${state.speed.labelEn})",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Expand / Collapse Toggle Button
                    IconButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Icon(
                            imageVector = if (state.isExpanded) Icons.Default.ExpandLess else Icons.Default.Tune,
                            contentDescription = "Time Machine Controls",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Expanded Controls Row
                AnimatedVisibility(
                    visible = state.isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        // Floating Pill Button Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 📅 Date Pill Button
                            LiquidGlassSurface(
                                shape = squircleShape(RedCornerRadius.window),
                                fallbackColor = MaterialTheme.colorScheme.surfaceVariant,
                                fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .clickable { showDatePicker = true }
                                    .testTag("tm_date_btn"),
                                style = LiquidGlassDefaults.Card,
                                fallbackShadowElevation = RedElevation.none,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.CalendarToday, contentDescription = "Date", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                    Text(text = formattedDate, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            // 🕒 Time Pill Button
                            LiquidGlassSurface(
                                shape = squircleShape(RedCornerRadius.window),
                                fallbackColor = MaterialTheme.colorScheme.surfaceVariant,
                                fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .clickable { showTimePicker = true }
                                    .testTag("tm_time_btn"),
                                style = LiquidGlassDefaults.Card,
                                fallbackShadowElevation = RedElevation.none,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.AccessTime, contentDescription = "Time", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                    Text(text = formattedTime, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            // ▶ / ⏸ Play/Pause Pill Button
                            LiquidGlassSurface(
                                shape = squircleShape(RedCornerRadius.window),
                                fallbackColor = if (state.isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                                modifier = Modifier
                                    .clickable {
                                        if (state.mode == TimeMachineMode.LIVE) {
                                            onModeChange(TimeMachineMode.SIMULATION)
                                        }
                                        onTogglePlay()
                                    }
                                    .testTag("tm_play_pause_btn"),
                                style = LiquidGlassDefaults.Card,
                                fallbackShadowElevation = RedElevation.none,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = if (state.isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (state.isPlaying) (if (isFa) "توقف" else "Pause") else (if (isFa) "پخش" else "Play"),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                        color = if (state.isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // ⚡ Speed Pill Button
                            Box {
                                LiquidGlassSurface(
                                    shape = squircleShape(RedCornerRadius.window),
                                    fallbackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    fallbackBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .clickable { showSpeedMenu = true }
                                        .testTag("tm_speed_btn"),
                                    style = LiquidGlassDefaults.Card,
                                    fallbackShadowElevation = RedElevation.none,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (state.isReverse) Icons.Default.FastRewind else Icons.Default.FastForward,
                                            contentDescription = "Speed",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (isFa) state.speed.labelFa else state.speed.labelEn,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showSpeedMenu,
                                    onDismissRequest = { showSpeedMenu = false },
                                    modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Icon(
                                                    imageVector = if (state.isReverse) Icons.Default.ArrowForward else Icons.Default.ArrowBack,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = if (state.isReverse) (if (isFa) "جهت: مستقیم" else "Direction: Forward")
                                                    else (if (isFa) "جهت: معکوس" else "Direction: Reverse")
                                                )
                                            }
                                        },
                                        onClick = {
                                            onToggleReverse()
                                            showSpeedMenu = false
                                        }
                                    )
                                    HorizontalDivider()
                                    TimeSimulationSpeed.values().forEach { spd ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = if (isFa) spd.labelFa else spd.labelEn,
                                                    fontWeight = if (spd == state.speed) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (spd == state.speed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            onClick = {
                                                onSpeedChange(spd)
                                                showSpeedMenu = false
                                            }
                                        )
                                    }
                                }
                            }

                            // 🔴 LIVE Reset Button
                            LiquidGlassSurface(
                                shape = squircleShape(RedCornerRadius.window),
                                fallbackColor = if (state.mode == TimeMachineMode.LIVE) StatusGood.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                fallbackBorder = BorderStroke(1.dp, if (state.mode == TimeMachineMode.LIVE) StatusGood else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .clickable { onReturnToLive() }
                                    .testTag("tm_live_btn"),
                                style = LiquidGlassDefaults.Card,
                                fallbackShadowElevation = RedElevation.none,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(StatusGood))
                                    Text(
                                        text = if (isFa) "زنده" else "LIVE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                        color = StatusGood
                                    )
                                }
                            }
                        }

                        // Preset Shortcuts Row (Birthday Sky & My Occasions)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // ✨ Birthday Sky Preset Button
                            Surface(
                                shape = squircleShape(14.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showBirthdayModal = true }
                                    .testTag("tm_birthday_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isFa) "✨ روز تولد من" else "✨ My Birthday Sky",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            // ⭐ My Occasions Button
                            Surface(
                                shape = squircleShape(14.dp),
                                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showOccasionsModal = true }
                                    .testTag("tm_my_occasions_btn")
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (isFa) "⭐ رویدادهای من (${userOccasions.size}/20)" else "⭐ My Occasions (${userOccasions.size}/20)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }

                        // --- Timeline Scrubber Slider ---
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(text = "1900", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = RedTheme.colors.textSecondary)
                                Text(text = "1950", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = RedTheme.colors.textSecondary)
                                Text(text = "2000", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = RedTheme.colors.textSecondary)
                                Text(text = "2026", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = RedTheme.colors.accentRed)
                                Text(text = "2050", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = RedTheme.colors.textSecondary)
                                Text(text = "2100", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = RedTheme.colors.textSecondary)
                            }

                            val currentVal = activeTime.toFloat().coerceIn(
                                TimeMachineState.MIN_TIMESTAMP_MS.toFloat(),
                                TimeMachineState.MAX_TIMESTAMP_MS.toFloat()
                            )

                            RedSlider(
                                value = currentVal,
                                onValueChange = { newVal ->
                                    onSimulatedTimeChange(newVal.toLong(), null, false)
                                },
                                valueRange = TimeMachineState.MIN_TIMESTAMP_MS.toFloat()..TimeMachineState.MAX_TIMESTAMP_MS.toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("tm_timeline_slider")
                            )

                            // Quick Step Jump Buttons (-1D, -1H, +1H, +1D)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                AssistChip(
                                    onClick = { onSimulatedTimeChange(activeTime - 86400000L, null, false) },
                                    label = { Text(if (isFa) "۱ روز-" else "-1 Day", fontSize = 10.sp, color = RedTheme.colors.textPrimary) }
                                )
                                AssistChip(
                                    onClick = { onSimulatedTimeChange(activeTime - 3600000L, null, false) },
                                    label = { Text(if (isFa) "۱ ساعت-" else "-1 Hour", fontSize = 10.sp, color = RedTheme.colors.textPrimary) }
                                )
                                AssistChip(
                                    onClick = { onSimulatedTimeChange(activeTime + 3600000L, null, false) },
                                    label = { Text(if (isFa) "۱ ساعت+" else "+1 Hour", fontSize = 10.sp, color = RedTheme.colors.textPrimary) }
                                )
                                AssistChip(
                                    onClick = { onSimulatedTimeChange(activeTime + 86400000L, null, false) },
                                    label = { Text(if (isFa) "۱ روز+" else "+1 Day", fontSize = 10.sp, color = RedTheme.colors.textPrimary) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- DatePicker Dialog (Jalali in Persian mode, Gregorian in English mode) ---
    if (showDatePicker) {
        if (calendarSystem == CalendarSystem.SOLAR_HIJRI || isFa) {
            JalaliDatePickerDialog(
                initialTimestampMs = activeTime,
                onDismissRequest = { showDatePicker = false },
                onDateConfirmed = { pickedMillis ->
                    onSimulatedTimeChange(pickedMillis, null, false)
                }
            )
        } else {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = activeTime
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let { pickedMillis ->
                                val calCurrent = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply { timeInMillis = activeTime }
                                val hour = calCurrent.get(Calendar.HOUR_OF_DAY)
                                val min = calCurrent.get(Calendar.MINUTE)
                                val sec = calCurrent.get(Calendar.SECOND)

                                val calTarget = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply {
                                    timeInMillis = pickedMillis
                                    set(Calendar.HOUR_OF_DAY, hour)
                                    set(Calendar.MINUTE, min)
                                    set(Calendar.SECOND, sec)
                                }
                                onSimulatedTimeChange(calTarget.timeInMillis, null, false)
                            }
                            showDatePicker = false
                        }
                    ) {
                        Text(if (isFa) "تأیید" else "OK")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) {
                        Text(if (isFa) "انصراف" else "Cancel")
                    }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }
    }

    // --- TimePicker Dialog ---
    if (showTimePicker) {
        val cal = remember(activeTime) { Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply { timeInMillis = activeTime } }
        val timePickerState = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )

        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        cal.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        cal.set(Calendar.MINUTE, timePickerState.minute)
                        onSimulatedTimeChange(cal.timeInMillis, null, false)
                        showTimePicker = false
                    }
                ) {
                    Text(if (isFa) "تأیید" else "OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(if (isFa) "انصراف" else "Cancel")
                }
            },
            title = {
                Text(
                    text = if (isFa) "انتخاب ساعت" else "Select Time",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TimePicker(state = timePickerState)
                }
            },
            containerColor = RedTheme.colors.surfaceElevated,
            shape = squircleShape(RedCornerRadius.window)
        )
    }

    // --- Birthday Sky Modal ---
    if (showBirthdayModal) {
        var birthDateMillis by remember { mutableStateOf<Long?>(null) }
        var showBirthDatePicker by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showBirthdayModal = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("✨", fontSize = 22.sp)
                    Text(
                        text = if (isFa) "مشاهده آسمان روز تولد" else "View My Birthday Sky",
                        fontWeight = FontWeight.Bold,
                        color = RedTheme.colors.textPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (isFa) "تاریخ تولد خود را انتخاب کنید تا موقعیت دقیق خورشید، ماه و صور فلکی در لحظه تولدتان محاسبه شود."
                        else "Enter your birth date to observe the exact astronomical sky at the moment of your birth.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = RedTheme.colors.textSecondary
                    )

                    LiquidGlassSurface(
                        shape = squircleShape(RedCornerRadius.window),
                        fallbackColor = RedTheme.colors.surfaceVariant,
                        fallbackBorder = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBirthDatePicker = true },
                        style = LiquidGlassDefaults.Card,
                        fallbackShadowElevation = RedElevation.none,
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = birthDateMillis?.let { TimeEngine.formatDate(it, calendarSystem, isFa) }
                                    ?: (if (isFa) "برای انتخاب تاریخ کلیک کنید" else "Tap to select birth date"),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = if (birthDateMillis != null) RedTheme.colors.accentRed else RedTheme.colors.textSecondary
                            )
                            Icon(Icons.Default.CalendarToday, contentDescription = null, tint = RedTheme.colors.accentRed)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        birthDateMillis?.let { bDate ->
                            onSimulatedTimeChange(bDate, if (isFa) "آسمان روز تولد من" else "My Birthday Sky", true)
                        }
                        showBirthdayModal = false
                    },
                    enabled = birthDateMillis != null,
                    colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed)
                ) {
                    Text(if (isFa) "نمایش آسمان" else "Show Sky", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBirthdayModal = false }) {
                    Text(if (isFa) "انصراف" else "Cancel", color = RedTheme.colors.textSecondary)
                }
            },
            containerColor = RedTheme.colors.surfaceElevated,
            shape = squircleShape(RedCornerRadius.window)
        )

        if (showBirthDatePicker) {
            if (calendarSystem == CalendarSystem.SOLAR_HIJRI || isFa) {
                JalaliDatePickerDialog(
                    initialTimestampMs = birthDateMillis ?: System.currentTimeMillis(),
                    onDismissRequest = { showBirthDatePicker = false },
                    onDateConfirmed = { pickedMillis ->
                        birthDateMillis = pickedMillis
                    }
                )
            } else {
                val bPickerState = rememberDatePickerState()
                DatePickerDialog(
                    onDismissRequest = { showBirthDatePicker = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                bPickerState.selectedDateMillis?.let { birthDateMillis = it }
                                showBirthDatePicker = false
                            }
                        ) {
                            Text(if (isFa) "تأیید" else "OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBirthDatePicker = false }) {
                            Text(if (isFa) "انصراف" else "Cancel")
                        }
                    }
                ) {
                    DatePicker(state = bPickerState)
                }
            }
        }
    }

    // --- "My Occasions" (رویدادهای من) Modal ---
    if (showOccasionsModal) {
        var showAddEditOccasionDialog by remember { mutableStateOf(false) }
        var occasionToEdit by remember { mutableStateOf<UserOccasionEntity?>(null) }

        AlertDialog(
            onDismissRequest = { showOccasionsModal = false },
            modifier = Modifier.testTag("my_occasions_dialog"),
            containerColor = RedTheme.colors.surfaceElevated,
            shape = squircleShape(RedCornerRadius.window),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("⭐", fontSize = 22.sp)
                        Text(
                            text = if (isFa) "رویدادهای من" else "My Occasions",
                            fontWeight = FontWeight.Bold,
                            color = RedTheme.colors.textPrimary
                        )
                    }
                    Surface(
                        shape = squircleShape(12.dp),
                        color = RedTheme.colors.accentRed.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "${userOccasions.size}/20",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = RedTheme.colors.accentRed,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Add Occasion Action Button (max 20)
                    Button(
                        onClick = {
                            occasionToEdit = null
                            showAddEditOccasionDialog = true
                        },
                        enabled = userOccasions.size < 20,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_occasion_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (userOccasions.size >= 20) {
                                if (isFa) "حداکثر ۲۰ رویداد ذخیره شده است" else "Maximum 20 occasions reached"
                            } else {
                                if (isFa) "افزودن رویداد جدید" else "Add New Occasion"
                            },
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    if (userOccasions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isFa) "هیچ رویدادی ذخیره نشده است.\nبا دکمه بالا رویداد دلخواه خود را ثبت کنید."
                                else "No occasions saved yet.\nTap above to create your custom occasion.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RedTheme.colors.textSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                        ) {
                            items(userOccasions, key = { it.id }) { occ ->
                                val occDateStr = TimeEngine.formatDate(occ.timestampMs, calendarSystem, isFa)
                                val occTimeStr = TimeEngine.formatTime24h(occ.timestampMs, isFa)

                                LiquidGlassSurface(
                                    shape = squircleShape(RedCornerRadius.window),
                                    fallbackColor = RedTheme.colors.surfaceVariant,
                                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.border),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onSimulatedTimeChange(occ.timestampMs, occ.title, false)
                                            showOccasionsModal = false
                                        }
                                        .testTag("occasion_item_${occ.id}"),
                                    style = LiquidGlassDefaults.Card,
                                    fallbackShadowElevation = RedElevation.none,
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = occ.title,
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = RedTheme.colors.accentRed
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "$occDateStr • $occTimeStr",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = RedTheme.colors.textSecondary
                                            )
                                        }

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    occasionToEdit = occ
                                                    showAddEditOccasionDialog = true
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "Edit Occasion",
                                                    tint = RedTheme.colors.textSecondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            IconButton(
                                                onClick = {
                                                    onDeleteOccasion(occ.id)
                                                },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete Occasion",
                                                    tint = StatusWarning,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOccasionsModal = false }) {
                    Text(if (isFa) "بستن" else "Close", color = RedTheme.colors.accentRed)
                }
            }
        )

        // Sub-dialog: Add / Edit Occasion
        if (showAddEditOccasionDialog) {
            var occasionTitle by remember { mutableStateOf(occasionToEdit?.title ?: "") }
            var occasionTimestampMs by remember { mutableLongStateOf(occasionToEdit?.timestampMs ?: activeTime) }
            var showSubDatePicker by remember { mutableStateOf(false) }
            var showSubTimePicker by remember { mutableStateOf(false) }

            val subDateStr = remember(occasionTimestampMs, calendarSystem, isFa) {
                TimeEngine.formatDate(occasionTimestampMs, calendarSystem, isFa)
            }
            val subTimeStr = remember(occasionTimestampMs, isFa) {
                TimeEngine.formatTime24h(occasionTimestampMs, isFa)
            }

            AlertDialog(
                onDismissRequest = { showAddEditOccasionDialog = false },
                containerColor = RedTheme.colors.surfaceElevated,
                shape = squircleShape(RedCornerRadius.window),
                title = {
                    Text(
                        text = if (occasionToEdit == null) {
                            if (isFa) "افزودن رویداد جدید" else "Add New Occasion"
                        } else {
                            if (isFa) "ویرایش رویداد" else "Edit Occasion"
                        },
                        fontWeight = FontWeight.Bold,
                        color = RedTheme.colors.textPrimary
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                            value = occasionTitle,
                            onValueChange = { occasionTitle = it },
                            label = { Text(if (isFa) "عنوان رویداد" else "Occasion Name") },
                            placeholder = { Text(if (isFa) "مثلاً سالگرد، شب رصدی، ..." else "e.g. Observation Night") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("occasion_title_input"),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = RedTheme.colors.accentRed,
                                unfocusedBorderColor = RedTheme.colors.border,
                                focusedTextColor = RedTheme.colors.textPrimary,
                                unfocusedTextColor = RedTheme.colors.textPrimary,
                                focusedLabelColor = RedTheme.colors.accentRed,
                                unfocusedLabelColor = RedTheme.colors.textSecondary,
                                focusedPlaceholderColor = RedTheme.colors.textSecondary,
                                unfocusedPlaceholderColor = RedTheme.colors.textSecondary
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Date Selection Button
                            Surface(
                                shape = squircleShape(12.dp),
                                color = RedTheme.colors.surfaceVariant,
                                border = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showSubDatePicker = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = subDateStr,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = RedTheme.colors.accentRed
                                    )
                                    Icon(Icons.Default.CalendarToday, contentDescription = null, tint = RedTheme.colors.accentRed, modifier = Modifier.size(16.dp))
                                }
                            }

                            // Time Selection Button
                            Surface(
                                shape = squircleShape(12.dp),
                                color = RedTheme.colors.surfaceVariant,
                                border = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { showSubTimePicker = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = subTimeStr,
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = RedTheme.colors.accentRed
                                    )
                                    Icon(Icons.Default.AccessTime, contentDescription = null, tint = RedTheme.colors.accentRed, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (occasionTitle.isNotBlank()) {
                                onSaveOccasion(occasionToEdit?.id, occasionTitle.trim(), occasionTimestampMs) { success ->
                                    if (success) {
                                        showAddEditOccasionDialog = false
                                    }
                                }
                            }
                        },
                        enabled = occasionTitle.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed)
                    ) {
                        Text(if (isFa) "ذخیره" else "Save", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddEditOccasionDialog = false }) {
                        Text(if (isFa) "انصراف" else "Cancel", color = RedTheme.colors.textSecondary)
                    }
                }
            )

            if (showSubDatePicker) {
                if (calendarSystem == CalendarSystem.SOLAR_HIJRI || isFa) {
                    JalaliDatePickerDialog(
                        initialTimestampMs = occasionTimestampMs,
                        onDismissRequest = { showSubDatePicker = false },
                        onDateConfirmed = { pickedMillis ->
                            occasionTimestampMs = pickedMillis
                        }
                    )
                } else {
                    val subPickerState = rememberDatePickerState(initialSelectedDateMillis = occasionTimestampMs)
                    DatePickerDialog(
                        onDismissRequest = { showSubDatePicker = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    subPickerState.selectedDateMillis?.let { pickedMillis ->
                                        val calCurrent = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply { timeInMillis = occasionTimestampMs }
                                        val hour = calCurrent.get(Calendar.HOUR_OF_DAY)
                                        val min = calCurrent.get(Calendar.MINUTE)
                                        val sec = calCurrent.get(Calendar.SECOND)

                                        val calTarget = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply {
                                            timeInMillis = pickedMillis
                                            set(Calendar.HOUR_OF_DAY, hour)
                                            set(Calendar.MINUTE, min)
                                            set(Calendar.SECOND, sec)
                                        }
                                        occasionTimestampMs = calTarget.timeInMillis
                                    }
                                    showSubDatePicker = false
                                }
                            ) {
                                Text(if (isFa) "تأیید" else "OK")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showSubDatePicker = false }) {
                                Text(if (isFa) "انصراف" else "Cancel")
                            }
                        }
                    ) {
                        DatePicker(state = subPickerState)
                    }
                }
            }

            if (showSubTimePicker) {
                val cal = remember(occasionTimestampMs) { Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply { timeInMillis = occasionTimestampMs } }
                val subTimePickerState = rememberTimePickerState(
                    initialHour = cal.get(Calendar.HOUR_OF_DAY),
                    initialMinute = cal.get(Calendar.MINUTE),
                    is24Hour = true
                )

                AlertDialog(
                    onDismissRequest = { showSubTimePicker = false },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                cal.set(Calendar.HOUR_OF_DAY, subTimePickerState.hour)
                                cal.set(Calendar.MINUTE, subTimePickerState.minute)
                                occasionTimestampMs = cal.timeInMillis
                                showSubTimePicker = false
                            }
                        ) {
                            Text(if (isFa) "تأیید" else "OK")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSubTimePicker = false }) {
                            Text(if (isFa) "انصراف" else "Cancel")
                        }
                    },
                    title = {
                        Text(
                            text = if (isFa) "انتخاب ساعت" else "Select Time",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TimePicker(state = subTimePickerState)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface,
                    shape = squircleShape(RedCornerRadius.window)
                )
            }
        }
    }
}
