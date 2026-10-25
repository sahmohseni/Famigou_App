package com.famigou.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket

enum class Role { PARENT, CHILD }
enum class Page { HOME, CHILD, PARENT, SCAN, CONNECTED, MAP }
enum class ChildAccessDisclosure { LOCATION, BACKGROUND_LOCATION, XIAOMI_BACKGROUND, BATTERY }

data class KidoraUiState(
    val role: Role?,
    val connectionId: String?,
    val routeStack: List<Page>,
    val error: String? = null,
    val showParentAuth: Boolean = false,
    val dark: Boolean = false,
    val connectionStatus: String? = null,
    val latestLocation: LocationEvent? = null,
    val locationHistory: List<LocationEvent> = emptyList(),
    val childProfile: ChildProfile = ChildProfile("فرزند من"),
    val childOnline: Boolean = false,
    val realtimeError: String? = null
)

enum class SplashCheckStatus { CHECKING, SUCCESS, WARNING }

data class SplashState(
    val internet: SplashCheckStatus = SplashCheckStatus.CHECKING,
    val login: SplashCheckStatus = SplashCheckStatus.CHECKING,
    val role: SplashCheckStatus = SplashCheckStatus.CHECKING,
    val roleLabel: String = "در حال بررسی...",
    val complete: Boolean = false
)

class KidoraViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES_NAME, Application.MODE_PRIVATE)
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var disposed = false

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<KidoraUiState> = _state.asStateFlow()
    private val _splash = MutableStateFlow(SplashState())
    val splash: StateFlow<SplashState> = _splash.asStateFlow()
    private var splashStarted = false

    private fun initialState(): KidoraUiState {
        val role = preferences.getString(KEY_ROLE, null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        val connectionId = preferences.getString(KEY_CONNECTION_ID, null)
        val routes = when {
            role == Role.PARENT && preferences.getString(KEY_AUTH_ACCESS_TOKEN, null) != null &&
                preferences.getString(KEY_ACCESS_TOKEN, null) != null && connectionId != null -> listOf(Page.MAP)
            role == Role.PARENT && preferences.getString(KEY_AUTH_ACCESS_TOKEN, null) != null -> listOf(Page.SCAN)
            role == Role.CHILD -> listOf(Page.CHILD)
            else -> listOf(Page.HOME)
        }
        return KidoraUiState(role = role, connectionId = connectionId, routeStack = routes)
    }

    fun navigate(destination: Page) { update { it.copy(routeStack = it.routeStack + destination) } }
    fun back() { update { if (it.routeStack.size > 1) it.copy(routeStack = it.routeStack.dropLast(1)) else it } }
    fun toggleDark() { update { it.copy(dark = !it.dark) } }
    fun clearError() { update { it.copy(error = null) } }
    fun showError(message: String) { update { it.copy(error = message) } }

    fun selectRole(selected: Role) {
        preferences.edit().putString(KEY_ROLE, selected.name).apply()
        update { it.copy(role = selected, routeStack = it.routeStack + Page.CHILD) }
    }

    fun selectParent() {
        if (preferences.getString(KEY_AUTH_ACCESS_TOKEN, null) != null) {
            preferences.edit().putString(KEY_ROLE, Role.PARENT.name).apply()
            update { it.copy(role = Role.PARENT, routeStack = listOf(Page.SCAN)) }
        } else {
            update { it.copy(showParentAuth = true) }
        }
    }

    fun dismissParentAuth() { update { it.copy(showParentAuth = false, role = null) } }

    fun onAuthenticated(session: ParentAuthSession) {
        val deviceId = session.deviceId ?: preferences.getString(KEY_AUTH_DEVICE_ID, null)
        val editor = preferences.edit().putString(KEY_ROLE, Role.PARENT.name).putString(KEY_AUTH_ACCESS_TOKEN, session.accessToken)
        session.refreshToken?.let { editor.putString(KEY_AUTH_REFRESH_TOKEN, it) }
        deviceId?.let { editor.putString(KEY_AUTH_DEVICE_ID, it) }
        editor.commit()
        update { it.copy(showParentAuth = false, role = Role.PARENT, routeStack = listOf(Page.SCAN)) }
    }

    fun claimCode(code: String) {
        val token = preferences.getString(KEY_AUTH_ACCESS_TOKEN, null)
        if (_state.value.role == Role.PARENT && token == null) {
            showError("نشست والد معتبر نیست؛ دوباره وارد شوید")
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { KidoraApi.claim(code, token.orEmpty()) } }
                .onSuccess { result ->
                    preferences.edit().putString(KEY_CONNECTION_ID, result.id).putString(KEY_ACCESS_TOKEN, result.parentToken).apply()
                    update { it.copy(connectionId = result.id, connectionStatus = result.status, routeStack = it.routeStack + Page.MAP, error = null) }
                }
                .onFailure { showError(it.message ?: "کد نامعتبر است") }
        }
    }

    fun saveProfile(displayName: String, avatarUrl: String?) {
        val id = _state.value.connectionId ?: return
        val token = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { KidoraApi.updateChildProfile(id, token, displayName, avatarUrl) } }
                .onSuccess { profile -> update { current -> current.copy(childProfile = profile, error = null) } }
                .onFailure { showError(it.message ?: "به‌روزرسانی پروفایل ناموفق بود") }
        }
    }

    fun resetToHome() {
        getApplication<Application>().stopService(Intent(getApplication(), LocationService::class.java))
        preferences.edit().remove(KEY_CONNECTION_ID).remove(KEY_CONNECTION_CODE).remove(KEY_CONNECTION_EXPIRES_AT)
            .remove(KEY_ACCESS_TOKEN).putBoolean(LocationService.KEY_CHILD_ACTIVE, false)
            .remove(KEY_AUTH_ACCESS_TOKEN).remove(KEY_AUTH_REFRESH_TOKEN).remove(KEY_AUTH_DEVICE_ID).remove(KEY_ROLE).apply()
        socket?.close(1000, "session reset")
        socket = null
        update { KidoraUiState(role = null, connectionId = null, routeStack = listOf(Page.HOME)) }
    }

    private fun startRealtime() {
        val id = _state.value.connectionId ?: return
        val token = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return
        if (_state.value.role != Role.PARENT || socket != null) return
        socket = KidoraApi.stream(id, token,
            onLocation = { event -> update { current -> current.copy(latestLocation = event, locationHistory = (listOf(event) + current.locationHistory).distinctBy { it.recordedAt }.take(20)) } },
            onError = { message -> update { it.copy(realtimeError = message) }; scheduleReconnect() },
            onOpen = { update { it.copy(realtimeError = null) } },
            onClosed = { socket = null; scheduleReconnect() },
            onPresence = { online -> update { it.copy(childOnline = online) } }
        )
    }

    private fun scheduleReconnect() {
        if (disposed || reconnectJob?.isActive == true) return
        reconnectJob = viewModelScope.launch {
            delay(2_000)
            socket = null
            startRealtime()
        }
    }

    private fun loadConnection() {
        val id = _state.value.connectionId ?: return
        val token = preferences.getString(KEY_ACCESS_TOKEN, null) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { KidoraApi.getConnectionOverview(id, token) }.onSuccess { overview ->
                update { it.copy(connectionStatus = overview.status, childOnline = overview.childOnline, latestLocation = overview.latestLocation, childProfile = overview.profile) }
            }
            runCatching { KidoraApi.getLocationHistory(id, token, 20) }.onSuccess { history -> update { it.copy(locationHistory = history) } }
        }
    }

    private fun update(transform: (KidoraUiState) -> KidoraUiState) { _state.update(transform) }

    override fun onCleared() {
        disposed = true
        reconnectJob?.cancel()
        socket?.close(1000, "view model cleared")
        socket = null
        super.onCleared()
    }

    fun refresh() { loadConnection(); startRealtime() }

    fun runSplashChecks() {
        if (splashStarted) return
        splashStarted = true
        viewModelScope.launch {
            delay(260)
            val online = runCatching { withContext(Dispatchers.IO) { hasInternetConnection() } }.getOrDefault(false)
            _splash.update { it.copy(internet = if (online) SplashCheckStatus.SUCCESS else SplashCheckStatus.WARNING) }

            delay(260)
            val loggedIn = when (_state.value.role) {
                Role.PARENT -> preferences.getString(KEY_AUTH_ACCESS_TOKEN, null).isNullOrBlank().not()
                Role.CHILD -> preferences.getString(KEY_ACCESS_TOKEN, null).isNullOrBlank().not()
                null -> false
            }
            _splash.update { it.copy(login = if (loggedIn) SplashCheckStatus.SUCCESS else SplashCheckStatus.WARNING) }

            delay(260)
            val storedRole = _state.value.role
            _splash.update {
                it.copy(
                    role = if (storedRole != null) SplashCheckStatus.SUCCESS else SplashCheckStatus.WARNING,
                    roleLabel = when (storedRole) {
                        Role.PARENT -> "والد"
                        Role.CHILD -> "فرزند"
                        null -> "انتخاب نشده"
                    }
                )
            }

            delay(420)
            _splash.update { it.copy(complete = true) }
        }
    }

    private fun hasInternetConnection(): Boolean {
        val manager = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    companion object {
        private const val PREFERENCES_NAME = "kidora"
        private const val KEY_ROLE = "role"
        private const val KEY_CONNECTION_ID = "connection_id"
        private const val KEY_CONNECTION_CODE = "connection_code"
        private const val KEY_CONNECTION_EXPIRES_AT = "connection_expires_at"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_AUTH_ACCESS_TOKEN = "auth_access_token"
        private const val KEY_AUTH_REFRESH_TOKEN = "auth_refresh_token"
        private const val KEY_AUTH_DEVICE_ID = "auth_device_id"
    }
}
