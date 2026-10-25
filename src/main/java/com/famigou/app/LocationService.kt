package com.famigou.app

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import android.os.SystemClock
import java.time.Instant

class LocationService : Service() {
    private var socket: WebSocket? = null
    private lateinit var fused: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activationJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var trackingStarted = false
    @Volatile private var socketOpen = false
    private var pendingLocation: Location? = null
    @Volatile private var reconnectScheduled = false
    private var reconnectAttempt = 0
    private var lastSentAt = 0L
    private var lastSentLocation: Location? = null
    private var serviceDestroyed = false
    private var activationSocket: WebSocket? = null
    private var heartbeatJob: kotlinx.coroutines.Job? = null
    private var activeConnectionId: String? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (serviceDestroyed) return
            result.lastLocation?.let { location ->
                pendingLocation = location
                sendLocationIfNeeded(location)
            }
        }
    }
    override fun onCreate() {
        super.onCreate(); fused = LocationServices.getFusedLocationProviderClient(this)
        val channel = NotificationChannel("location", "موقعیت فمیگو", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("kidora", MODE_PRIVATE)
        val connectionId = intent?.getStringExtra(EXTRA_CONNECTION_ID) ?: prefs.getString(EXTRA_CONNECTION_ID, null) ?: return START_NOT_STICKY
        val accessToken = intent?.getStringExtra(EXTRA_ACCESS_TOKEN) ?: prefs.getString(EXTRA_ACCESS_TOKEN, null) ?: return START_NOT_STICKY
        if (serviceDestroyed) return START_NOT_STICKY
        if (activeConnectionId == connectionId && trackingStarted) return START_STICKY

        prefs.edit().putString(EXTRA_CONNECTION_ID, connectionId).apply()
        prefs.edit().putString(EXTRA_ACCESS_TOKEN, accessToken).apply()
        prefs.edit().putBoolean(KEY_CHILD_ACTIVE, false).apply()
        if (!promoteToForeground(notification("در انتظار اتصال والد"))) return START_NOT_STICKY
        activeConnectionId = connectionId
        openActivationStream(connectionId, accessToken)
        activationJob?.cancel()
        activationJob = serviceScope.launch { waitForActivation(connectionId, accessToken) }
        return START_STICKY
    }
    private suspend fun waitForActivation(connectionId: String, accessToken: String) {
        while (currentCoroutineContext().isActive) {
            val status = runCatching { KidoraApi.getConnectionStatus(connectionId, accessToken) }.getOrNull()
            if (status != null) {
                if (status.status == "active") {
                    val started = runCatching { withContext(Dispatchers.Main.immediate) { startTracking(connectionId, accessToken) } }.isSuccess
                    if (started && trackingStarted) return
                }
                if (status.status == "revoked" || runCatching { Instant.parse(status.expiresAt).isBefore(Instant.now()) }.getOrDefault(false)) {
                    stopSelf()
                    return
                }
            }
            // The activation WebSocket is the primary signal. Polling is only a
            // fallback for servers/proxies that do not deliver that event.
            delay(10_000)
        }
    }
    private fun startTracking(connectionId: String, accessToken: String) {
        if (trackingStarted) return
        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission || serviceDestroyed) { stopSelf(); return }
        trackingStarted = true
        getSharedPreferences("kidora", MODE_PRIVATE).edit().putBoolean(KEY_CHILD_ACTIVE, true).apply()
        activationSocket?.cancel()
        activationSocket = null
        socketOpen = false
        if (!promoteToForeground(notification("موقعیت برای والد ارسال می‌شود"))) {
            trackingStarted = false
            stopSelf()
            return
        }
        connectLocationSocket(connectionId, accessToken)
        runCatching {
            fused.requestLocationUpdates(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000)
                .setMinUpdateIntervalMillis(7_500).setWaitForAccurateLocation(false).build(), callback, mainLooper)
            fused.lastLocation.addOnSuccessListener { location ->
                if (!serviceDestroyed && location != null) {
                    pendingLocation = location
                    sendLocationIfNeeded(location, force = true)
                }
            }
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { location ->
                if (!serviceDestroyed && location != null) {
                    pendingLocation = location
                    sendLocationIfNeeded(location, force = true)
                }
            }
        }.onFailure {
            trackingStarted = false
            getSharedPreferences("kidora", MODE_PRIVATE).edit().putBoolean(KEY_CHILD_ACTIVE, false).apply()
            stopSelf()
        }
    }

    private fun sendLocationIfNeeded(location: Location, force: Boolean = false) {
        if (!socketOpen || serviceDestroyed) return
        val now = SystemClock.elapsedRealtime()
        val previous = lastSentLocation
        if (!force && previous != null && now - lastSentAt < 10_000L && previous.distanceTo(location) < 5f) return
        val sent = socket?.let { KidoraApi.sendLocation(it, location.latitude, location.longitude, location.accuracy) } ?: false
        if (sent) {
            lastSentAt = now
            lastSentLocation = Location(location)
        } else {
            scheduleReconnect(activeConnectionId ?: return, socket)
        }
    }

    private fun connectLocationSocket(connectionId: String, accessToken: String) {
        socket?.cancel()
        socketOpen = false
        var candidate: WebSocket? = null
        candidate = KidoraApi.stream(
                connectionId,
                accessToken,
                {},
                { if (socket === candidate && !serviceDestroyed) scheduleReconnect(connectionId, candidate) },
                {
                if (socket === candidate && !serviceDestroyed) {
                    socketOpen = true
                    reconnectScheduled = false
                    reconnectAttempt = 0
                    pendingLocation?.let { location -> sendLocationIfNeeded(location, force = true) }
                }
                },
                { if (socket === candidate && !serviceDestroyed) scheduleReconnect(connectionId, candidate) },
                {},
                {}
            )
        socket = candidate
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(10_000)
                if (socketOpen && socket === candidate) {
                    runCatching { candidate?.let { KidoraApi.sendHeartbeat(it) == true } == true }
                        .onSuccess { sent -> if (!sent && socket === candidate) scheduleReconnect(connectionId, candidate) }
                        .onFailure { if (socket === candidate) scheduleReconnect(connectionId, candidate) }
                }
            }
        }
    }
    private fun openActivationStream(connectionId: String, accessToken: String) {
        activationSocket?.cancel()
        var candidate: WebSocket? = null
        candidate = KidoraApi.stream(
            connectionId,
            accessToken,
            {},
            {},
            {},
            {},
            { if (activationSocket === candidate && !serviceDestroyed) serviceScope.launch {
                runCatching { withContext(Dispatchers.Main.immediate) { startTracking(connectionId, accessToken) } }
            } },
            {}
        )
        activationSocket = candidate
    }
    private fun scheduleReconnect(connectionId: String, failedSocket: WebSocket? = null) {
        socketOpen = false
        if (serviceDestroyed || (failedSocket != null && socket !== failedSocket) || reconnectScheduled) return
        reconnectScheduled = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        reconnectJob = serviceScope.launch {
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
            delay((2_000L shl (reconnectAttempt - 1)).coerceAtMost(30_000L))
            reconnectScheduled = false
            val accessToken = getSharedPreferences("kidora", MODE_PRIVATE).getString(EXTRA_ACCESS_TOKEN, null)
            if (isActive && accessToken != null && !serviceDestroyed) {
                runCatching { withContext(Dispatchers.Main.immediate) { connectLocationSocket(connectionId, accessToken) } }
            }
        }
    }
    private fun promoteToForeground(value: Notification): Boolean {
        return runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        }.isSuccess
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "location")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("فمیگو")
        .setContentText(text)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openApp)
        .build()
    }
    override fun onDestroy() {
        serviceDestroyed = true
        trackingStarted = false
        socketOpen = false
        getSharedPreferences("kidora", MODE_PRIVATE).edit().putBoolean(KEY_CHILD_ACTIVE, false).apply()
        activationJob?.cancel()
        reconnectJob?.cancel()
        serviceScope.cancel()
        if (::fused.isInitialized) runCatching { fused.removeLocationUpdates(callback) }
        socket?.close(1000, "stopped")
        activationSocket?.close(1000, "stopped")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val NOTIFICATION_ID = 41
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val KEY_CHILD_ACTIVE = "child_active"
    }
}
