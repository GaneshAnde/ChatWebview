package com.chatclient.app

import android.app.Activity
import android.webkit.JavascriptInterface

class ChatNativeBridge(
    private val activity: Activity,
    private val onScreenChange: (screen: String, chatId: String) -> Unit,
) {

    @JavascriptInterface
    fun onScreenChanged(screen: String, chatId: String) {
        activity.runOnUiThread {
            onScreenChange(screen, chatId)
        }
    }
}
