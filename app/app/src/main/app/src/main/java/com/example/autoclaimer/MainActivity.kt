package com.example.autoclaimer

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class AccountSession(
    val token: String,
    val domain: String,
    val endpointPath: String = "/api/web/v1/gift/receive"
)

object FastClaimEngine {
    private val dnsMap = ConcurrentHashMap<String, List<InetAddress>>()
    private val fastDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return dnsMap[hostname] ?: Dns.SYSTEM.lookup(hostname).also { dnsMap[hostname] = it }
        }
    }
    private val httpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(50, 10, TimeUnit.MINUTES))
        .dns(fastDns)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .writeTimeout(1, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val MEDIA_TYPE_JSON = MediaType.get("application/json; charset=utf-8")

    fun preWarmConnections(domains: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            domains.distinct().forEach { domain ->
                launch {
                    try {
                        val pingReq = Request.Builder().url("https://$domain/").head().build()
                        httpClient.newCall(pingReq).execute().close()
                    } catch (_: Exception) {}
                }
            }
        }
    }

    suspend fun fireInstant(code: String, accounts: List<AccountSession>): List<Int> =
        withContext(Dispatchers.IO) {
            val rawPayload = """{"giftCode":"$code"}""".toByteArray()
            accounts.map { account ->
                async {
                    val request = Request.Builder()
                        .url("https://${account.domain}${account.endpointPath}")
                        .addHeader("Authorization", "Bearer ${account.token}")
                        .addHeader("Accept", "application/json")
                        .addHeader("Connection", "keep-alive")
                        .post(rawPayload.toRequestBody(MEDIA_TYPE_JSON))
                        .build()
                    try { httpClient.newCall(request).execute().use { it.code } } catch (e: Exception) { 500 }
                }
            }.awaitAll()
        }
}

class FloatingClaimerService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var etGiftCode: EditText
    private lateinit var tvStatus: TextView
    private lateinit var btnAutoFire: Button
    private var isAutoFireEnabled = false
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private val registeredAccounts = listOf(
        AccountSession("YOUR_AUTH_TOKEN_1", "varanasi91.com"),
        AccountSession("YOUR_AUTH_TOKEN_2", "559874.com")
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupFloatingWindow()
        val domains = registeredAccounts.map { it.domain }
        FastClaimEngine.preWarmConnections(domains)
        setupClipboardListener()
    }

    private fun setupFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#161622")); cornerRadius = dp(10).toFloat() }
        }

        val headerLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tvTitle = TextView(this).apply { text = "⚡ ULTRA CLAIMER"; setTextColor(Color.parseColor("#00FF66")); textSize = 13f; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val btnClose = Button(this).apply { text = "✕"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF3B30")); layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)) }
        headerLayout.addView(tvTitle); headerLayout.addView(btnClose); rootLayout.addView(headerLayout)

        etGiftCode = EditText(this).apply { hint = "Paste Code..."; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.parseColor("#232336")); cornerRadius = dp(6).toFloat() }; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) } }
        rootLayout.addView(etGiftCode)

        val buttonsLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        btnAutoFire = Button(this).apply { text = "AUTO: OFF"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#3A3A4C")); layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f) }
        val btnFire = Button(this).apply { text = "FIRE 🔥"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF6600")); layoutParams = LinearLayout.LayoutParams(0, dp(42), 1.2f).apply { marginStart = dp(6) } }
        buttonsLayout.addView(btnAutoFire); buttonsLayout.addView(btnFire); rootLayout.addView(buttonsLayout)

        tvStatus = TextView(this).apply { text = "Status: Ready"; setTextColor(Color.parseColor("#00E5FF")); textSize = 11f; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) } }
        rootLayout.addView(tvStatus)

        floatingView = rootLayout
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(dp(320), WindowManager.LayoutParams.WRAP_CONTENT, layoutType, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(30); y = dp(80) }
        windowManager.addView(floatingView, params)

        btnClose.setOnClickListener { stopSelf() }
        btnAutoFire.setOnClickListener { isAutoFireEnabled = !isAutoFireEnabled; btnAutoFire.text = if (isAutoFireEnabled) "AUTO: ON 🔥" else "AUTO: OFF"; btnAutoFire.setBackgroundColor(if (isAutoFireEnabled) Color.parseColor("#00AA44") else Color.parseColor("#3A3A4C")) }
        btnFire.setOnClickListener { val code = etGiftCode.text.toString().trim(); if (code.isNotEmpty()) executeClaim(code) }
    }

    private fun setupClipboardListener() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.addPrimaryClipChangedListener {
            val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
            if (clipText.length == 32) { etGiftCode.setText(clipText); if (isAutoFireEnabled) executeClaim(clipText) }
        }
    }

    private fun executeClaim(code: String) {
        serviceScope.launch { val results = FastClaimEngine.fireInstant(code, registeredAccounts); tvStatus.text = "🔥 Done | Hit: ${results.size}" }
    }

    private fun startForegroundNotification() {
        val channelId = "ultra_claimer_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channelId, "Claimer", NotificationManager.IMPORTANCE_LOW))
        startForeground(101, NotificationCompat.Builder(this, channelId).setContentTitle("Ultra Auto Claimer").setSmallIcon(android.R.drawable.ic_menu_compass).build())
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val btn = Button(this).apply { text = "START CLAIMER"; setOnClickListener { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) else { startService(Intent(this@MainActivity, FloatingClaimerService::class.java)); finish() } } }
        setContentView(btn)
    }
}
