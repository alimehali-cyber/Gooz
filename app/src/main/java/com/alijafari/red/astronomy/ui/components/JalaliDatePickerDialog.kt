package com.alijafari.red.astronomy.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alijafari.red.astronomy.astro_engine.TimeEngine
import com.alijafari.red.astronomy.ui.theme.LiquidGlassDefaults
import com.alijafari.red.astronomy.ui.theme.LiquidGlassSurface
import com.alijafari.red.astronomy.ui.theme.LocalAppFontFamily
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.RedElevation
import com.alijafari.red.astronomy.ui.theme.RedTheme
import com.alijafari.red.astronomy.ui.theme.squircleShape
import java.util.Calendar

private fun parseJalaliYear(input: String): Int? {
    val englishDigits = input
        .replace('۰', '0').replace('۱', '1').replace('۲', '2').replace('۳', '3').replace('۴', '4')
        .replace('۵', '5').replace('۶', '6').replace('۷', '7').replace('۸', '8').replace('۹', '9')
        .replace('٠', '0').replace('١', '1').replace('٢', '2').replace('٣', '3').replace('٤', '4')
        .replace('٥', '5').replace('٦', '6').replace('٧', '7').replace('٨', '8').replace('٩', '9')
        .trim()
    val yr = englishDigits.toIntOrNull() ?: return null
    return if (yr in 1200..1600) yr else null
}

private val PERSIAN_MONTHS_FA = arrayOf(
    "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
    "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
)

private val PERSIAN_WEEKDAYS_SHORT = arrayOf("ش", "ی", "د", "س", "چ", "پ", "ج")

@Composable
fun JalaliDatePickerDialog(
    initialTimestampMs: Long,
    onDismissRequest: () -> Unit,
    onDateConfirmed: (Long) -> Unit
) {
    val initialSh = remember(initialTimestampMs) {
        TimeEngine.toSolarHijri(initialTimestampMs)
    }

    var selectedYear by remember { mutableIntStateOf(initialSh.year) }
    var selectedMonth by remember { mutableIntStateOf(initialSh.month) } // 1..12
    var selectedDay by remember { mutableIntStateOf(initialSh.day) }

    val daysInCurrentMonth = remember(selectedYear, selectedMonth) {
        TimeEngine.getSolarHijriDaysInMonth(selectedYear, selectedMonth)
    }

    LaunchedEffect(daysInCurrentMonth) {
        if (selectedDay > daysInCurrentMonth) {
            selectedDay = daysInCurrentMonth
        }
    }

    var isSelectingYear by remember { mutableStateOf(false) }
    var directYearInput by remember(selectedYear) { mutableStateOf(selectedYear.toString()) }
    var isDirectYearError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("jalali_date_picker_dialog"),
        containerColor = RedTheme.colors.surfaceElevated,
        shape = squircleShape(RedCornerRadius.window),
        title = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "انتخاب تاریخ خورشیدی",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = RedTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                LiquidGlassSurface(
                    shape = squircleShape(12.dp),
                    fallbackColor = RedTheme.colors.accentRed.copy(alpha = 0.15f),
                    fallbackBorder = BorderStroke(1.dp, RedTheme.colors.accentRed.copy(alpha = 0.4f)),
                    style = LiquidGlassDefaults.Card,
                    fallbackShadowElevation = RedElevation.none,
                ) {
                    Text(
                        text = "${TimeEngine.formatPersianNumbers(selectedDay.toString())} ${PERSIAN_MONTHS_FA[(selectedMonth - 1).coerceIn(0, 11)]} ${TimeEngine.formatPersianNumbers(selectedYear.toString())}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = RedTheme.colors.accentRed,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Year Header with Navigation and Direct Entry Trigger
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { selectedYear-- },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Previous Year",
                            tint = RedTheme.colors.textPrimary
                        )
                    }

                    Surface(
                        shape = squircleShape(8.dp),
                        color = if (isSelectingYear) RedTheme.colors.accentRed.copy(alpha = 0.2f) else RedTheme.colors.surfaceVariant,
                        border = BorderStroke(1.dp, if (isSelectingYear) RedTheme.colors.accentRed else RedTheme.colors.border),
                        modifier = Modifier
                            .clickable {
                                isSelectingYear = !isSelectingYear
                                if (isSelectingYear) {
                                    directYearInput = selectedYear.toString()
                                    isDirectYearError = false
                                }
                            }
                            .testTag("jalali_year_header_btn")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Year",
                                tint = RedTheme.colors.accentRed,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "سال ${TimeEngine.formatPersianNumbers(selectedYear.toString())}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = RedTheme.colors.textPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = { selectedYear++ },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
                            contentDescription = "Next Year",
                            tint = RedTheme.colors.textPrimary
                        )
                    }
                }

                if (isSelectingYear) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Direct Year Entry Input Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                textStyle = TextStyle(fontFamily = LocalAppFontFamily.current),
                                value = directYearInput,
                                onValueChange = {
                                    directYearInput = it
                                    isDirectYearError = false
                                },
                                label = { Text("ورود مستقیم سال", fontSize = 11.sp) },
                                placeholder = { Text("مثلاً ۱۳۷۰ یا 1403", fontSize = 11.sp) },
                                singleLine = true,
                                isError = isDirectYearError,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        val parsed = parseJalaliYear(directYearInput)
                                        if (parsed != null) {
                                            selectedYear = parsed
                                            isSelectingYear = false
                                            isDirectYearError = false
                                        } else {
                                            isDirectYearError = true
                                        }
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("jalali_direct_year_input"),
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

                            Button(
                                onClick = {
                                    val parsed = parseJalaliYear(directYearInput)
                                    if (parsed != null) {
                                        selectedYear = parsed
                                        isSelectingYear = false
                                        isDirectYearError = false
                                    } else {
                                        isDirectYearError = true
                                    }
                                },
                                shape = squircleShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed),
                                modifier = Modifier.testTag("jalali_direct_year_apply_btn")
                            ) {
                                Text("پرش", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }

                        if (isDirectYearError) {
                            Text(
                                text = "لطفاً سال معتبر خورشیدی بین ۱۲۰۰ تا ۱۶۰۰ وارد کنید",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }

                        Text(
                            text = "یا انتخاب سریع سال‌های مجاور:",
                            style = MaterialTheme.typography.labelSmall,
                            color = RedTheme.colors.textSecondary
                        )

                        // Quick Year Selector Grid
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val years = (selectedYear - 7..selectedYear + 8).toList()
                            items(years) { yr ->
                                val isSelected = yr == selectedYear
                                Surface(
                                    shape = squircleShape(8.dp),
                                    color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.surfaceVariant,
                                    border = BorderStroke(1.dp, if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.border),
                                    modifier = Modifier.clickable {
                                        selectedYear = yr
                                        isSelectingYear = false
                                    }
                                ) {
                                    Text(
                                        text = TimeEngine.formatPersianNumbers(yr.toString()),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else RedTheme.colors.textPrimary
                                        ),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Month Selector (3x4 Grid)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(12) { idx ->
                            val mNum = idx + 1
                            val isSelected = mNum == selectedMonth
                            Surface(
                                shape = squircleShape(8.dp),
                                color = if (isSelected) RedTheme.colors.accentRed.copy(alpha = 0.25f) else RedTheme.colors.surfaceVariant,
                                border = BorderStroke(1.dp, if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.border),
                                modifier = Modifier.clickable { selectedMonth = mNum }
                            ) {
                                Text(
                                    text = PERSIAN_MONTHS_FA[idx],
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) RedTheme.colors.accentRed else RedTheme.colors.textPrimary
                                    ),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = RedTheme.colors.border, thickness = 0.5.dp)

                    // Day Selector Grid (1..31)
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(7),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(daysInCurrentMonth) { dIdx ->
                            val dNum = dIdx + 1
                            val isSelected = dNum == selectedDay
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) RedTheme.colors.accentRed else Color.Transparent)
                                    .clickable { selectedDay = dNum },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = TimeEngine.formatPersianNumbers(dNum.toString()),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color.White else RedTheme.colors.textPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val calOrig = Calendar.getInstance(TimeEngine.TEHRAN_TIME_ZONE).apply { timeInMillis = initialTimestampMs }
                    val hour = calOrig.get(Calendar.HOUR_OF_DAY)
                    val minute = calOrig.get(Calendar.MINUTE)
                    val second = calOrig.get(Calendar.SECOND)

                    val targetMs = TimeEngine.persianToTimestamp(
                        year = selectedYear,
                        month = selectedMonth,
                        day = selectedDay,
                        hour = hour,
                        minute = minute,
                        second = second,
                        timeZone = TimeEngine.TEHRAN_TIME_ZONE
                    )
                    onDateConfirmed(targetMs)
                    onDismissRequest()
                },
                colors = ButtonDefaults.buttonColors(containerColor = RedTheme.colors.accentRed)
            ) {
                Text("تأیید", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("انصراف", color = RedTheme.colors.textSecondary)
            }
        }
    )
}
