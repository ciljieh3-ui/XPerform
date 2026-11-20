package com.example

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.LruCache
import android.view.Display
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class XPerformBridge(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 20001
        private const val ICON_SIZE_PX = 40
        private val SOCIAL_PACKAGES = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "com.instagram.android",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically",
            "com.google.android.youtube",
            "com.facebook.katana",
            "com.facebook.orca",
            "com.facebook.lite",
            "org.telegram.messenger",
            "com.telegram.messenger",
            "com.discord",
            "com.twitter.android",
            "com.snapchat.android",
            "com.pinterest",
            "com.reddit.frontpage",
            "com.instagram.barcelona",
            "jp.naver.line.android"
        )
        private val KNOWN_GLOBAL_SETTINGS_KEYS = listOf(
            "animator_duration_scale",
            "transition_animation_scale",
            "window_animation_scale",
            "cached_apps_freezer",
            "low_power",
            "low_power_sticky",
            "app_standby_enabled",
            "adaptive_battery_management_enabled",
            "ram_expand_size",
            "game_driver_all_apps",
            "updatable_driver_all_apps",
            "force_allow_on_external",
            "wifi_scan_always_enabled",
            "ble_scan_always_enabled",
            "CPU_MAX_FREQ"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceMutex = Mutex()
    private val iconDiskCacheDir = File(context.cacheDir, "xperform_icons_v1").apply { mkdirs() }
    private val iconMemoryCache = LruCache<String, String>(260)

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var isBindingService = false

    @Volatile
    private var cachedInstalledAppsJson: String = "[]"

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(
            ComponentName(context.packageName, ShellUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("shizuku_shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.Stub.asInterface(service)
            isBindingService = false
            notifyShizukuStatusToJs()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            isBindingService = false
            notifyShizukuStatusToJs()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope.launch {
            if (isShizukuPermissionGranted()) {
                ensureShellServiceBound()
            }
            notifyShizukuStatusToJs()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        shellService = null
        isBindingService = false
        notifyShizukuStatusToJs()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                scope.launch {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        ensureShellServiceBound()
                    }
                    notifyShizukuStatusToJs()
                }
            }
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
    }

    fun cleanup() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        try {
            if (shellService != null && Shizuku.pingBinder()) {
                Shizuku.unbindUserService(userServiceArgs, userServiceConnection, true)
            }
        } catch (_: Throwable) {
        }
        shellService = null
        scope.cancel()
    }

    @JavascriptInterface
    fun checkAndRequestShizuku() {
        scope.launch {
            val binderAlive = try {
                Shizuku.pingBinder()
            } catch (_: Throwable) {
                false
            }

            if (!binderAlive) {
                notifyShizukuStatusToJs()
                return@launch
            }

            val granted = isShizukuPermissionGranted()
            if (granted) {
                ensureShellServiceBound()
                notifyShizukuStatusToJs()
            } else {
                try {
                    if (!Shizuku.shouldShowRequestPermissionRationale()) {
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    } else {
                        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                    }
                } catch (_: Throwable) {
                }
                notifyShizukuStatusToJs()
            }
        }
    }

    @JavascriptInterface
    fun getInstalledPackages(): String {
        return cachedInstalledAppsJson
    }

    @JavascriptInterface
    fun getInstalledApps() {
        scope.launch {
            try {
                val pm = context.packageManager
                val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }

                data class AppEntry(
                    val name: String,
                    val pkg: String,
                    val category: String,
                    val isSystem: Boolean,
                    val iconBase64: String
                )

                val selfPkg = context.packageName
                val entries = ArrayList<AppEntry>(packages.size)

                for (appInfo in packages) {
                    val pkgName = appInfo.packageName ?: continue
                    if (pkgName == selfPkg) continue

                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0

                    // Sertakan aplikasi peluncur sistem penting atau aplikasi pihak ketiga
                    val hasLaunchIntent = pm.getLaunchIntentForPackage(pkgName) != null
                    if (isSystem && !hasLaunchIntent) continue

                    val label = try {
                        pm.getApplicationLabel(appInfo).toString().trim().ifEmpty { pkgName }
                    } catch (_: Throwable) {
                        pkgName
                    }

                    @Suppress("DEPRECATION")
                    val isGame = appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                        (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    val isSocial = appInfo.category == ApplicationInfo.CATEGORY_SOCIAL ||
                        SOCIAL_PACKAGES.contains(pkgName)

                    val category = when {
                        isGame -> "game"
                        isSocial -> "social"
                        isSystem -> "system"
                        else -> "app"
                    }

                    val iconBase64 = getOrCreateCompressedIcon(pm, appInfo, pkgName)
                    entries.add(AppEntry(label, pkgName, category, isSystem, iconBase64))
                }

                entries.sortWith(
                    compareBy<AppEntry> { it.isSystem }
                        .thenBy { it.category != "game" }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )

                val jsonArray = JSONArray()
                for (entry in entries) {
                    jsonArray.put(
                        JSONObject().apply {
                            put("name", entry.name)
                            put("package", entry.pkg)
                            put("category", entry.category)
                            put("isSystem", entry.isSystem)
                            if (entry.iconBase64.isNotEmpty()) {
                                put("icon", entry.iconBase64)
                            }
                        }
                    )
                }

                val jsonResult = jsonArray.toString()
                cachedInstalledAppsJson = jsonResult
                evaluateJs("window.onInstalledAppsLoaded(${JSONObject.quote(jsonResult)})")
            } catch (t: Throwable) {
                val errObj = JSONObject().apply {
                    put("error", t.message ?: "Gagal membaca daftar aplikasi")
                }.toString()
                evaluateJs("window.onInstalledAppsError(${JSONObject.quote(errObj)})")
            }
        }
    }

    @JavascriptInterface
    fun getDeviceTelemetry(): String {
        return try {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val batteryPercent = if (level >= 0 && scale > 0) {
                ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
            } else {
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            }

            val rawTempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320
            val batteryTempC = (rawTempTenths / 10.0)

            val rawCurrent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                ?: batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
                ?: 0
            val currentMa = when {
                rawCurrent == Int.MIN_VALUE -> 0
                abs(rawCurrent) > 10_000 -> rawCurrent / 1000
                else -> rawCurrent
            }

            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val totalRamMb = memInfo.totalMem / (1024L * 1024L)
            val availRamMb = memInfo.availMem / (1024L * 1024L)
            val usedRamMb = (totalRamMb - availRamMb).coerceAtLeast(0L)
            val ramPercent = if (totalRamMb > 0L) {
                ((usedRamMb * 100.0) / totalRamMb).roundToInt().coerceIn(0, 100)
            } else 0

            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val refreshRateHz = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.mode?.refreshRate?.roundToInt() ?: 60

            val cpuFreqGhz = readMaxCpuFreqGhz()
            val manufacturer = Build.MANUFACTURER.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
            val modelName = if (Build.MODEL.startsWith(manufacturer, ignoreCase = true)) {
                Build.MODEL
            } else {
                "$manufacturer ${Build.MODEL}"
            }

            val shizukuAlive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
            val shizukuGranted = shizukuAlive && isShizukuPermissionGranted()

            JSONObject().apply {
                put("batteryPercent", batteryPercent)
                put("batteryTempC", String.format(Locale.US, "%.1f", batteryTempC).toDouble())
                put("chargingCurrentMa", currentMa)
                put("isCharging", isCharging)
                put("ramUsedMb", usedRamMb)
                put("ramTotalMb", totalRamMb)
                put("ramPercent", ramPercent)
                put("deviceModel", modelName)
                put("androidVersion", Build.VERSION.RELEASE ?: "")
                put("sdkInt", Build.VERSION.SDK_INT)
                put("refreshRateHz", refreshRateHz)
                put("cpuFreqGhz", cpuFreqGhz)
                put("shizukuConnected", shizukuAlive)
                put("shizukuGranted", shizukuGranted)
                put("userServiceBound", shellService != null)
            }.toString()
        } catch (t: Throwable) {
            JSONObject().apply {
                put("error", t.message ?: "Telemetry error")
            }.toString()
        }
    }

    @JavascriptInterface
    fun executeCommand(command: String) {
        scope.launch {
            val resultJson = executeSingleViaUserService(command)
            evaluateJs("window.onCommandResult(${JSONObject.quote(command)}, ${JSONObject.quote(resultJson)})")
        }
    }

    @JavascriptInterface
    fun executeShellCommand(command: String) {
        executeCommand(command)
    }

    @JavascriptInterface
    fun executeBatchCommands(commandsJson: String) {
        scope.launch {
            val resultJson = executeBatchViaUserService(commandsJson)
            evaluateJs("window.onBatchResult(${JSONObject.quote(resultJson)})")
        }
    }

    @JavascriptInterface
    fun resetAllTweaks(modifiedPackagesJson: String) {
        scope.launch {
            val commands = ArrayList<String>()

            // 1. Pemulihan pengaturan global, device_config, window manager, doze & thermal
            KNOWN_GLOBAL_SETTINGS_KEYS.forEach { key ->
                commands.add("settings delete global $key")
            }
            for (i in 1..80) {
                commands.add("settings delete global cpu_perf_mode_$i")
                commands.add("settings delete global battery_saver_level_$i")
            }

            commands.add("device_config reset untrusted_defaults all")
            commands.add("wm size reset")
            commands.add("wm density reset")
            commands.add("cmd deviceidle unforce")
            commands.add("cmd deviceidle enable all")
            commands.add("cmd thermalservice reset")
            commands.add("settings put global low_power 0")

            // 2. Pulihkan seluruh paket aplikasi yang pernah diubah
            val packages = HashSet<String>()
            val extraRevertCommands = ArrayList<String>()
            try {
                val trimmed = modifiedPackagesJson.trim()
                if (trimmed.startsWith("[")) {
                    val arr = JSONArray(trimmed)
                    for (i in 0 until arr.length()) {
                        val pkg = arr.optString(i, "").trim()
                        if (pkg.isNotEmpty()) packages.add(pkg)
                    }
                } else if (trimmed.startsWith("{")) {
                    val obj = JSONObject(trimmed)
                    val pkgArr = obj.optJSONArray("packages")
                    if (pkgArr != null) {
                        for (i in 0 until pkgArr.length()) {
                            val pkg = pkgArr.optString(i, "").trim()
                            if (pkg.isNotEmpty()) packages.add(pkg)
                        }
                    }
                    val keysArr = obj.optJSONArray("settingsKeys")
                    if (keysArr != null) {
                        for (i in 0 until keysArr.length()) {
                            val key = keysArr.optString(i, "").trim()
                            if (key.isNotEmpty()) commands.add("settings delete global $key")
                        }
                    }
                    val revertArr = obj.optJSONArray("revertCommands")
                    if (revertArr != null) {
                        for (i in 0 until revertArr.length()) {
                            val cmd = revertArr.optString(i, "").trim()
                            if (cmd.isNotEmpty()) extraRevertCommands.add(cmd)
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            packages.forEach { pkg ->
                commands.add("pm unsuspend $pkg")
                commands.add("cmd appops reset $pkg")
                commands.add("am set-standby-bucket $pkg active")
                commands.add("cmd game mode standard $pkg")
            }
            commands.addAll(extraRevertCommands)

            val batchJson = JSONArray(commands).toString()
            val resultJson = executeBatchViaUserService(batchJson)
            evaluateJs("window.onResetAllComplete(${JSONObject.quote(resultJson)})")
        }
    }

    private suspend fun executeSingleViaUserService(command: String): String {
        val service = getOrBindService()
        if (service == null) {
            return JSONObject().apply {
                put("ok", false)
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", "Shizuku belum aktif atau izin UID 2000 belum diberikan.")
            }.toString()
        }

        return try {
            service.exec(command)
        } catch (t: Throwable) {
            shellService = null
            JSONObject().apply {
                put("ok", false)
                put("exitCode", -1)
                put("stdout", "")
                put("stderr", t.message ?: "Koneksi AIDL Shizuku terputus")
            }.toString()
        }
    }

    private suspend fun executeBatchViaUserService(commandsJson: String): String {
        val service = getOrBindService()
        if (service == null) {
            return JSONObject().apply {
                put("ok", false)
                put("total", 0)
                put("succeeded", 0)
                put("failed", 1)
                put("error", "Shizuku belum aktif atau izin UID 2000 belum diberikan.")
                put("results", JSONArray())
            }.toString()
        }

        return try {
            service.execBatch(commandsJson)
        } catch (t: Throwable) {
            shellService = null
            JSONObject().apply {
                put("ok", false)
                put("total", 0)
                put("succeeded", 0)
                put("failed", 1)
                put("error", t.message ?: "Koneksi AIDL Shizuku terputus")
                put("results", JSONArray())
            }.toString()
        }
    }

    private suspend fun getOrBindService(): IShellService? {
        shellService?.let { return it }
        if (!tryPingShizuku() || !isShizukuPermissionGranted()) return null
        ensureShellServiceBound()
        // Tunggu sebentar jika binding baru saja dipicu
        for (attempt in 0 until 20) {
            shellService?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        return shellService
    }

    private suspend fun ensureShellServiceBound() {
        serviceMutex.withLock {
            if (shellService != null || isBindingService) return
            if (!tryPingShizuku() || !isShizukuPermissionGranted()) return
            try {
                isBindingService = true
                Shizuku.bindUserService(userServiceArgs, userServiceConnection)
            } catch (_: Throwable) {
                isBindingService = false
            }
        }
    }

    private fun tryPingShizuku(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    private fun isShizukuPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    private fun notifyShizukuStatusToJs() {
        val connected = tryPingShizuku()
        val granted = connected && isShizukuPermissionGranted()
        val bound = shellService != null
        val payload = JSONObject().apply {
            put("connected", connected)
            put("granted", granted)
            put("userServiceBound", bound)
            put("uid", if (granted) 2000 else -1)
        }.toString()
        evaluateJs("window.onShizukuStatusChanged(${JSONObject.quote(payload)})")
    }

    private fun getOrCreateCompressedIcon(
        pm: PackageManager,
        appInfo: ApplicationInfo,
        pkgName: String
    ): String {
        iconMemoryCache.get(pkgName)?.let { return it }

        val diskFile = File(iconDiskCacheDir, "${pkgName.replace('/', '_')}.b64")
        if (diskFile.exists() && diskFile.length() in 16..32768) {
            try {
                val cached = diskFile.readText()
                if (cached.isNotEmpty()) {
                    iconMemoryCache.put(pkgName, cached)
                    return cached
                }
            } catch (_: Throwable) {
            }
        }

        return try {
            val drawable = pm.getApplicationIcon(appInfo)
            val bitmap = drawableToScaledBitmap(drawable, ICON_SIZE_PX, ICON_SIZE_PX)
            val baos = ByteArrayOutputStream()
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            bitmap.compress(format, 76, baos)
            bitmap.recycle()

            val base64 = "data:image/webp;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            iconMemoryCache.put(pkgName, base64)
            try {
                diskFile.writeText(base64)
            } catch (_: Throwable) {
            }
            base64
        } catch (_: Throwable) {
            ""
        }
    }

    private fun drawableToScaledBitmap(drawable: Drawable, width: Int, height: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width == width && src.height == height) {
                return src.copy(Bitmap.Config.ARGB_8888, false)
            }
            return Bitmap.createScaledBitmap(src, width, height, true)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun readMaxCpuFreqGhz(): String {
        var maxKhz = 0L
        for (core in 0..7) {
            val file = File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
            if (file.exists()) {
                try {
                    val khz = file.readText().trim().toLongOrNull() ?: 0L
                    if (khz > maxKhz) maxKhz = khz
                } catch (_: Throwable) {
                }
            }
        }
        return if (maxKhz > 0L) {
            String.format(Locale.US, "%.2fGHz", maxKhz / 1_000_000.0)
        } else {
            "-- GHz"
        }
    }

    private fun evaluateJs(script: String) {
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }
}
