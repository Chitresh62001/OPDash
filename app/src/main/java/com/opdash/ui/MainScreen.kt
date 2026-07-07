package com.opdash.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opdash.logging.Logger
import com.opdash.model.ConnectionState
import com.opdash.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val logListState = rememberLazyListState()

    // Auto-scroll logs to bottom
    LaunchedEffect(Logger.logs.size) {
        if (Logger.logs.isNotEmpty()) {
            logListState.animateScrollToItem(Logger.logs.size - 1)
        }
    }

    Scaffold(
        containerColor = DashBlack,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Pulsing connection indicator
                        ConnectionDot(vm.state)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "OPDash",
                                fontWeight = FontWeight.Black,
                                fontSize = 22.sp,
                                color = TextPrimary,
                                letterSpacing = 1.sp
                            )
                            Text(
                                "Tripper Dash Controller",
                                fontSize = 11.sp,
                                color = TextDim,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DashSurface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // ─── Connection Card ───
            item {
                ConnectionCard(vm)
            }

            // ─── Status Dashboard (only when connected or connecting) ───
            if (vm.isServiceRunning) {
                item {
                    StatusDashboard(vm)
                }

                // ─── Music Card ───
                item {
                    MusicCard(vm)
                }

                // ─── Simulation Controls ───
                item {
                    SimulationCard(vm)
                }
            }

            // ─── Log Viewer ───
            item {
                LogCard(vm, logListState)
            }
        }
    }
}

// ──────────────────────── Connection Indicator Dot ────────────────────────

@Composable
fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.CONNECTED -> AccentGreen
        ConnectionState.CONNECTING_WIFI,
        ConnectionState.CONNECTED_WIFI,
        ConnectionState.STARTING_UDP -> AccentAmber
        ConnectionState.ERROR -> AccentRed
        ConnectionState.IDLE -> TextDim
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dot_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTING_WIFI ||
            state == ConnectionState.STARTING_UDP) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

// ──────────────────────── Connection Card ────────────────────────

@Composable
fun ConnectionCard(vm: MainViewModel) {
    var ssid by remember { mutableStateOf(vm.dashboardSSID) }
    var password by remember { mutableStateOf(vm.password) }

    DashCard {
        Text(
            "CONNECTION",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AccentOrange,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Status row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                vm.state.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when (vm.state) {
                    ConnectionState.CONNECTED -> AccentGreen
                    ConnectionState.ERROR -> AccentRed
                    ConnectionState.IDLE -> TextDim
                    else -> AccentAmber
                }
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                vm.status,
                fontSize = 12.sp,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SSID Input
        OutlinedTextField(
            value = ssid,
            onValueChange = {
                ssid = it
                vm.updateDashboardSSID(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Dashboard SSID", fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentOrange,
                unfocusedBorderColor = TextDim,
                cursorColor = AccentOrange,
                focusedLabelColor = AccentOrange,
                unfocusedLabelColor = TextDim,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Password Input
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                vm.updatePassword(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("WiFi Password", fontSize = 12.sp) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentOrange,
                unfocusedBorderColor = TextDim,
                cursorColor = AccentOrange,
                focusedLabelColor = AccentOrange,
                unfocusedLabelColor = TextDim,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Connect / Disconnect Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { vm.connect() },
                modifier = Modifier.weight(1f),
                enabled = ssid.isNotBlank() && password.isNotBlank() && !vm.isServiceRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentOrange,
                    contentColor = Color.Black,
                    disabledContainerColor = AccentOrange.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("CONNECT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }

            OutlinedButton(
                onClick = { vm.disconnect() },
                modifier = Modifier.weight(1f),
                enabled = vm.isServiceRunning,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentRed
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = vm.isServiceRunning),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("DISCONNECT", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

// ──────────────────────── Status Dashboard ────────────────────────

@Composable
fun StatusDashboard(vm: MainViewModel) {
    DashCard {
        Text(
            "TELEMETRY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AccentCyan,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            GaugeItem(
                label = "TX",
                value = "${vm.packetsSent}",
                color = AccentOrange
            )
            GaugeItem(
                label = "RX",
                value = "${vm.packetsReceived}",
                color = AccentCyan
            )
            GaugeItem(
                label = "STATE",
                value = vm.state.name.take(6),
                color = AccentGreen
            )
        }

        if (vm.lastRxHex.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "LAST RX",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = TextDim,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                vm.lastRxHex.take(64) + if (vm.lastRxHex.length > 64) "…" else "",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = AccentCyan.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun GaugeItem(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(
            value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            color = color
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = TextDim,
            letterSpacing = 1.5.sp
        )
    }
}

// ──────────────────────── Music Card ────────────────────────

@Composable
fun MusicCard(vm: MainViewModel) {
    DashCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "MUSIC",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentAmber,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.weight(1f))

            // Playback indicator
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (vm.isMusicPlaying) AccentGreen.copy(alpha = 0.15f)
                        else AccentRed.copy(alpha = 0.1f)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    if (vm.isMusicPlaying) "▶ PLAYING" else "⏸ PAUSED",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (vm.isMusicPlaying) AccentGreen else AccentRed
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            vm.currentTrack,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

// ──────────────────────── Simulation Controls ────────────────────────

@Composable
fun SimulationCard(vm: MainViewModel) {
    DashCard {
        Text(
            "SIMULATION",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = AccentBlue,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Test dash features without live data sources",
            fontSize = 12.sp,
            color = TextDim
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = { vm.simulateMusicToggle() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentAmber
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("🎵 Music", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = { vm.simulateNavigation() },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AccentCyan
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("🧭 Nav", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ──────────────────────── Log Viewer Card ────────────────────────

@Composable
fun LogCard(
    vm: MainViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState
) {
    DashCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "LIVE LOG",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = AccentGreen,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = { vm.clearLogs() },
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    "CLEAR",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextDim,
                    letterSpacing = 1.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0A0A0A))
                .padding(8.dp)
        ) {
            if (Logger.logs.isEmpty()) {
                Text(
                    "No logs yet.",
                    fontSize = 11.sp,
                    color = TextDim,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(state = listState) {
                    items(Logger.logs) { log ->
                        Text(
                            "› $log",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = AccentGreen.copy(alpha = 0.8f),
                            modifier = Modifier.padding(vertical = 1.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ──────────────────────── Reusable Card Container ────────────────────────

@Composable
fun DashCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = DashCard
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            TextDim.copy(alpha = 0.2f),
                            Color.Transparent
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(16.dp),
            content = content
        )
    }
}