package com.example.transport_ticketing

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.Color as AndroidColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = TicketViewModel()
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TransportTicketingApp(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportTicketingApp(viewModel: TicketViewModel) {
    val state by viewModel.uiState.collectAsState()
    var phoneInput by remember { mutableStateOf("+79991234567") }
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Транспортные билеты (Swarm API)") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ClusterStatusCard(
                containerId = state.activeContainerId,
                isLoading = state.isLoading,
                onPingClick = { viewModel.checkClusterHealth() }
            )

            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Маршруты и покупка") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Мой билет (QR)") }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    ScheduleAndBuySection(
                        phone = phoneInput,
                        onPhoneChange = { phoneInput = it },
                        routes = state.routes,
                        onRefreshRoutes = { viewModel.loadSchedule() },
                        onBuyTicket = { route ->
                            viewModel.buyTicket(phoneInput, route)
                            selectedTab = 1
                        }
                    )
                } else {
                    TicketQrSection(
                        ticketId = state.currentTicketId,
                        ticketJson = state.ticketStatusJson,
                        qrSignature = state.qrSignature,
                        onVerifyClick = { id -> viewModel.verifyTicket(id) }
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E1E1E)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
            ) {
                Column(modifier = Modifier.padding(6.dp)) {
                    Text(
                        text = "Лог ответа кластера (REST API / JSON):",
                        color = Color(0xFF80CBC4),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.lastRawResponse,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun ClusterStatusCard(
    containerId: String,
    isLoading: Boolean,
    onPingClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Активная реплика Docker Swarm:",
                    fontSize = 12.sp
                )
                Text(
                    text = "Container ID: $containerId",
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp
                )
            }
            Button(onClick = onPingClick, enabled = !isLoading) {
                Text("Проверить узел")
            }
        }
    }
}

@Composable
fun ScheduleAndBuySection(
    phone: String,
    onPhoneChange: (String) -> Unit,
    routes: List<RouteInfo>,
    onRefreshRoutes: () -> Unit,
    onBuyTicket: (RouteInfo) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("Телефон пассажира") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Доступные маршруты:", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onRefreshRoutes) {
                Text("Обновить из Redis")
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(routes) { route ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${route.transportType} № ${route.routeNumber}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "Интервал: ${route.interval} | Тариф: ${route.price} ₽",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { onBuyTicket(route) }) {
                            Text("Купить")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TicketQrSection(
    ticketId: String?,
    ticketJson: String?,
    qrSignature: String?,
    onVerifyClick: (String) -> Unit
) {
    if (ticketId == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Билет еще не куплен. Выберите маршрут на первой вкладке.")
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "Билет № $ticketId",
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp
        )

        if (qrSignature != null) {
            RealQrCode(content = qrSignature, sizeDp = 450)
            Text(
                text = "Подпись SHA-256: ${qrSignature.take(28)}...",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }

        Button(onClick = { onVerifyClick(ticketId) }) {
            Text("Проверить статус билета (Redis / БД)")
        }

        if (ticketJson != null) {
            Text(
                text = "Данные верификации: $ticketJson",
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(8.dp))
                    .padding(4.dp)
            )
        }
    }
}

@Composable
fun RealQrCode(content: String, sizeDp: Int = 250) {
    val bitmap = remember(content) {
        generateQrBitmap(content, 512)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR-код билета",
            modifier = Modifier.size(sizeDp.dp)
        )
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap? {
    if (content.isEmpty()) return null
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}