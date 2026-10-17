package dev.phosphor.launcher

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.StorageStatsManager
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.Vibrator
import android.os.storage.StorageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ConcurrentHashMap

/** Foreground-only device observations. Unknown scalar readings use -1 (floats use NaN).
 * Battery current is signed µA as reported by Android; Int.MIN_VALUE means unsupported.
 * Every listener, poll, location request and result publication is scoped to start()/stop(). */
class DeviceRepository(context: Context, val onChange: () -> Unit) {
    val context: Context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("phosphor", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val batteryManager = this.context.getSystemService(BatteryManager::class.java)
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val locationManager = this.context.getSystemService(LocationManager::class.java)
    private val cameras = this.context.getSystemService(CameraManager::class.java)
    private var executor: ExecutorService? = null
    @Volatile private var started = false
    @Volatile private var generation = 0
    private var systemReceiverRegistered = false
    private var appReceiverRegistered = false
    private var calendarObserverRegistered = false
    private var torchRegistered = false
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastNetworkTime = 0L
    private var lastStorageTime = 0L
    private var lastBatterySample = 0L
    private var appsJob: Future<*>? = null
    private var calendarJob: Future<*>? = null
    private var weatherJob: Future<*>? = null
    @Volatile private var weatherRequest = 0
    @Volatile private var weatherConnection: HttpURLConnection? = null
    private var locationListener: LocationListener? = null
    private var locationTimeout: Runnable? = null
    private var torchId: String? = null
    private var storageCategories: List<Pair<String, Long>> = emptyList()
    private var categoriesTime = 0L
    private data class AppIcon(val source: String, val resource: Int, val bitmap: Bitmap)
    private val appIcons = ConcurrentHashMap<ComponentName, AppIcon>()

    var apps: List<AppEntry> = emptyList(); private set
    var batteryLevel = -1; private set
    var batteryTemp = Float.NaN; private set
    var batteryVoltage = Float.NaN; private set
    var batteryCurrent = Int.MIN_VALUE; private set
    var charging = false; private set
    var memoryUsed = -1L; private set
    var memoryTotal = -1L; private set
    var storageUsed = -1L; private set
    var storageTotal = -1L; private set
    var rxSpeed = -1L; private set
    var txSpeed = -1L; private set
    private val networkSamples = ArrayList<Pair<Long, Long>>(90)
    val networkHistory: List<Pair<Long, Long>> get() = networkSamples
    private val batterySamples = ArrayList<BatterySample>()
    val batteryHistory: List<BatterySample> get() = batterySamples
    var networkName = "Unavailable"; private set
    var wifi = false; private set
    var bluetooth = false; private set
    var location = false; private set
    var saver = false; private set
    var torch = false; private set
    var sound = "unavailable"; private set
    var dnd = false; private set
    var weather: WeatherState? = null; private set
    var weatherStatus = "Choose a city or allow location"; private set
    var notifications: List<Notice> = emptyList(); private set
    var events: List<AgendaEvent> = emptyList(); private set
    var media: MediaState? = null; private set

    init {
        loadBatteryHistory()
        if (!prefs.getBoolean("weather_enabled", true)) {
            weatherStatus = "Weather disabled"
        } else runCatching {
            val cached = prefs.getString("weather_cache", null) ?: return@runCatching
            weather = parseWeather(JSONObject(cached), prefs.getString("weather_cache_city", "").orEmpty(), prefs.getLong("weather_cache_time", 0L))
            weatherStatus = "Cached weather"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!started) return
            poll()
            onChange()
            if (started) handler.postDelayed(this, 1_000L)
        }
    }
    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!started || intent == null) return
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            if (intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_DATE_CHANGED) refreshCalendar()
            poll()
            onChange()
        }
    }
    private val appReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (started) refreshApps() }
    }
    private val calendarObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) { if (started) refreshCalendar() }
    }
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (!started) return
            if (cameraId == torchId) { torch = enabled; onChange() }
        }
        override fun onTorchModeUnavailable(cameraId: String) {
            if (started && cameraId == torchId) { torch = false; onChange() }
        }
    }

    fun start() {
        if (started) return
        started = true
        generation++
        executor = Executors.newSingleThreadExecutor { task -> Thread(task, "phosphor-device").apply { isDaemon = true } }
        lastRx = -1L; lastTx = -1L; lastNetworkTime = 0L
        val systemFilter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED); addAction(Intent.ACTION_DATE_CHANGED)
        }
        runCatching {
            val sticky = register(systemReceiver, systemFilter)
            systemReceiverRegistered = true
            if (sticky?.action == Intent.ACTION_BATTERY_CHANGED) readBattery(sticky)
        }
        runCatching {
            register(appReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            })
            appReceiverRegistered = true
        }
        runCatching {
            torchId = cameras?.cameraIdList?.firstOrNull { id ->
                val info = cameras.getCameraCharacteristics(id)
                info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true && info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            cameras?.registerTorchCallback(torchCallback, handler)
            torchRegistered = cameras != null
        }
        if (allowed(Manifest.permission.READ_CALENDAR)) runCatching {
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, calendarObserver)
            calendarObserverRegistered = true
        }
        refreshApps()
        refreshCalendar()
        if (!prefs.getBoolean("weather_enabled", true) || weather == null || System.currentTimeMillis() - (weather?.updated ?: 0L) >= 30 * 60_000L) refreshWeather()
        handler.post(tick)
    }

    fun stop() {
        if (!started) return
        started = false
        generation++
        weatherRequest++
        handler.removeCallbacksAndMessages(null)
        cancelLocation()
        if (systemReceiverRegistered) runCatching { context.unregisterReceiver(systemReceiver) }
        if (appReceiverRegistered) runCatching { context.unregisterReceiver(appReceiver) }
        if (calendarObserverRegistered) runCatching { context.contentResolver.unregisterContentObserver(calendarObserver) }
        if (torchRegistered) runCatching { cameras?.unregisterTorchCallback(torchCallback) }
        systemReceiverRegistered = false; appReceiverRegistered = false; calendarObserverRegistered = false; torchRegistered = false
        appsJob?.cancel(true); calendarJob?.cancel(true); weatherJob?.cancel(true)
        weatherConnection?.disconnect(); weatherConnection = null
        executor?.shutdownNow(); executor = null
        saveBatteryHistory()
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter): Intent? =
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun publish(epoch: Int, block: () -> Unit) {
        if (!started || epoch != generation) return
        handler.post { if (started && epoch == generation) { block(); onChange() } }
    }

    fun refreshApps() {
        if (!started) return
        appsJob?.cancel(true)
        val epoch = generation
        appsJob = executor?.submit {
            val result = runCatching {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val pm = context.packageManager
                pm.queryIntentActivities(intent, 0).map { result ->
                    val component = ComponentName(result.activityInfo.packageName, result.activityInfo.name)
                    AppEntry(result.loadLabel(pm).toString(), component, appIcon(result, component, pm))
                }.distinctBy { it.component }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).also { current ->
                    val components = current.mapTo(HashSet()) { it.component }
                    appIcons.keys.retainAll(components)
                }
            }.getOrDefault(emptyList())
            if (!Thread.currentThread().isInterrupted) publish(epoch) { apps = result }
        }
    }
    private fun appIcon(result: ResolveInfo, component: ComponentName, pm: PackageManager): Bitmap {
        val source = result.activityInfo.applicationInfo.sourceDir
        val resource = result.iconResource
        appIcons[component]?.let { if (it.source == source && it.resource == resource) return it.bitmap }
        // Rasterize once off the UI thread; drawing a list row never loads APK resources.
        val drawable = result.loadIcon(pm)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(Canvas(bitmap))
        appIcons[component] = AppIcon(source, resource, bitmap)
        return bitmap
    }

    fun launch(app: AppEntry) {
        open(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    private fun readBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        batteryTemp = if (intent.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f else Float.NaN
        batteryVoltage = if (intent.hasExtra(BatteryManager.EXTRA_VOLTAGE)) intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f else Float.NaN
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun poll() {
        runCatching {
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
            if (info.totalMem > 0L) { memoryTotal = info.totalMem; memoryUsed = (info.totalMem - info.availMem).coerceAtLeast(0L) }
        }
        val elapsed = SystemClock.elapsedRealtime()
        if (lastStorageTime == 0L || elapsed - lastStorageTime >= 30_000L) { readStorage(); lastStorageTime = elapsed }
        val current = runCatching { batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
        batteryCurrent = if (current == null || current == Long.MIN_VALUE) Int.MIN_VALUE else current.coerceIn(Int.MIN_VALUE + 1L, Int.MAX_VALUE.toLong()).toInt()
        val now = System.currentTimeMillis()
        val expired = now - DAY
        batterySamples.removeAll { it.time < expired || it.time > now }
        if (batteryLevel >= 0 && (lastBatterySample == 0L || now - lastBatterySample >= 60_000L || now < lastBatterySample)) {
            batterySamples.add(BatterySample(now, batteryLevel, batteryCurrent)); lastBatterySample = now
            saveBatteryHistory()
        }
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val delta = elapsed - lastNetworkTime
        if (rx >= 0 && tx >= 0 && lastRx >= 0 && lastTx >= 0 && rx >= lastRx && tx >= lastTx && delta > 0L) {
            rxSpeed = (rx - lastRx) * 1000L / delta
            txSpeed = (tx - lastTx) * 1000L / delta
            if (networkSamples.size >= 90) networkSamples.removeAt(0)
            networkSamples.add(rxSpeed to txSpeed)
        } else { rxSpeed = -1; txSpeed = -1 }
        lastRx = rx; lastTx = tx; lastNetworkTime = elapsed
        readSwitches()
        notifications = NotificationBridge.notifications
        media = NotificationBridge.media
    }

    private fun readStorage() {
        runCatching {
            val stat = StatFs(Environment.getDataDirectory().absolutePath)
            storageTotal = stat.totalBytes
            storageUsed = (stat.totalBytes - stat.availableBytes).coerceAtLeast(0L)
        }
    }

    private fun readSwitches() {
        networkName = runCatching {
            val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            val transport = when {
                caps == null -> "Offline"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Network"
            }
            if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "$transport · no internet" else transport
        }.getOrDefault("Unavailable")
        wifi = runCatching { context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
        bluetooth = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }.getOrDefault(false)
        location = runCatching {
            if (Build.VERSION.SDK_INT >= 28) locationManager?.isLocationEnabled == true
            else locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true || locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        }.getOrDefault(false)
        saver = power?.isPowerSaveMode == true
        sound = when (runCatching { audio?.ringerMode }.getOrNull()) { AudioManager.RINGER_MODE_NORMAL -> "sound"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; AudioManager.RINGER_MODE_SILENT -> "silent"; else -> "unavailable" }
        dnd = runCatching {
            val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            filter != null && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN && filter != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)
    }

    private fun loadBatteryHistory() {
        runCatching {
            val data = JSONArray(prefs.getString("battery_samples", "[]"))
            val now = System.currentTimeMillis()
            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                val time = row.getLong(0); val level = row.getInt(1)
                if (time in (now - DAY)..now && level in 0..100) batterySamples.add(BatterySample(time, level, row.getInt(2)))
            }
            batterySamples.sortBy { it.time }
            lastBatterySample = batterySamples.lastOrNull()?.time ?: 0L
        }
    }

    private fun saveBatteryHistory() {
        val data = JSONArray()
        batterySamples.forEach { data.put(JSONArray().put(it.time).put(it.level).put(it.current)) }
        prefs.edit().putString("battery_samples", data.toString()).apply()
    }

    fun refreshCalendar() {
        if (!started) return
        if (!allowed(Manifest.permission.READ_CALENDAR)) { events = emptyList(); onChange(); return }
        calendarJob?.cancel(true)
        val epoch = generation
        calendarJob = executor?.submit {
            val result = ArrayList<AgendaEvent>()
            runCatching {
                val start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, start); ContentUris.appendId(it, start + 31 * DAY)
                }.build()
                val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
                val selection = "${CalendarContract.Calendars.VISIBLE}=1 AND (${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS}!=?)"
                context.contentResolver.query(uri, projection, selection, arrayOf(CalendarContract.Events.STATUS_CANCELED.toString()), "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
                    while (cursor.moveToNext() && result.size < 300 && !Thread.currentThread().isInterrupted) {
                        result.add(AgendaEvent(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(2), cursor.getLong(3), cursor.getInt(4) != 0))
                    }
                }
            }.onFailure { result.clear() }
            if (!Thread.currentThread().isInterrupted) publish(epoch) { events = result }
        }
    }

    fun refreshWeather(city: String? = null) {
        weatherRequest++
        val request = weatherRequest
        val epoch = generation
        weatherJob?.cancel(true)
        weatherConnection?.disconnect(); weatherConnection = null
        cancelLocation()
        if (city != null) prefs.edit().putString("weather_city", city.trim()).putBoolean("weather_enabled", true).apply()
        if (!prefs.getBoolean("weather_enabled", true)) {
            weather = null
            weatherStatus = "Weather disabled"
            if (started) onChange()
            return
        }
        if (!started) return
        val selectedCity = prefs.getString("weather_city", "").orEmpty().trim()
        if (selectedCity.isNotEmpty()) {
            weatherStatus = if (weather == null) "Looking up $selectedCity…" else "Refreshing cached weather…"
            onChange()
            fetchWeather(epoch, request, selectedCity, null)
            return
        }
        if (!allowed(Manifest.permission.ACCESS_COARSE_LOCATION) && !allowed(Manifest.permission.ACCESS_FINE_LOCATION)) {
            weatherStatus = if (weather == null) "Choose a city or allow location" else "Cached · choose a city or allow location"
            onChange(); return
        }
        val last = runCatching {
            locationManager?.getProviders(true)?.mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
                ?.filter { SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos in 0..DAY * 1_000_000L }
                ?.maxByOrNull { it.elapsedRealtimeNanos }
        }.getOrNull()
        if (last != null) {
            weatherStatus = "Updating local weather…"; onChange()
            fetchWeather(epoch, request, null, last)
            return
        }
        weatherStatus = "Waiting for coarse location…"; onChange()
        val listener = object : LocationListener {
            override fun onLocationChanged(value: Location) {
                if (!started || generation != epoch || weatherRequest != request) return
                cancelLocation()
                fetchWeather(epoch, request, null, value)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Legacy location callback")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        locationListener = listener
        val timeout = Runnable {
            if (started && generation == epoch && weatherRequest == request) {
                cancelLocation()
                weatherStatus = if (weather == null) "Location unavailable · enter a city" else "Cached · location unavailable"
                onChange()
            }
        }
        locationTimeout = timeout
        runCatching {
            val provider = if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) LocationManager.NETWORK_PROVIDER
                else if (allowed(Manifest.permission.ACCESS_FINE_LOCATION) && locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) LocationManager.GPS_PROVIDER
                else throw IllegalStateException("Location provider unavailable")
            locationManager?.requestSingleUpdate(provider, listener, Looper.getMainLooper()) ?: throw IllegalStateException("Location unavailable")
            handler.postDelayed(timeout, 20_000L)
        }.onFailure {
            cancelLocation()
            weatherStatus = if (weather == null) "Location unavailable · enter a city" else "Cached · location unavailable"
            onChange()
        }
    }

    private fun cancelLocation() {
        locationListener?.let { listener -> runCatching { locationManager?.removeUpdates(listener) } }
        locationListener = null
        locationTimeout?.let(handler::removeCallbacks)
        locationTimeout = null
    }

    private fun fetchWeather(epoch: Int, request: Int, city: String?, coordinates: Location?) {
        weatherJob = executor?.submit {
            try {
                var label: String
                val latitude: Double
                val longitude: Double
                var zone: ZoneId? = null
                if (city != null) {
                    val parts = city.split(',', limit = 2)
                    val query = URLEncoder.encode(parts[0].trim(), "UTF-8")
                    val country = parts.getOrNull(1)?.trim()?.let(::weatherCountry)
                    val filter = country?.let { "&countryCode=$it" }.orEmpty()
                    // Geocoding searches the selected language's name index, not every translation.
                    val language = if (parts[0].any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.CYRILLIC }) "ru" else Locale.getDefault().language
                    val result = getJson("https://geocoding-api.open-meteo.com/v1/search?name=$query&count=1&language=$language&format=json$filter", request)
                        .optJSONArray("results")?.optJSONObject(0) ?: throw IllegalArgumentException("City not found · try Самара, RU")
                    latitude = result.getDouble("latitude"); longitude = result.getDouble("longitude")
                    zone = ZoneId.of(result.getString("timezone"))
                    label = listOf(result.getString("name"), result.optString("country_code")).filter { it.isNotBlank() }.joinToString(", ")
                } else {
                    val value = coordinates ?: throw IllegalArgumentException("Location unavailable")
                    // Only coarse coordinates are transmitted, including when fine location was granted.
                    latitude = kotlin.math.round(value.latitude * 100.0) / 100.0
                    longitude = kotlin.math.round(value.longitude * 100.0) / 100.0
                    label = "Local · ${String.format(Locale.US, "%.2f, %.2f", latitude, longitude)}"
                }
                if (!started || epoch != generation || request != weatherRequest || Thread.currentThread().isInterrupted) return@submit
                val json = try {
                    getJson("https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m,wind_direction_10m&hourly=temperature_2m,precipitation_probability&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&timezone=auto&forecast_days=3&wind_speed_unit=kmh", request)
                } catch (error: IOException) {
                    if (!started || epoch != generation || request != weatherRequest || Thread.currentThread().isInterrupted) return@submit
                    publish(epoch) { if (request == weatherRequest) weatherStatus = "Open-Meteo unavailable · loading wttr.in…" }
                    val address = "https://wttr.in/$latitude,$longitude"
                    val data = getJson("$address?format=j1", request)
                    val localZone = zone ?: ZoneId.of(getText("$address?format=%25Z", request).trim())
                    BackupWeather.normalize(data, latitude, longitude, localZone, System.currentTimeMillis())
                }
                val time = System.currentTimeMillis()
                val state = parseWeather(json, label, time)
                publish(epoch) {
                    if (request == weatherRequest) {
                        weather = state; weatherStatus = "Updated · ${state.source}"
                        prefs.edit().putString("weather_cache", json.toString()).putString("weather_cache_city", label).putLong("weather_cache_time", time).apply()
                    }
                }
            } catch (error: Exception) {
                if (!Thread.currentThread().isInterrupted) publish(epoch) {
                    if (request == weatherRequest) {
                        val reason = if (error is IllegalArgumentException) error.message ?: "Weather unavailable" else "Weather unavailable · check connection"
                        weatherStatus = if (weather != null) "Cached · $reason" else reason
                    }
                }
            }
        }
    }

    private fun weatherCountry(hint: String): String {
        if (hint.equals("РФ", true) || hint.equals("Российская Федерация", true)) return "RU"
        val russian = Locale.forLanguageTag("ru")
        return Locale.getISOCountries().firstOrNull { code ->
            code.equals(hint, true) || Locale("", code).let {
                it.getDisplayCountry(Locale.ENGLISH).equals(hint, true) || it.getDisplayCountry(russian).equals(hint, true)
            }
        } ?: throw IllegalArgumentException("Unknown country · use an ISO code, e.g. Самара, RU")
    }

    private fun getJson(address: String, request: Int): JSONObject = JSONObject(getText(address, request))

    private fun getText(address: String, request: Int): String {
        if (!started || request != weatherRequest || Thread.currentThread().isInterrupted) throw IOException("Cancelled")
        val connection = URL(address).openConnection() as HttpURLConnection
        weatherConnection = connection
        try {
            connection.connectTimeout = 12_000; connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "PhosphorDemake (+https://github.com/Arkuda/phosphor_demake)")
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
            if (weatherConnection === connection) weatherConnection = null
        }
    }

    private fun parseWeather(json: JSONObject, city: String, updated: Long): WeatherState {
        val current = json.getJSONObject("current")
        val hourly = json.getJSONObject("hourly")
        val daily = json.getJSONObject("daily")
        val times = hourly.getJSONArray("time")
        val currentTime = LocalDateTime.parse(current.getString("time")).withMinute(0).withSecond(0).withNano(0)
        val currentHour = currentTime.toString().take(13)
        val untilHour = currentTime.plusHours(24).toString().take(13)
        val indexes = (0 until times.length()).filter { times.getString(it).let { stamp -> stamp >= currentHour && stamp < untilHour } }
        val dailyTimes = daily.getJSONArray("time")
        val offset = ZoneOffset.ofTotalSeconds(json.getInt("utc_offset_seconds"))
        fun solar(key: String): String = runCatching {
            LocalDateTime.parse(daily.getJSONArray(key).optString(0, "")).atOffset(offset).toString()
        }.getOrDefault("")
        return WeatherState(
            city, current.getDouble("temperature_2m"), current.getDouble("apparent_temperature"), current.getInt("relative_humidity_2m"),
            current.getInt("weather_code"), current.getDouble("wind_speed_10m"), current.getDouble("wind_direction_10m"), current.getInt("is_day") == 1,
            indexes.map { times.getString(it) }, indexes.map { hourly.getJSONArray("temperature_2m").getDouble(it) }, indexes.map { hourly.getJSONArray("precipitation_probability").optInt(it, -1) },
            (0 until dailyTimes.length()).map { dailyTimes.getString(it) },
            (0 until dailyTimes.length()).map { daily.getJSONArray("temperature_2m_max").getDouble(it) },
            (0 until dailyTimes.length()).map { daily.getJSONArray("temperature_2m_min").getDouble(it) },
            (0 until dailyTimes.length()).map { daily.getJSONArray("weather_code").getInt(it) }, solar("sunrise"), solar("sunset"), updated,
            json.optString("phosphor_source", "Open-Meteo")
        )
    }

    fun dismissNotice(key: String) { NotificationBridge.dismiss(key) }
    fun mediaAction(action: String) { NotificationBridge.control(action) }

    /** OS-protected switches deliberately open their official panel instead of pretending to toggle. */
    fun quickAction(name: String) {
        when (name.trim().lowercase(Locale.ROOT)) {
            "torch", "flashlight" -> toggleTorch()
            "wifi", "wi-fi" -> open(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
            "bluetooth" -> open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            "sound", "ringer" -> toggleSound()
            "do not disturb", "dnd" -> toggleDnd()
            "location", "gps" -> open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "battery saver", "saver", "battery" -> open(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "settings" -> open(Intent(Settings.ACTION_SETTINGS))
            "internet", "network" -> open(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS))
            "alarm", "alarms", "clock" -> open(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            "calendar", "agenda" -> open(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build(), System.currentTimeMillis())), Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
            "camera" -> open(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            "calc", "calculator" -> open(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALCULATOR))
            "notifications", "notification access" -> open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "display", "brightness" -> open(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            "volume" -> open(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS))
            "airplane mode", "airplane" -> open(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            "hotspot", "tethering" -> open(Intent("android.settings.TETHER_SETTINGS"), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "search", "browser", "web" -> open(Intent(Intent.ACTION_WEB_SEARCH), Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/")))
            else -> toast("Action unavailable: $name")
        }
    }

    private fun toggleTorch() {
        if (!allowed(Manifest.permission.CAMERA)) {
            toast("Allow Camera permission to use the torch")
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            return
        }
        val id = torchId ?: run { toast("Torch unavailable on this device"); return }
        runCatching { cameras?.setTorchMode(id, !torch) }.onFailure { toast("Torch unavailable while the camera is in use") }
    }

    private fun toggleSound() {
        val manager = audio ?: return
        val hasVibrator = context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
        val next = when (manager.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> if (hasVibrator) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_SILENT
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if ((next == AudioManager.RINGER_MODE_SILENT || manager.ringerMode == AudioManager.RINGER_MODE_SILENT) && notificationManager?.isNotificationPolicyAccessGranted != true) {
            open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return
        }
        runCatching { manager.ringerMode = next; readSwitches(); if (started) onChange() }
            .onFailure { open(Intent(Settings.ACTION_SOUND_SETTINGS)) }
    }

    private fun toggleDnd() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.isNotificationPolicyAccessGranted) { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); return }
        runCatching {
            manager.setInterruptionFilter(if (dnd) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            readSwitches(); if (started) onChange()
        }.onFailure { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }

    private fun open(vararg choices: Intent) {
        for (intent in choices) {
            try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
            catch (_: android.content.ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        toast("No app is available for this action")
    }

    private fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }

    /** Exact accessible byte counts, never estimated package or media sizes.
     * Android's scoped-storage restrictions leave the remainder explicitly unclassified. */
    fun queryStorageCategories(): List<Pair<String, Long>> {
        val now = SystemClock.elapsedRealtime()
        if (storageCategories.isNotEmpty() && now - categoriesTime < 60_000L) return storageCategories
        readStorage()
        if (storageUsed < 0L) return emptyList()
        val result = ArrayList<Pair<String, Long>>()
        var remaining = storageUsed
        fun add(label: String, bytes: Long) {
            val count = bytes.coerceIn(0L, remaining)
            if (count > 0L) { result.add(label to count); remaining -= count }
        }
        runCatching {
            val manager = context.getSystemService(StorageStatsManager::class.java)
            val stats = manager?.queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
            if (stats != null) add("Phosphor · app & data", stats.appBytes + stats.dataBytes)
        }
        // Emulated primary storage shares /data's backing volume; removable media does not.
        if (Environment.isExternalStorageEmulated()) {
            fun mediaBytes(uri: Uri, selection: String? = null): Long {
                var total = 0L
                runCatching {
                    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), selection, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            if (!cursor.isNull(0)) total += cursor.getLong(0).coerceAtLeast(0L)
                        }
                    }
                }
                return total
            }
            val volume = if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL_PRIMARY else "external"
            add("Visible photos", mediaBytes(MediaStore.Images.Media.getContentUri(volume)))
            add("Visible videos", mediaBytes(MediaStore.Video.Media.getContentUri(volume)))
            add("Visible audio", mediaBytes(MediaStore.Audio.Media.getContentUri(volume)))
            if (Build.VERSION.SDK_INT >= 29) {
                // Downloaded images, video and audio already belong to the categories above.
                val mime = MediaStore.MediaColumns.MIME_TYPE
                add("Visible downloads", mediaBytes(MediaStore.Downloads.getContentUri(volume),
                    "($mime IS NULL OR ($mime NOT LIKE 'image/%' AND $mime NOT LIKE 'video/%' AND $mime NOT LIKE 'audio/%'))"))
            }
        }
        // Includes other apps, inaccessible media, filesystem metadata and reserved blocks.
        if (remaining > 0L) result.add("Other / inaccessible" to remaining)
        storageCategories = result
        categoriesTime = now
        return storageCategories
    }

    companion object { private const val DAY = 86_400_000L }
}
