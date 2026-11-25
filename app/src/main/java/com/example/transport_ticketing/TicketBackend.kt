package com.example.transport_ticketing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class RouteInfo(
    val routeNumber: String,
    val transportType: String,
    val interval: String,
    val price: Int
)

data class TicketUiState(
    val baseUrl: String = "http://192.168.56.1:8080",
    val isLoading: Boolean = false,
    val activeContainerId: String = "Не проверено",
    val routes: List<RouteInfo> = emptyList(),
    val currentTicketId: String? = null,
    val ticketStatusJson: String? = null,
    val qrSignature: String? = null,
    val lastRawResponse: String = "Ожидание запросов к кластеру Docker Swarm..."
)

class TicketApiClient {

    suspend fun getRequest(fullUrl: String): String = withContext(Dispatchers.IO) {
        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 50000
            readTimeout = 50000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun postRequest(fullUrl: String, jsonBody: JSONObject): String = withContext(Dispatchers.IO) {
        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 5000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            stream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

class TicketViewModel : ViewModel() {
    private val apiClient = TicketApiClient()

    private val _uiState = MutableStateFlow(TicketUiState())
    val uiState: StateFlow<TicketUiState> = _uiState.asStateFlow()

    init {
        checkClusterHealth()
        loadSchedule()
    }

    fun updateBaseUrl(newUrl: String) {
        _uiState.update { it.copy(baseUrl = newUrl.trimEnd('/')) }
    }

    fun checkClusterHealth() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                apiClient.getRequest("${_uiState.value.baseUrl}/api/tickets/health")
            }.onSuccess { response ->
                val json = JSONObject(response)
                val containerId = json.optString("container_id", "unknown")
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        activeContainerId = containerId,
                        lastRawResponse = "GET /api/tickets/health\n$response"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        activeContainerId = "Ошибка связи",
                        lastRawResponse = "Ошибка: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun loadSchedule() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                apiClient.getRequest("${_uiState.value.baseUrl}/api/schedule/routes")
            }.onSuccess { response ->
                val parsedRoutes = parseRoutesJson(response)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        routes = parsedRoutes,
                        lastRawResponse = "GET /api/schedule/routes\n$response"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastRawResponse = "Ошибка загрузки расписания: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun buyTicket(phone: String, route: RouteInfo) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val payload = JSONObject().apply {
                put("user_phone", phone)
                put("route_number", route.routeNumber)
                put("transport_type", route.transportType)
                put("price", route.price)
            }
            runCatching {
                apiClient.postRequest("${_uiState.value.baseUrl}/api/tickets/buy", payload)
            }.onSuccess { response ->
                val json = JSONObject(response)
                val ticketId = json.optString("ticket_id", json.optString("id", ""))
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentTicketId = ticketId.ifEmpty { null },
                        ticketStatusJson = response,
                        lastRawResponse = "POST /api/tickets/buy\n$response"
                    )
                }
                if (ticketId.isNotEmpty()) {
                    loadQrForTicket(ticketId)
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastRawResponse = "Ошибка покупки: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun verifyTicket(ticketId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            runCatching {
                apiClient.getRequest("${_uiState.value.baseUrl}/api/tickets/$ticketId")
            }.onSuccess { response ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        ticketStatusJson = response,
                        lastRawResponse = "GET /api/tickets/$ticketId\n$response"
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastRawResponse = "Ошибка проверки билета: ${error.localizedMessage}"
                    )
                }
            }
        }
    }

    fun loadQrForTicket(ticketId: String) {
        viewModelScope.launch {
            runCatching {
                apiClient.getRequest("${_uiState.value.baseUrl}/api/schedule/qr/$ticketId")
            }.onSuccess { response ->
                val json = JSONObject(response)
                val qrData = json.optString(
                    "qr_payload",
                    json.optString("qr_token", json.optString("signature", response))
                )
                _uiState.update {
                    it.copy(
                        qrSignature = qrData,
                        lastRawResponse = "GET /api/schedule/qr/$ticketId\n$response"
                    )
                }
            }
        }
    }

    private fun parseRoutesJson(raw: String): List<RouteInfo> {
        val list = mutableListOf<RouteInfo>()
        runCatching {
            val root = JSONObject(raw)
            val arr: JSONArray = root.optJSONArray("routes") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                list.add(
                    RouteInfo(
                        routeNumber = item.optString("route_number", item.optString("number", "М${i + 1}")),
                        transportType = item.optString("transport_type", item.optString("type", "Автобус")),
                        interval = item.optString("interval", "5-10 мин"),
                        price = item.optInt("price", 55)
                    )
                )
            }
        }
        if (list.isEmpty()) {
            list.add(RouteInfo("М1", "Автобус", "6 мин", 55))
            list.add(RouteInfo("Т17", "Трамвай", "8 мин", 55))
            list.add(RouteInfo("Е29", "Электробус", "10 мин", 60))
        }
        return list
    }
}