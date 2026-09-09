package com.chatclient.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var menuButton: ImageButton
    private lateinit var messagesItem: TextView

    private var pendingChatId: String? = null
    private var activeChatId: String? = null
    private var webReady = false

    private var currentScreen : String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(
                    this,
                    "Notifications disabled — simulated alerts won't show",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NotificationHelper.createChannel(this)
        requestNotificationPermission()

        webView = findViewById(R.id.webView)
        setupWebView()
        setupDrawer()
        setupBackPress()

        handleIntent(intent)

        if (savedInstanceState == null) {
            webView.loadUrl("$LOCAL_WEB_URL/index.html")
        }
    }

    private fun setupWebView() = with(webView) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true

        addJavascriptInterface(
            ChatNativeBridge(this@MainActivity) { screen, chatId ->
                currentScreen = screen
                activeChatId = chatId.takeIf { screen == "thread" }
                updateDrawer()
            },
            "AndroidBridge",
        )

        val cache = FileCache(applicationContext)

        webViewClient = CachingWebViewClient(
            cache = cache,
            baseUrl = LOCAL_WEB_URL,
            onResourceEvent = { url, fromCache ->
                val source = if (fromCache) "CACHE" else "NETWORK"
                Log.i(TAG, "$source <- $url")
            },
            onPageFinished = ::onWebPageReady,
        )

        webChromeClient = WebChromeClient()
    }

    private fun onWebPageReady() {
        webReady = true

        val chatId = pendingChatId ?: return
        pendingChatId = null
        openChat(chatId)
    }

    private fun setupDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout)
        menuButton = findViewById(R.id.menuBtn)
        messagesItem = findViewById(R.id.drawerMessagesItem)
        val simulateItem = findViewById<TextView>(R.id.drawerSimulateItem)

        messagesItem.setBackgroundColor(
            ContextCompat.getColor(this, R.color.hf_accent_dim),
        )
        messagesItem.setTextColor(
            ContextCompat.getColor(this, R.color.hf_accent),
        )
        messagesItem.setTypeface(messagesItem.typeface, Typeface.BOLD)

        menuButton.setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        messagesItem.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        simulateItem.setOnClickListener {
            simulateIncomingMessage()
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        updateDrawer()
    }

    private fun updateDrawer() {

        val onChatList = currentScreen == "list"

        menuButton.visibility = if (onChatList) View.VISIBLE else View.GONE

        drawerLayout.setDrawerLockMode(
            if (onChatList) {
                DrawerLayout.LOCK_MODE_UNLOCKED
            } else {
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            },
        )

        if (!onChatList) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID) ?: return

        if (webReady) {
            openChat(chatId)
        } else {
            pendingChatId = chatId
        }
    }

    private fun openChat(chatId: String) {
        webView.evaluateJavascript(
            "window.ChatDetail && window.ChatDetail.open(${JSONObject.quote(chatId)});",
            null,
        )
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun simulateIncomingMessage() {
        val message = DEMO_MESSAGES.random()

        if (message.chatId == activeChatId) {
            Toast.makeText(
                this,
                "${message.sender}'s chat is already open — notification suppressed",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        NotificationHelper.showMessageNotification(
            context = this,
            chatId = message.chatId,
            contactName = message.sender,
            preview = message.preview,
        )
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
                return@addCallback
            }

            webView.evaluateJavascript(
                "(function(){ return String(window.AndroidNav ? window.AndroidNav.back() : false); })()",
            ) { result ->
                val handledByWeb = result?.trim('"') == "true"

                if (!handledByWeb) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("AndroidBridge")
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    private data class DemoMessage(
        val chatId: String,
        val sender: String,
        val preview: String,
    )

    companion object {
        private const val TAG = "ChatWebView"

        const val EXTRA_CHAT_ID = "chat_id"

        private const val LOCAL_WEB_URL = "http://10.0.2.2:8000"

        private val DEMO_MESSAGES = listOf(
            DemoMessage("priya", "Priya Nair", "Sending the doc now"),
            DemoMessage(
                "kavya",
                "Kavya Reddy",
                "Left a comment on the checksum validation",
            ),
            DemoMessage("arjun", "Arjun Mehta", "Got it, running it now"),
            DemoMessage("meera", "Meera Iyer", "Lunch at 1?"),
        )
    }
}
