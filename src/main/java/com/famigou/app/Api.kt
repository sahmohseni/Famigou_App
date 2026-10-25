package com.famigou.app

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

data class ConnectionCode(val id: String, val code: String, val expiresAt: String, val childToken: String)
data class ClaimedConnection(val id: String, val status: String, val parentToken: String)
data class ConnectionStatus(val id: String, val status: String, val expiresAt: String)
data class ParentAuthSession(val accessToken: String, val refreshToken: String?, val deviceId: String?)
data class LocationEvent(val latitude: Double, val longitude: Double, val accuracy: Double?, val recordedAt: String, val connectionId: String? = null)
data class ChildProfile(val displayName: String, val avatarUrl: String? = null, val updatedAt: String? = null)
data class ConnectionOverview(val id: String, val status: String, val childOnline: Boolean, val childLastSeenAt: String?, val latestLocation: LocationEvent?, val profile: ChildProfile)

object KidoraApi {
    private const val APP_PACKAGE_NAME = "com.famigou.app"
    private const val BASE = "https://api.famigou.ir"
    private const val AUTH_BASE = BASE
    private const val WS_BASE = "wss://api.famigou.ir"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(15, TimeUnit.SECONDS).build()

    fun requestParentOtp(phone: String) {
        val body = JSONObject()
            .put("phone", phone.trim())
            .put("package_name", APP_PACKAGE_NAME)
            .put("role", "parent")
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$AUTH_BASE/api/v1/auth/request-otp").post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(authError(response.code))
        }
    }

    fun verifyParentOtp(phone: String, code: String, deviceId: String): ParentAuthSession {
        val body = JSONObject()
            .put("phone", phone.trim())
            .put("code", code.trim())
            .put("package_name", APP_PACKAGE_NAME)
            .put("role", "parent")
            .put("device_id", deviceId)
            .put("platform", "android")
            .put("device_name", android.os.Build.MODEL ?: "Android")
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$AUTH_BASE/api/v1/auth/verify-otp").post(body).build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(authError(response.code))
            JSONObject(response.body?.string() ?: error("پاسخ خالی است"))
        }
        return ParentAuthSession(
            accessToken = json.optString("access_token").ifBlank { json.getString("token") },
            refreshToken = json.optString("refresh_token").ifBlank { null },
            deviceId = json.optString("device_id").ifBlank { null }
        )
    }

    private fun authError(code: Int): String = when (code) {
        403 -> "احراز هویت با OTP فقط برای والدین فعال است"
        401 -> "کد تأیید نادرست یا منقضی شده است"
        404 -> "سرویس احراز هویت در دسترس نیست"
        else -> "ارتباط با سرویس احراز هویت ناموفق بود ($code)"
    }

    fun createCode(): ConnectionCode {
        val request = Request.Builder().url("$BASE/api/v1/connections/codes").post("".toRequestBody()).build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("ساخت کد ناموفق بود (${response.code})")
            JSONObject(response.body?.string() ?: error("پاسخ خالی است"))
        }
        return ConnectionCode(json.getString("connectionId"), json.getString("code"), json.getString("expiresAt"), json.getString("childToken"))
    }

    fun claim(code: String, parentAccessToken: String): ClaimedConnection {
        val body = JSONObject().put("code", code.trim().uppercase()).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$BASE/api/v1/connections/claim").header("Authorization", "Bearer $parentAccessToken").post(body).build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("کد نامعتبر یا منقضی شده است")
            JSONObject(response.body?.string() ?: error("پاسخ خالی است"))
        }
        return ClaimedConnection(json.getString("connectionId"), json.optString("status", "active"), json.getString("parentToken"))
    }

    fun getConnectionStatus(connectionId: String, accessToken: String): ConnectionStatus {
        val request = Request.Builder().url("$BASE/api/v1/connections/$connectionId").header("Authorization", "Bearer $accessToken").get().build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("دریافت وضعیت اتصال ناموفق بود (${response.code})")
            JSONObject(response.body?.string() ?: error("پاسخ خالی است"))
        }
        return ConnectionStatus(json.getString("connectionId"), json.getString("status"), json.getString("expiresAt"))
    }

    fun getConnectionOverview(connectionId: String, accessToken: String): ConnectionOverview {
        val request = Request.Builder().url("$BASE/api/v1/connections/$connectionId/overview").header("Authorization", "Bearer $accessToken").get().build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("دریافت نمای وضعیت ناموفق بود (${response.code})")
            JSONObject(response.body?.string() ?: error("پاسخ خالی است"))
        }
        val locationJson = json.optJSONObject("latestLocation")
        val location = locationJson?.let {
            LocationEvent(
                latitude = it.getDouble("latitude"),
                longitude = it.getDouble("longitude"),
                accuracy = if (it.isNull("accuracyM")) null else it.getDouble("accuracyM"),
                recordedAt = it.optString("recordedAt", Instant.now().toString()),
                connectionId = it.optString("connectionId").ifBlank { null }
            )
        }
        return ConnectionOverview(
            id = json.getString("connectionId"),
            status = json.getString("status"),
            childOnline = json.optBoolean("childOnline", false),
            childLastSeenAt = json.optString("childLastSeenAt").ifBlank { null },
            latestLocation = location,
            profile = json.optJSONObject("profile")?.let {
                ChildProfile(
                    displayName = it.optString("displayName").ifBlank { "فرزند من" },
                    avatarUrl = it.optString("avatarUrl").ifBlank { null },
                    updatedAt = it.optString("updatedAt").ifBlank { null }
                )
            } ?: ChildProfile("فرزند من")
        )
    }

    fun updateChildProfile(connectionId: String, accessToken: String, displayName: String, avatarUrl: String?): ChildProfile {
        val body = JSONObject().put("displayName", displayName).put("avatarUrl", avatarUrl ?: JSONObject.NULL)
            .toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$BASE/api/v1/connections/$connectionId/profile")
            .header("Authorization", "Bearer $accessToken").put(body).build()
        val json = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("به‌روزرسانی پروفایل ناموفق بود (${response.code})")
            JSONObject(response.body?.string() ?: error("پاسخ خالی است"))
        }
        return ChildProfile(json.optString("displayName", displayName), json.optString("avatarUrl").ifBlank { null }, json.optString("updatedAt").ifBlank { null })
    }

    fun getLocationHistory(connectionId: String, accessToken: String, limit: Int = 20): List<LocationEvent> {
        val safeLimit = limit.coerceIn(1, 50)
        val request = Request.Builder().url("$BASE/api/v1/connections/$connectionId/locations?limit=$safeLimit")
            .header("Authorization", "Bearer $accessToken").get().build()
        val array = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("دریافت تاریخچه موقعیت ناموفق بود (${response.code})")
            JSONArray(response.body?.string() ?: "[]")
        }
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LocationEvent(
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
                accuracy = if (item.isNull("accuracyM")) null else item.getDouble("accuracyM"),
                recordedAt = item.optString("recordedAt", Instant.now().toString()),
                connectionId = item.optString("connectionId").ifBlank { null }
            )
        }
    }

    fun stream(connectionId: String, accessToken: String, onLocation: (LocationEvent) -> Unit, onError: (String) -> Unit, onOpen: () -> Unit = {}, onClosed: () -> Unit = {}, onActivated: () -> Unit = {}, onPresence: (Boolean) -> Unit = {}): WebSocket {
        val request = Request.Builder().url("$WS_BASE/api/v1/connections/$connectionId/stream").header("Authorization", "Bearer $accessToken").build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onOpen() }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("type") == "connection_activated") {
                        onActivated()
                        return@runCatching
                    }
                    if (json.optString("type") == "child_online" || json.optString("type") == "child_offline") {
                        onPresence(json.optString("type") == "child_online")
                        return@runCatching
                    }
                    if (!json.has("latitude") || !json.has("longitude")) return@runCatching
                    val connectionId = if (json.has("connectionId") && !json.isNull("connectionId")) json.getString("connectionId") else null
                    onLocation(LocationEvent(json.getDouble("latitude"), json.getDouble("longitude"), if (json.isNull("accuracyM")) null else json.getDouble("accuracyM"), json.optString("recordedAt", Instant.now().toString()), connectionId))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onError(t.message ?: "ارتباط با سرور قطع شد") }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onClosed() }
        })
    }

    fun sendLocation(socket: WebSocket, latitude: Double, longitude: Double, accuracy: Float?): Boolean {
        val json = JSONObject().put("latitude", latitude).put("longitude", longitude).put("recordedAt", Instant.now().toString())
        if (accuracy != null) json.put("accuracyM", accuracy.toDouble())
        return socket.send(json.toString())
    }

    fun sendHeartbeat(socket: WebSocket): Boolean = socket.send(JSONObject().put("type", "heartbeat").toString())
}
