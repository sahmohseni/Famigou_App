package com.famigou.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.famigou.app.ui.theme.KidoraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.util.UUID
import java.net.URL

private val AppBackground = Color(0xFFFFFFFF)
private val Ink = Color(0xFF20384D)
private val Muted = Color(0xFF71879A)
private val Blue = Color(0xFF1976D2)
private val Mint = Color(0xFF8FCBFF)
private val Border = Color(0xFFDCEEFF)
private val SoftBlue = Color(0xFFEEF8FF)
private val SoftMint = Color(0xFFF5FAFF)
private val Plum = Color(0xFF234B70)
private val CoralDark = Color(0xFFD9534F)
private val WarmSuccess = Color(0xFFA8564C)

class MainActivity : ComponentActivity() {
    private val appViewModel: KidoraViewModel by lazy { ViewModelProvider(this)[KidoraViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().userAgentValue = packageName
        setContent { KidoraApp(appViewModel) }
    }
}

private const val PREF_CONNECTION_ID = "connection_id"
private const val PREF_CONNECTION_CODE = "connection_code"
private const val PREF_CONNECTION_EXPIRES_AT = "connection_expires_at"
private const val PREF_ACCESS_TOKEN = "access_token"
private const val PREF_AUTH_ACCESS_TOKEN = "auth_access_token"
private const val PREF_AUTH_REFRESH_TOKEN = "auth_refresh_token"
private const val PREF_AUTH_DEVICE_ID = "auth_device_id"
private const val PREF_XIAOMI_GUIDANCE_SHOWN = "xiaomi_guidance_shown"

@Composable
private fun KidoraApp(viewModel: KidoraViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val splash by viewModel.splash.collectAsStateWithLifecycle()
    val page = state.routeStack.last()

    LaunchedEffect(Unit) { viewModel.runSplashChecks() }

    if (!splash.complete) {
        KidoraTheme(state.dark) { KidoraSplashScreen(splash) }
        return
    }

    BackHandler(enabled = state.role != null && page != Page.HOME && !state.showParentAuth) { }
    LaunchedEffect(state.connectionId, state.role) { viewModel.refresh() }

    KidoraTheme(state.dark) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = AppBackground) {
                when (page) {
                    Page.HOME -> RoleSelectionPage(state.role, state.dark, viewModel::toggleDark, viewModel::selectParent, viewModel::selectRole)
                    Page.CHILD -> ChildPage(viewModel::showError)
                    Page.PARENT -> ParentPage(null, { viewModel.navigate(Page.SCAN) }, viewModel::claimCode)
                    Page.SCAN -> ScannerPage(null, viewModel::claimCode, viewModel::showError)
                    Page.CONNECTED -> ConnectedPage(state.connectionId, state.connectionStatus, viewModel::resetToHome) { viewModel.navigate(Page.MAP) }
                    Page.MAP -> ParentMapPage(state.childProfile, state.latestLocation, state.locationHistory, state.childOnline, state.realtimeError, viewModel::saveProfile, viewModel::resetToHome)
                }
            }
            if (state.showParentAuth) {
                ParentAuthSheet(viewModel::dismissParentAuth, viewModel::onAuthenticated)
            }
            state.error?.let { message ->
                AlertDialog(onDismissRequest = viewModel::clearError, confirmButton = { TextButton(viewModel::clearError) { Text("باشه") } }, title = { Text("خطا") }, text = { Text(message) })
            }
        }
    }
}

@Composable
private fun KidoraSplashScreen(state: SplashState) {
    val checkRows = listOf(
        Triple("اتصال به اینترنت", state.internet, if (state.internet == SplashCheckStatus.SUCCESS) "متصل است" else "بررسی شد"),
        Triple("وضعیت ورود", state.login, if (state.login == SplashCheckStatus.SUCCESS) "وارد شده‌اید" else "مهمان"),
        Triple("نوع کاربر", state.role, state.roleLabel)
    )

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFFF8FCFF)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(124.dp).clip(RoundedCornerShape(38.dp)).background(Color(0xFFEAF6FF)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.People, contentDescription = null, tint = Blue, modifier = Modifier.size(72.dp))
            }
            Text("فمیگو", color = Blue, fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
            Text("همراه امن خانواده", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp))

            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 34.dp).background(Color.White, RoundedCornerShape(26.dp))
                    .border(1.dp, Border, RoundedCornerShape(26.dp)).padding(horizontal = 18.dp, vertical = 8.dp)
            ) {
                checkRows.forEachIndexed { index, row ->
                    SplashCheckRow(row.first, row.second, row.third)
                    if (index < checkRows.lastIndex) Divider(color = Border, modifier = Modifier.padding(horizontal = 4.dp))
                }
                Divider(color = Border, modifier = Modifier.padding(horizontal = 4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Mint, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Text("در حال آماده‌سازی برنامه...", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun SplashCheckRow(title: String, status: SplashCheckStatus, value: String) {
    val checking = status == SplashCheckStatus.CHECKING
    val positive = status == SplashCheckStatus.SUCCESS
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(if (positive) Color(0xFFE2F8EF) else SoftBlue),
            contentAlignment = Alignment.Center
        ) {
            if (checking) {
                CircularProgressIndicator(color = Blue, strokeWidth = 2.dp, modifier = Modifier.size(17.dp))
            } else {
                Icon(
                    if (positive) Icons.Default.Check else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (positive) Color(0xFF20A66A) else Blue,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(value, color = if (positive) Color(0xFF20A66A) else Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@Composable
private fun KidoraAppLegacy() {
    val preferences = LocalContext.current.getSharedPreferences("kidora", Context.MODE_PRIVATE)
    var role by remember { mutableStateOf(preferences.getString("role", null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }) }
    var connectionId by remember { mutableStateOf(preferences.getString(PREF_CONNECTION_ID, null)) }
    var routeStack by remember {
        mutableStateOf(
            when {
                role == Role.PARENT && preferences.getString(PREF_AUTH_ACCESS_TOKEN, null) != null &&
                    preferences.getString(PREF_ACCESS_TOKEN, null) != null && connectionId != null -> listOf(Page.MAP)
                role == Role.PARENT && preferences.getString(PREF_AUTH_ACCESS_TOKEN, null) != null -> listOf(Page.SCAN)
                role == Role.CHILD -> listOf(Page.CHILD)
                else -> listOf(Page.HOME)
            }
        )
    }
    var error by remember { mutableStateOf<String?>(null) }
    var showParentAuth by remember { mutableStateOf(false) }
    var dark by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var latestLocation by remember { mutableStateOf<LocationEvent?>(null) }
    var locationHistory by remember { mutableStateOf<List<LocationEvent>>(emptyList()) }
    var childProfile by remember { mutableStateOf(ChildProfile("فرزند من")) }
    var childOnline by remember { mutableStateOf(false) }
    var realtimeError by remember { mutableStateOf<String?>(null) }
    val activity = LocalContext.current as ComponentActivity
    val page = routeStack.last()

    // A selected role is locked. Leaving it requires an explicit logout or
    // disconnect action instead of the system back button.
    BackHandler(enabled = role != null && page != Page.HOME && !showParentAuth) { }

    fun navigate(destination: Page) { routeStack = routeStack + destination }
    fun back() { if (routeStack.size > 1) routeStack = routeStack.dropLast(1) }
    fun clearActiveDeviceSession() {
        activity.stopService(Intent(activity, LocationService::class.java))
        preferences.edit().remove(PREF_CONNECTION_ID).remove(PREF_CONNECTION_CODE).remove(PREF_CONNECTION_EXPIRES_AT)
            .remove(PREF_ACCESS_TOKEN).putBoolean(LocationService.KEY_CHILD_ACTIVE, false).apply()
        connectionId = null
        connectionStatus = null
        latestLocation = null
        locationHistory = emptyList()
        childProfile = ChildProfile("فرزند من")
        childOnline = false
        realtimeError = null
    }
    fun resetToHome() {
        clearActiveDeviceSession()
        preferences.edit()
            .remove(PREF_AUTH_ACCESS_TOKEN).remove(PREF_AUTH_REFRESH_TOKEN).remove(PREF_AUTH_DEVICE_ID)
            .remove("role").apply()
        role = null
        showParentAuth = false
        routeStack = listOf(Page.HOME)
    }

    fun claimCode(code: String) {
        val parentAccessToken = preferences.getString(PREF_AUTH_ACCESS_TOKEN, null)
        if (role == Role.PARENT && parentAccessToken == null) {
            error = "نشست والد معتبر نیست؛ دوباره وارد شوید"
            return
        }
        activity.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { KidoraApi.claim(code, parentAccessToken.orEmpty()) } }
                .onSuccess {
                    connectionId = it.id
                    connectionStatus = it.status
                    preferences.edit().putString(PREF_CONNECTION_ID, it.id).putString(PREF_ACCESS_TOKEN, it.parentToken).apply()
                    // Claiming the code is the connection event: show the live map immediately.
                    navigate(Page.MAP)
                }
                .onFailure { error = it.message ?: "کد نامعتبر است" }
        }
    }

    val realtimeScope = rememberCoroutineScope()
    DisposableEffect(connectionId, role) {
        latestLocation = null
        childOnline = false
        realtimeError = null
        var disposed = false
        var socket: WebSocket? = null
        var reconnectJob: Job? = null
        var connect: (() -> Unit)? = null
        fun reconnect() {
            if (disposed || reconnectJob != null) return
            reconnectJob = realtimeScope.launch {
                delay(2_000)
                reconnectJob = null
                if (!disposed) connect?.invoke()
            }
        }
        connect = {
            if (role == Role.PARENT) connectionId?.let { id ->
                val accessToken = preferences.getString(PREF_ACCESS_TOKEN, null) ?: return@let
                socket = KidoraApi.stream(
                    id,
                    accessToken,
                    { event -> realtimeScope.launch(Dispatchers.Main.immediate) {
                        latestLocation = event
                        locationHistory = (listOf(event) + locationHistory).distinctBy { it.recordedAt }.take(20)
                    } },
                    { message -> realtimeScope.launch(Dispatchers.Main.immediate) { realtimeError = message }; reconnect() },
                    { realtimeScope.launch(Dispatchers.Main.immediate) { realtimeError = null } },
                    { reconnect() },
                    {},
                    { online -> realtimeScope.launch(Dispatchers.Main.immediate) { childOnline = online } }
                )
            }
        }
        connectionId?.let { id ->
            val accessToken = preferences.getString(PREF_ACCESS_TOKEN, null)
            if (role == Role.PARENT && accessToken != null) {
                realtimeScope.launch(Dispatchers.IO) {
                    runCatching { KidoraApi.getConnectionOverview(id, accessToken) }
                        .onSuccess { overview ->
                            realtimeScope.launch(Dispatchers.Main.immediate) {
                                if (!disposed) {
                                    connectionStatus = overview.status
                                    childOnline = overview.childOnline
                                    latestLocation = overview.latestLocation
                                    childProfile = overview.profile
                                }
                            }
                        }
                    runCatching { KidoraApi.getLocationHistory(id, accessToken, 20) }
                        .onSuccess { history ->
                            realtimeScope.launch(Dispatchers.Main.immediate) {
                                if (!disposed) locationHistory = history
                            }
                        }
                }
            }
        }
        connect?.invoke()
        onDispose {
            disposed = true
            reconnectJob?.cancel()
            socket?.close(1000, "connection closed")
        }
    }

    KidoraTheme(dark) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), color = AppBackground) {
                when (page) {
                    Page.HOME -> RoleSelectionPage(role, dark, { dark = !dark }, onParentSelected = {
                        if (preferences.getString(PREF_AUTH_ACCESS_TOKEN, null) != null) {
                            role = Role.PARENT
                            preferences.edit().putString("role", Role.PARENT.name).apply()
                            routeStack = listOf(Page.SCAN)
                        } else {
                            showParentAuth = true
                        }
                    }) { selected ->
                        role = selected
                        preferences.edit().putString("role", selected.name).apply()
                        navigate(Page.CHILD)
                    }
                    Page.CHILD -> ChildPage { error = it }
                    Page.PARENT -> ParentPage(null, { navigate(Page.SCAN) }, ::claimCode)
                    Page.SCAN -> ScannerPage(null, ::claimCode) { error = it }
                    Page.CONNECTED -> ConnectedPage(connectionId, connectionStatus, { resetToHome() }) { navigate(Page.MAP) }
                    Page.MAP -> ParentMapPage(childProfile, latestLocation, locationHistory, childOnline, realtimeError, { displayName, avatarUrl ->
                        val id = connectionId
                        val token = preferences.getString(PREF_ACCESS_TOKEN, null)
                        if (id != null && token != null) {
                            activity.lifecycleScope.launch {
                                runCatching { withContext(Dispatchers.IO) { KidoraApi.updateChildProfile(id, token, displayName, avatarUrl) } }
                                    .onSuccess { childProfile = it }
                                    .onFailure { error = it.message ?: "به‌روزرسانی پروفایل ناموفق بود" }
                            }
                        }
                    }, ::resetToHome)
                }
            }
            if (showParentAuth) {
                ParentAuthSheet(
                    onDismiss = { showParentAuth = false; role = null },
                    onAuthenticated = { session ->
                        val deviceId = session.deviceId ?: preferences.getString(PREF_AUTH_DEVICE_ID, null)
                        val editor = preferences.edit()
                            .putString("role", Role.PARENT.name)
                            .putString(PREF_AUTH_ACCESS_TOKEN, session.accessToken)
                        session.refreshToken?.let { editor.putString(PREF_AUTH_REFRESH_TOKEN, it) }
                        deviceId?.let { editor.putString(PREF_AUTH_DEVICE_ID, it) }
                        editor.commit()
                        showParentAuth = false
                        role = Role.PARENT
                        routeStack = listOf(Page.SCAN)
                    }
                )
            }
            error?.let { message ->
                AlertDialog(onDismissRequest = { error = null }, confirmButton = { TextButton({ error = null }) { Text("باشه") } }, title = { Text("خطا") }, text = { Text(message) })
            }
        }
    }
}

@Composable
private fun RoleSelectionPage(role: Role?, dark: Boolean, toggleDark: () -> Unit, onParentSelected: () -> Unit, next: (Role) -> Unit) {
    AppColumn {
        Brand("همراهک", dark, toggleDark)
        Spacer(Modifier.height(68.dp))
        Eyebrow("امنیت، با یک نگاه")
        Text("نقش خود را انتخاب کنید", color = Ink, fontSize = 29.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold)
        Text("برای شروع اتصال خانوادگی، مشخص کنید با کدام دستگاه وارد می‌شوید.", color = Muted, fontSize = 14.sp, lineHeight = 28.sp)
        Spacer(Modifier.height(24.dp))
        RoleCard(role == Role.PARENT, Icons.Default.Person, SoftBlue, "من والد هستم", "مشاهده موقعیت و مدیریت اتصال") { onParentSelected() }
        Spacer(Modifier.height(13.dp))
        RoleCard(role == Role.CHILD, Icons.Default.ChildCare, SoftMint, "من فرزند هستم", "ساخت کد اتصال برای والدین") { next(Role.CHILD) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentAuthSheet(onDismiss: () -> Unit, onAuthenticated: (ParentAuthSession) -> Unit) {
    val context = LocalContext.current
    val preferences = context.getSharedPreferences("kidora", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var step by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.White) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp)) {
            Text(if (step == 0) "ورود والدین" else "تأیید شماره موبایل", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(if (step == 0) "برای مدیریت اتصال فرزند، شماره موبایل خود را وارد کنید." else "کد ۶ رقمی ارسال‌شده به ${phone.maskedPhone()} را وارد کنید.", color = Muted, fontSize = 13.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 8.dp))
            if (step == 0) {
                OutlinedTextField(phone, { phone = it.filter(Char::isDigit).take(11) }, label = { Text("شماره موبایل") }, placeholder = { Text("09121234567") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 22.dp), shape = RoundedCornerShape(14.dp))
            } else {
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("کد تأیید") }, placeholder = { Text("123456") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 22.dp), shape = RoundedCornerShape(14.dp))
            }
            localError?.let { Text(it, color = CoralDark, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
            Button(
                onClick = {
                    localError = null
                    loading = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                if (step == 0) {
                                    require(phone.length >= 10) { "شماره موبایل معتبر نیست" }
                                    KidoraApi.requestParentOtp(phone)
                                    null
                                } else {
                                    require(code.length == 6) { "کد تأیید باید ۶ رقم باشد" }
                                    val deviceId = preferences.getString(PREF_AUTH_DEVICE_ID, null) ?: UUID.randomUUID().toString()
                                    KidoraApi.verifyParentOtp(phone, code, deviceId)
                                }
                            }
                        }.onSuccess { result ->
                            loading = false
                            if (result == null) step = 1 else onAuthenticated(result)
                        }.onFailure {
                            loading = false
                            localError = it.message ?: "عملیات ناموفق بود"
                        }
                    }
                },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(54.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) { if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp)) else Text(if (step == 0) "دریافت کد تأیید" else "ورود و ادامه به اسکن") }
            if (step == 1) TextButton(onClick = { step = 0; code = "" }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("ویرایش شماره موبایل") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun String.maskedPhone(): String = if (length >= 4) "•••• ${takeLast(4)}" else this

@Composable
private fun ParentPage(back: (() -> Unit)?, scan: () -> Unit, claim: (String) -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) scan()
    }
    LaunchedEffect(Unit) {
        if (cameraGranted) scan() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    AppColumn {
        TopBar("اتصال والد", back)
        Spacer(Modifier.height(38.dp)); Eyebrow("گام ۱ از ۲")
        Text("کد فرزند را اسکن کنید", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("دوربین را مقابل QR کد نمایش‌داده‌شده در گوشی فرزند بگیرید.", color = Muted, fontSize = 13.sp, lineHeight = 26.sp)
        SurfaceCard {
            ScannerPlaceholder(scan)
            Text("برای اسکن، دسترسی دوربین لازم است.", color = Muted, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp))
            DividerLabel("یا ورود دستی")
            ManualCodeField(claim)
        }
    }
}

@Composable
private fun ScannerPage(back: (() -> Unit)?, onFound: (String) -> Unit, showError: (String) -> Unit) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it
        if (!it) showError("برای اسکن کد QR، دسترسی دوربین لازم است")
    }
    LaunchedEffect(Unit) { if (!cameraGranted) launcher.launch(Manifest.permission.CAMERA) }
    AppColumn {
        TopBar("اتصال والد", back)
        Spacer(Modifier.height(38.dp)); Eyebrow("گام ۱ از ۲")
        Text("کد فرزند را اسکن کنید", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        Text("دوربین را مقابل QR کد نمایش‌داده‌شده در گوشی فرزند بگیرید.", color = Muted, fontSize = 13.sp, lineHeight = 26.sp)
        SurfaceCard {
            if (cameraGranted) CameraScanner(onFound) else ScannerPlaceholder {}
            Text("دوربین را روی کد نگه دارید تا اتصال شناسایی شود.", color = Muted, fontSize = 11.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp))
            DividerLabel("یا ورود دستی")
            ManualCodeField(onFound)
        }
    }
}

@Composable
private fun ChildPage(showError: (String) -> Unit) {
    val preferences = LocalContext.current.getSharedPreferences("kidora", Context.MODE_PRIVATE)
    val savedConnectionId = preferences.getString(PREF_CONNECTION_ID, null)
    val savedCode = preferences.getString(PREF_CONNECTION_CODE, null)
    val savedExpiresAt = preferences.getString(PREF_CONNECTION_EXPIRES_AT, null)
    val savedToken = preferences.getString(PREF_ACCESS_TOKEN, null)
    var code by remember {
        mutableStateOf(
            if (savedConnectionId != null && savedCode != null && savedExpiresAt != null && savedToken != null)
                ConnectionCode(savedConnectionId, savedCode, savedExpiresAt, savedToken)
            else null
        )
    }
    var loading by remember { mutableStateOf(true) }
    var denied by remember { mutableStateOf(false) }
    var childActive by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf<ChildAccessDisclosure?>(null) }
    var gpsResolutionSender by remember { mutableStateOf<IntentSender?>(null) }
    val context = LocalContext.current
    val activity = context as ComponentActivity

    fun startTracking(connectionId: String) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LocationService::class.java)
                .putExtra(LocationService.EXTRA_CONNECTION_ID, connectionId)
                .putExtra(LocationService.EXTRA_ACCESS_TOKEN, preferences.getString(PREF_ACCESS_TOKEN, null))
        )
    }

    fun createCode() {
        loading = true; denied = false
        activity.lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { KidoraApi.createCode() } }
                .onSuccess { result ->
                    code = result; loading = false
                    preferences.edit().putString(PREF_CONNECTION_ID, result.id)
                        .putString(PREF_CONNECTION_CODE, result.code)
                        .putString(PREF_CONNECTION_EXPIRES_AT, result.expiresAt)
                        .putString(PREF_ACCESS_TOKEN, result.childToken).apply()
                    startTracking(result.id)
                }
                .onFailure { loading = false; showError(it.message ?: "ساخت کد ناموفق بود") }
        }
    }

    fun regenerateCode() {
        if (loading) return
        context.stopService(Intent(context, LocationService::class.java))
        preferences.edit()
            .remove(PREF_CONNECTION_ID)
            .remove(PREF_CONNECTION_CODE)
            .remove(PREF_CONNECTION_EXPIRES_AT)
            .remove(PREF_ACCESS_TOKEN)
            .putBoolean(LocationService.KEY_CHILD_ACTIVE, false)
            .apply()
        code = null
        childActive = false
        createCode()
    }

    fun startTrackingOrCreateCode() {
        if (savedConnectionId != null) startTracking(savedConnectionId) else createCode()
    }

    fun continueAfterSetup() {
        if (isXiaomiFamilyDevice() && !preferences.getBoolean(PREF_XIAOMI_GUIDANCE_SHOWN, false)) {
            disclosure = ChildAccessDisclosure.XIAOMI_BACKGROUND
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(PowerManager::class.java)
            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                disclosure = ChildAccessDisclosure.BATTERY
                return
            }
        }
        startTrackingOrCreateCode()
    }

    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Foreground tracking remains available even if the user declines this optional upgrade.
        continueAfterSetup()
    }

    fun continueAfterGpsIsReady() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            disclosure = ChildAccessDisclosure.BACKGROUND_LOCATION
        } else {
            continueAfterSetup()
        }
    }

    var ensureGpsIsEnabled: () -> Unit = {}

    val gpsResolution = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            ensureGpsIsEnabled()
        } else {
            loading = false
            denied = true
            showError("برای ارسال موقعیت، فعال بودن مکان‌یابی دستگاه ضروری است")
        }
    }

    fun checkGpsAndContinue() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(7_500L)
            .build()
        val settingsRequest = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)
            .build()

        LocationServices.getSettingsClient(context)
            .checkLocationSettings(settingsRequest)
            .addOnSuccessListener { continueAfterGpsIsReady() }
            .addOnFailureListener { error ->
                if (error is ResolvableApiException) {
                    gpsResolutionSender = error.resolution.intentSender
                    gpsResolutionSender?.let { sender ->
                        runCatching {
                            gpsResolution.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
                        }.onFailure {
                            loading = false
                            denied = true
                            showError("امکان باز کردن تنظیمات مکان‌یابی وجود ندارد")
                        }
                    }
                } else {
                    loading = false
                    denied = true
                    showError("مکان‌یابی دستگاه خاموش است؛ لطفاً آن را روشن کنید")
                }
            }
    }
    ensureGpsIsEnabled = ::checkGpsAndContinue

    fun continueAfterLocationPermission() {
        ensureGpsIsEnabled()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            continueAfterLocationPermission()
        } else {
            loading = false; denied = true; showError("برای ساخت کد QR، دسترسی موقعیت مکانی لازم است")
        }
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            disclosure = ChildAccessDisclosure.LOCATION
        } else if (savedConnectionId != null) {
            // Reuse the existing connection after process/app restart. The service is
            // idempotent and will wait for activation or resume tracking as needed.
            ensureGpsIsEnabled()
            loading = false
            runCatching { withContext(Dispatchers.IO) { KidoraApi.getConnectionStatus(savedConnectionId, savedToken ?: "") } }
                .onFailure {
                    preferences.edit().remove(PREF_CONNECTION_ID).remove(PREF_CONNECTION_CODE)
                        .remove(PREF_CONNECTION_EXPIRES_AT).remove(PREF_ACCESS_TOKEN).apply()
                    code = null
                    createCode()
                }
        } else {
            ensureGpsIsEnabled()
        }
    }
    LaunchedEffect(code?.id) {
        while (code != null) {
            childActive = context.getSharedPreferences("kidora", Context.MODE_PRIVATE).getBoolean(LocationService.KEY_CHILD_ACTIVE, false)
            kotlinx.coroutines.delay(500)
        }
    }

    disclosure?.let { request ->
        val optional = request == ChildAccessDisclosure.BATTERY || request == ChildAccessDisclosure.XIAOMI_BACKGROUND
        ChildAccessDialog(
            request = request,
            onDismiss = {
                disclosure = null
                if (optional) {
                    preferences.edit().putBoolean(PREF_XIAOMI_GUIDANCE_SHOWN, true).apply()
                    startTrackingOrCreateCode()
                } else {
                    loading = false
                    denied = true
                }
            },
            onContinue = {
                disclosure = null
                when (request) {
                    ChildAccessDisclosure.LOCATION -> permission.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                    )
                    ChildAccessDisclosure.BACKGROUND_LOCATION -> backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    ChildAccessDisclosure.XIAOMI_BACKGROUND -> {
                        preferences.edit().putBoolean(PREF_XIAOMI_GUIDANCE_SHOWN, true).apply()
                        openXiaomiAutostartSettings(context)
                        startTrackingOrCreateCode()
                    }
                    ChildAccessDisclosure.BATTERY -> {
                        requestBatteryOptimizationExemption(context)
                        startTrackingOrCreateCode()
                    }
                }
            }
        )
    }

    AppColumn(Alignment.CenterHorizontally) {
        TopBar("اتصال فرزند", null)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("این کد را به والد نشان دهید", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("والد می‌تواند QR کد را اسکن کند یا کد زیر را وارد کند.", color = Muted, fontSize = 13.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 5.dp))
            }
            if (!childActive) {
                IconButton(
                    onClick = ::regenerateCode,
                    enabled = !loading,
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color.White, RoundedCornerShape(14.dp))
                        .border(1.dp, Border, RoundedCornerShape(14.dp))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "تولید کد جدید", tint = if (loading) Muted else Blue)
                }
            } else {
                Spacer(Modifier.size(46.dp))
            }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth().height(460.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
        } else if (denied) {
            SurfaceCard { Text("بدون دسترسی موقعیت، کد QR ساخته نمی‌شود.", color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()); Button(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue)) { Text("اعطای دسترسی موقعیت") } }
        } else {
            code?.let { connection ->
                if (childActive) {
                    SurfaceCard {
                        Icon(Icons.Default.LocationOn, null, tint = Mint, modifier = Modifier.size(52.dp).align(Alignment.CenterHorizontally))
                        Text("اتصال برقرار شد", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                        Text("موقعیت شما به‌صورت لحظه‌ای برای والد ارسال می‌شود.", color = Muted, fontSize = 13.sp, lineHeight = 24.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                } else {
                    SurfaceCard {
                        QrCode(connection.code, Modifier.align(Alignment.CenterHorizontally))
                        Text(formatCode(connection.code), color = Blue, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
                        OutlinedButton(onClick = { copyText(context, connection.code) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue), border = BorderStroke(0.dp, Color.Transparent), modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("کپی کد اتصال") }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text("کد را نزد خود نگه دارید؛ اتصال پس از وارد کردن کد توسط والد انجام می‌شود.", color = Muted, fontSize = 12.sp, lineHeight = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ChildAccessDialog(
    request: ChildAccessDisclosure,
    onDismiss: () -> Unit,
    onContinue: () -> Unit
) {
    val optional = request == ChildAccessDisclosure.BATTERY || request == ChildAccessDisclosure.XIAOMI_BACKGROUND
    val title = when (request) {
        ChildAccessDisclosure.LOCATION -> "فعال‌سازی موقعیت مکانی"
        ChildAccessDisclosure.BACKGROUND_LOCATION -> "موقعیت در پس‌زمینه"
        ChildAccessDisclosure.XIAOMI_BACKGROUND -> "تنظیمات ضروری شیائومی"
        ChildAccessDisclosure.BATTERY -> "اجازه اجرای پایدار"
    }
    val description = when (request) {
        ChildAccessDisclosure.LOCATION -> "برای ساخت کد اتصال و ارسال موقعیت فرزند به والد، فمیگو به موقعیت مکانی نیاز دارد. بعد از ادامه، مجوز رسمی اندروید نمایش داده می‌شود."
        ChildAccessDisclosure.BACKGROUND_LOCATION -> "برای حفظ ایمنی فرزند، موقعیت حتی هنگام بسته بودن برنامه برای والد ارسال می‌شود. این مجوز فقط برای همین قابلیت استفاده می‌شود."
        ChildAccessDisclosure.XIAOMI_BACKGROUND -> "شیائومی ممکن است اجرای خودکار و فعالیت پس‌زمینه فمیگو را متوقف کند. در صفحه بعد، اجرای خودکار برنامه را فعال کنید."
        ChildAccessDisclosure.BATTERY -> "برای جلوگیری از توقف ارسال موقعیت، محدودیت باتری فمیگو را کاهش دهید. این مرحله اختیاری است."
    }
    val icon = when (request) {
        ChildAccessDisclosure.LOCATION, ChildAccessDisclosure.BACKGROUND_LOCATION -> Icons.Default.LocationOn
        ChildAccessDisclosure.XIAOMI_BACKGROUND -> Icons.Default.Settings
        ChildAccessDisclosure.BATTERY -> Icons.Default.BatteryChargingFull
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f)),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                color = Color.White,
                tonalElevation = 4.dp
            ) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.align(Alignment.End).size(48.dp).clip(RoundedCornerShape(16.dp)).background(SoftBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(icon, null, tint = Blue, modifier = Modifier.size(24.dp))
                    }
                    Text(title, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    Text(description, color = Muted, fontSize = 13.sp, lineHeight = 25.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 15.dp),
                        shape = RoundedCornerShape(15.dp),
                        color = SoftMint
                    ) {
                        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, null, tint = Blue, modifier = Modifier.size(18.dp))
                            Text("فمیگو فقط برای فعال‌بودن مراقبت فرزند از آن استفاده می‌کند.", color = Ink, fontSize = 11.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onContinue, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(14.dp)) {
                            Text(when (request) {
                                ChildAccessDisclosure.LOCATION -> "ادامه"
                                ChildAccessDisclosure.BACKGROUND_LOCATION -> "اجازه می‌دهم"
                                ChildAccessDisclosure.XIAOMI_BACKGROUND, ChildAccessDisclosure.BATTERY -> "باز کردن تنظیمات"
                            })
                        }
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Muted), border = BorderStroke(0.dp, Color.Transparent), shape = RoundedCornerShape(14.dp)) {
                            Text(if (optional) "فعلاً رد می‌کنم" else "انصراف")
                        }
                    }
                }
            }
        }
    }
}

private fun requestBatteryOptimizationExemption(context: Context) {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return
    val powerManager = context.getSystemService(PowerManager::class.java)
    if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) return
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun isXiaomiFamilyDevice(): Boolean {
    val manufacturer = android.os.Build.MANUFACTURER.lowercase()
    val brand = android.os.Build.BRAND.lowercase()
    return listOf("xiaomi", "redmi", "poco", "blackshark").any { it in manufacturer || it in brand }
}

private fun openXiaomiAutostartSettings(context: Context) {
    val autostartIntent = Intent("miui.intent.action.OP_AUTO_START").apply {
        setClassName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        )
    }
    runCatching { context.startActivity(autostartIntent) }.onFailure {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    }
}

@Composable
private fun ConnectedPage(connectionId: String?, connectionStatus: String?, back: () -> Unit, openMap: () -> Unit) {
    AppColumn(Alignment.CenterHorizontally, 68.dp) {
        Surface(Modifier.size(82.dp), shape = RoundedCornerShape(28.dp), color = SoftMint) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Check, null, tint = Mint, modifier = Modifier.size(48.dp)) } }
        Text("اتصال با موفقیت انجام شد", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text("دستگاه‌ها به‌صورت امن به یکدیگر متصل شدند.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        SurfaceCard {
            DetailLine("شناسه اتصال", connectionId?.take(8) ?: "—")
            DetailLine("وضعیت اتصال", connectionStatus ?: "—", connectionStatus == "active")
        }
        Button(onClick = openMap, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue), shape = RoundedCornerShape(13.dp)) { Text("مشاهده موقعیت فرزند") }
        OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink), border = BorderStroke(0.dp, Color.Transparent)) { Text("بازگشت به شروع") }
    }
}

@Composable
private fun ParentMapPage(profile: ChildProfile, location: LocationEvent?, history: List<LocationEvent>, childOnline: Boolean, streamError: String?, saveProfile: (String, String?) -> Unit, disconnect: () -> Unit) {
    val live = childOnline && streamError == null
    val accuracy = location?.accuracy
    val gpsStrong = accuracy?.let { it <= 20.0 } == true
    var showProfileEditor by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val sheetHeight by animateDpAsState(if (expanded) 560.dp else 365.dp, label = "parentSheetHeight")
    Box(Modifier.fillMaxSize().background(Color(0xFFFFF6EE))) {
        if (location == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(30.dp)) {
                    Icon(Icons.Default.LocationOff, null, tint = Muted, modifier = Modifier.size(42.dp))
                    Text(if (!childOnline) "اتصال گوشی فرزند قطع شده است" else streamError ?: "در انتظار دریافت اولین موقعیت واقعی...", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                    Text("با برقراری اتصال، موقعیت روی نقشه نمایش داده می‌شود.", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
                }
            }
        } else {
            OsmMap(location, Modifier.fillMaxSize()) { mapView = it }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .94f), shadowElevation = 5.dp) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(Blue), contentAlignment = Alignment.Center) { Text("✦", color = Color.White, fontSize = 17.sp) }
                    Column(Modifier.padding(start = 8.dp)) { Text("فمیگو", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold); Text("موقعیت خانواده", color = Muted, fontSize = 9.sp) }
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(15.dp), color = Color.White.copy(alpha = .94f), shadowElevation = 5.dp) {
                IconButton(onClick = { }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.NotificationsNone, "اعلان‌ها", tint = Ink) }
            }
        }
        Surface(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 96.dp), shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = .94f), shadowElevation = 4.dp) {
            Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(if (live) Mint else CoralDark))
                Column(Modifier.padding(start = 7.dp)) { Text(if (live) "ردیابی زنده" else "اتصال قطع است", color = if (live) WarmSuccess else CoralDark, fontSize = 10.sp, fontWeight = FontWeight.Bold); Text("موقعیت فرزند", color = Muted, fontSize = 9.sp) }
            }
        }
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(sheetHeight).padding(horizontal = 10.dp, vertical = 10.dp).navigationBarsPadding(), shape = RoundedCornerShape(27.dp), color = Color.White, shadowElevation = 14.dp) {
            var dragDistance by remember { mutableFloatStateOf(0f) }
            Column(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                Box(
                    Modifier.width(42.dp).height(4.dp).align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0xFFE8C9CF))
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, amount -> dragDistance += amount },
                                onDragEnd = {
                                    if (dragDistance < -45f) expanded = true
                                    if (dragDistance > 45f) expanded = false
                                    dragDistance = 0f
                                }
                            )
                        }
                        .clickable { expanded = !expanded }
                )
                Row(Modifier.fillMaxWidth().padding(top = 14.dp).clickable { showProfileEditor = true }, verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(profile.avatarUrl, Modifier.size(50.dp).clip(RoundedCornerShape(17.dp)).background(SoftBlue))
                    Column(Modifier.weight(1f).padding(start = 11.dp)) {
                        Text(profile.displayName, color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("گوشی فرزند · متصل و فعال", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                    Text(if (live) "● آنلاین" else "● قطع اتصال", color = if (live) WarmSuccess else CoralDark, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(if (live) SoftMint else SoftBlue, RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 7.dp))
                }
                Row(Modifier.fillMaxWidth().padding(top = 13.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Home, null, tint = Muted, modifier = Modifier.size(16.dp))
                    Text("نزدیک خانه", color = Ink, fontSize = 11.sp, modifier = Modifier.padding(start = 5.dp))
                    Spacer(Modifier.weight(1f))
                    Text("به‌روز در همین لحظه", color = Muted, fontSize = 10.sp)
                }
                Text("وضعیت لحظه‌ای", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                    ParentStatusCard(Icons.Default.Link, "اتصال والد و فرزند", if (live) "متصل و پایدار" else "قطع", live, Modifier.weight(1f))
                    ParentStatusCard(Icons.Default.GpsFixed, "سیگنال GPS", gpsQuality(accuracy), gpsStrong, Modifier.weight(1f))
                }
                location?.let { event ->
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp).background(SoftMint, RoundedCornerShape(15.dp)).padding(horizontal = 11.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = Blue, modifier = Modifier.size(19.dp))
                        Column(Modifier.padding(start = 8.dp)) { Text("آخرین به‌روزرسانی", color = Muted, fontSize = 9.sp); Text(formatPersianDateTime(event.recordedAt), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 3.dp)) }
                    }
                }
                if (expanded) {
                    Text("تاریخچه موقعیت", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 7.dp))
                    if (history.isEmpty()) {
                        Text("هنوز موقعیت ثبت‌شده‌ای وجود ندارد.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                    } else {
                        history.take(5).forEachIndexed { index, event ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(8.dp)).background(if (index == 0) Mint else Border))
                                Column(Modifier.weight(1f).padding(start = 9.dp)) {
                                    Text(formatPersianDateTime(event.recordedAt), color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("دقت GPS: ${gpsQuality(event.accuracy)}", color = Muted, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))
                                }
                                Icon(Icons.Default.ChevronLeft, null, tint = Border, modifier = Modifier.size(17.dp))
                            }
                        }
                    }
                }
                TextButton(onClick = disconnect, modifier = Modifier.align(Alignment.CenterHorizontally), colors = ButtonDefaults.textButtonColors(contentColor = CoralDark)) { Text("قطع اتصال") }
            }
        }
        Surface(
            Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = sheetHeight + 18.dp),
            shape = RoundedCornerShape(16.dp), color = Color.White.copy(alpha = .96f), shadowElevation = 7.dp
        ) {
            IconButton(onClick = {
                location?.let { event ->
                    mapView?.controller?.setZoom(15.0)
                    mapView?.controller?.animateTo(GeoPoint(event.latitude, event.longitude))
                }
            }, enabled = location != null) {
                Icon(Icons.Default.MyLocation, "نمایش موقعیت فرزند", tint = if (location != null) Blue else Muted)
            }
        }
        if (showProfileEditor) {
            ProfileEditorDialog(profile, { showProfileEditor = false }, { name, avatar -> showProfileEditor = false; saveProfile(name, avatar) })
        }
    }
}

@Composable
private fun ProfileAvatar(url: String?, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = "تصویر پروفایل فرزند", modifier = Modifier.fillMaxSize())
        else Icon(Icons.Default.ChildCare, null, tint = Blue, modifier = Modifier.size(29.dp))
    }
}

@Composable
private fun ProfileEditorDialog(profile: ChildProfile, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf(profile.displayName) }
    var avatarUrl by remember { mutableStateOf(profile.avatarUrl.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("پروفایل فرزند", color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("نام و تصویر نمایشی فرزند را تنظیم کنید.", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("نام فرزند") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
                OutlinedTextField(avatarUrl, { avatarUrl = it }, label = { Text("لینک تصویر پروفایل") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (name.trim().isNotEmpty()) onSave(name.trim(), avatarUrl.trim().ifBlank { null }) }) { Text("ذخیره") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
private fun ParentStatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, positive: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.background(Color.White, RoundedCornerShape(17.dp)).border(1.dp, Border, RoundedCornerShape(17.dp)).padding(11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(if (positive) SoftMint else SoftBlue), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (positive) WarmSuccess else CoralDark, modifier = Modifier.size(17.dp)) }
            Text(label, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(start = 7.dp))
        }
        Text(value, color = if (positive) WarmSuccess else Ink, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 9.dp))
    }
}

private fun gpsQuality(accuracy: Double?): String = when {
    accuracy == null -> "نامشخص"
    accuracy <= 20.0 -> "قوی · ${accuracy.toInt()} متر"
    accuracy <= 100.0 -> "متوسط · ${accuracy.toInt()} متر"
    else -> "ضعیف · ${accuracy.toInt()} متر"
}

private fun formatPersianDateTime(value: String): String {
    val instant = runCatching { java.time.Instant.parse(value) }.getOrNull() ?: return value
    val calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tehran"), ULocale("fa_IR@calendar=persian"))
    calendar.timeInMillis = instant.toEpochMilli()
    val weekdays = arrayOf("یک‌شنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنج‌شنبه", "جمعه", "شنبه")
    val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    val hour = calendar.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val minute = calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
    val second = calendar.get(Calendar.SECOND).toString().padStart(2, '0')
    val date = "${calendar.get(Calendar.YEAR)} ${months[calendar.get(Calendar.MONTH)]} ${calendar.get(Calendar.DAY_OF_MONTH)}"
    return faDigits("${weekdays[calendar.get(Calendar.DAY_OF_WEEK) - 1]}، $date · $hour:$minute:$second")
}

private fun faDigits(value: String): String = value.map { char ->
    if (char in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[char - '0'] else char
}.joinToString("")

@Composable
private fun OsmMap(location: LocationEvent?, modifier: Modifier = Modifier, onReady: (MapView) -> Unit = {}) {
    val context = LocalContext.current
    val map = remember { MapView(context).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(15.0) } }
    LaunchedEffect(location) {
        location?.let {
            map.overlays.clear()
            val geoPoint = GeoPoint(it.latitude, it.longitude)
            it.accuracy?.let { accuracy ->
                val accuracyCircle = org.osmdroid.views.overlay.Polygon(map).apply {
                    points = org.osmdroid.views.overlay.Polygon.pointsAsCircle(geoPoint, maxOf(30.0, accuracy))
                    fillColor = android.graphics.Color.argb(35, 45, 139, 114)
                    strokeColor = android.graphics.Color.argb(110, 45, 139, 114)
                    strokeWidth = 2f
                }
                map.overlays.add(accuracyCircle)
            }
            val marker = Marker(map).apply {
                setPosition(geoPoint)
                title = "موقعیت فرزند"
                icon = createChildMarkerIcon(context)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            map.overlays.add(marker)
            map.controller.setCenter(geoPoint)
            map.invalidate()
        }
    }
    AndroidView({ map.also(onReady) }, modifier)
}

private fun createChildMarkerIcon(context: Context): Drawable {
    val size = 112
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, 50f, paint)
    paint.color = android.graphics.Color.rgb(45, 139, 114)
    canvas.drawCircle(center, center, 43f, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center - 10f, 11f, paint)
    canvas.drawRoundRect(center - 20f, center + 2f, center + 20f, center + 27f, 13f, 13f, paint)
    return BitmapDrawable(context.resources, bitmap)
}

@Composable
private fun CameraScanner(onFound: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val preview = remember { androidx.camera.view.PreviewView(context) }
    var handled by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val providerFuture = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context)
        val analysis = androidx.camera.core.ImageAnalysis.Builder().setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val scanner = com.google.mlkit.vision.barcode.BarcodeScanning.getClient()
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { image -> image.image?.let { media -> scanner.process(com.google.mlkit.vision.common.InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)).addOnSuccessListener { result -> result.firstOrNull()?.rawValue?.let { value -> if (!handled) { handled = true; onFound(value) } } }.addOnCompleteListener { image.close() } } ?: image.close() }
        val executor = ContextCompat.getMainExecutor(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            provider.unbindAll()
            provider.bindToLifecycle(lifecycle, androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, androidx.camera.core.Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }, analysis)
        }, executor)
        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbind(analysis)
            scanner.close()
        }
    }
    AndroidView({ preview }, Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)))
}

@Composable
private fun ScannerPlaceholder(start: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)).background(Plum).clickable(onClick = start), contentAlignment = Alignment.Center) {
        Box(Modifier.size(176.dp).border(2.dp, Color(0xFFFFB3A7), RoundedCornerShape(15.dp)))
        Box(Modifier.fillMaxWidth().padding(horizontal = 48.dp).height(2.dp).background(Blue))
        Text("محل نمایش اسکنر دوربین", color = Color(0xFFFFE4DF), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp))
    }
}

@Composable
private fun ManualCodeField(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(code, { code = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("۱۲۳۴۵۶") }, textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.Center, letterSpacing = 3.sp), shape = RoundedCornerShape(13.dp))
        Button(onClick = { onSubmit(code) }, colors = ButtonDefaults.buttonColors(containerColor = SoftBlue, contentColor = Blue), shape = RoundedCornerShape(13.dp), contentPadding = PaddingValues(horizontal = 17.dp, vertical = 13.dp)) { Text("اتصال") }
    }
}

@Composable
private fun AppColumn(horizontal: Alignment.Horizontal = Alignment.Start, topPadding: Dp = 25.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 28.dp), horizontalAlignment = horizontal, content = content)
}

@Composable private fun Brand(name: String, dark: Boolean, toggle: () -> Unit) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(34.dp), shape = RoundedCornerShape(12.dp), color = Blue) { Box(contentAlignment = Alignment.Center) { Text("⌁", color = Color.White, fontSize = 22.sp) } }; Text(name, color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 9.dp)); Spacer(Modifier.weight(1f)); IconButton(onClick = toggle) { Icon(if (dark) Icons.Default.LightMode else Icons.Default.DarkMode, "ظاهر", tint = Muted) } } }
@Composable private fun TopBar(title: String, back: (() -> Unit)?) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { back?.let { IconButton(onClick = it, modifier = Modifier.size(38.dp).background(Color.White, RoundedCornerShape(13.dp)).border(1.dp, Border, RoundedCornerShape(13.dp))) { Icon(Icons.Default.ArrowBack, "بازگشت", tint = Ink) } }; Spacer(Modifier.weight(1f)); Text(title, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Spacer(Modifier.size(38.dp)) } }
@Composable private fun Eyebrow(text: String) { Text(text, color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 7.dp)) }
@Composable private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) { Column(Modifier.fillMaxWidth().padding(top = 22.dp).background(Color.White, RoundedCornerShape(24.dp)).border(1.dp, Border, RoundedCornerShape(24.dp)).padding(20.dp), content = content) }
@Composable private fun RoleCard(selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, iconColor: Color, title: String, subtitle: String, click: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = click).background(Color.White, RoundedCornerShape(22.dp)).border(1.dp, if (selected) Blue else Border, RoundedCornerShape(22.dp)).padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(52.dp).clip(RoundedCornerShape(17.dp)).background(iconColor), contentAlignment = Alignment.Center) { Icon(icon, null, tint = if (iconColor == SoftBlue) Blue else Mint, modifier = Modifier.size(27.dp)) }; Column(Modifier.padding(start = 14.dp)) { Text(title, color = Ink, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp)) }; Spacer(Modifier.weight(1f)); Icon(Icons.Default.ChevronLeft, null, tint = Muted) } }
@Composable private fun DividerLabel(text: String) { Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) { HorizontalDivider(Modifier.weight(1f), color = Border); Text(text, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp)); HorizontalDivider(Modifier.weight(1f), color = Border) } }
@Composable private fun DetailLine(label: String, value: String, positive: Boolean = false) { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Ink, fontSize = 13.sp); Text(value, color = if (positive) WarmSuccess else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = if (positive) Modifier.background(SoftMint, RoundedCornerShape(20.dp)).padding(horizontal = 9.dp, vertical = 5.dp) else Modifier) } }
@Composable private fun StatCard(label: String, value: String, positive: Boolean, modifier: Modifier = Modifier) { Column(modifier.background(Color.White, RoundedCornerShape(14.dp)).border(1.dp, Border, RoundedCornerShape(14.dp)).padding(12.dp)) { Text(label, color = Muted, fontSize = 11.sp); Text(value, color = if (positive) Mint else Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) } }
@Composable private fun QrCode(value: String, modifier: Modifier = Modifier) { val bitmap = remember(value) { val matrix: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 600, 600); Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).also { for (x in 0 until 600) for (y in 0 until 600) it.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE) } }; androidx.compose.foundation.Image(bitmap.asImageBitmap(), "کد QR", modifier.size(205.dp)) }
private fun formatCode(value: String) = value.chunked(3).joinToString(" ")
private fun copyText(context: Context, value: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("کد اتصال", value)) }
