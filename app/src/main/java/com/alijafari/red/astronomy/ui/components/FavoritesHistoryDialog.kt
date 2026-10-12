package com.alijafari.red.astronomy.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alijafari.red.astronomy.R
import com.alijafari.red.astronomy.data.catalog.AstronomyCatalog
import com.alijafari.red.astronomy.domain.AppLanguage
import com.alijafari.red.astronomy.ui.MainUiState
import com.alijafari.red.astronomy.ui.MainViewModel
import com.alijafari.red.astronomy.ui.theme.RedCornerRadius
import com.alijafari.red.astronomy.ui.theme.AccentPrimary

@Composable
fun FavoritesHistoryDialog(
    uiState: MainUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val isFa = uiState.language == AppLanguage.PERSIAN
    var selectedTabState by remember { mutableStateOf(0) } // 0: Favorites, 1: Observation Journal

    val allCatalog = remember { AstronomyCatalog.getAllObjects() }
    val favoriteObjects = remember(uiState.favoritesList) {
        allCatalog.filter { uiState.favoritesList.contains(it.id) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("favorites_history_dialog"),
        title = {
            TabRow(
                selectedTabIndex = selectedTabState,
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = selectedTabState == 0,
                    onClick = { selectedTabState = 0 },
                    text = { Text(text = stringResource(R.string.favorites)) }
                )
                Tab(
                    selected = selectedTabState == 1,
                    onClick = { selectedTabState = 1 },
                    text = { Text(text = stringResource(R.string.observation_logs)) }
                )
            }
        },
        text = {
            Box(modifier = Modifier.height(380.dp)) {
                if (selectedTabState == 0) {
                    if (favoriteObjects.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            SafeAppLogo(modifier = Modifier.size(48.dp), cornerRadius = 10.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.no_favorites_yet),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(favoriteObjects) { obj ->
                                Card(
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = if (isFa) obj.nameFa else obj.nameEn,
                                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                            )
                                            val constName = if (isFa) obj.constellationFa else obj.constellationEn
                                            Text(
                                                text = constName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        IconButton(onClick = { viewModel.toggleFavoriteById(obj.id, obj.type.name) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    if (uiState.observationLogs.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            SafeAppLogo(modifier = Modifier.size(48.dp), cornerRadius = 10.dp)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (isFa) "دفترچه رصد شما خالی است." else "Observation journal is empty.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(uiState.observationLogs) { log ->
                                Card(
                                    shape = RoundedCornerShape(RedCornerRadius.window),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = log.objectName,
                                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                                )
                                                val logTimeStr = com.alijafari.red.astronomy.astro_engine.TimeEngine.formatDateTime24h(log.observedAt, uiState.calendarSystem, isFa)
                                                Text(
                                                    text = logTimeStr,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            Text(
                                                text = log.notes,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row {
                                                for (i in 1..log.rating) {
                                                    Icon(
                                                        imageVector = Icons.Default.Star,
                                                        contentDescription = null,
                                                        tint = AccentPrimary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                }
                                            }
                                        }

                                        IconButton(onClick = { viewModel.deleteObservationLog(log) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
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
            Button(onClick = onDismiss) {
                Text(text = stringResource(R.string.close))
            }
        }
    )
}
