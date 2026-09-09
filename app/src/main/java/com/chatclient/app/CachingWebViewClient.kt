package com.chatclient.app

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.net.toUri

class CachingWebViewClient(
    private val cache: FileCache,
    private val baseUrl: String,
    private val onResourceEvent: (url: String, servedFromCache: Boolean) -> Unit = { _, _ -> },
    private val onPageFinished: () -> Unit = {},
) : WebViewClient() {

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        if (!isCacheable(url)) return null

        cache.get(url)?.let { entry ->
            onResourceEvent(url, true)
            Log.d(TAG, "cache hit: $url")

            return WebResourceResponse(
                entry.contentType,
                "UTF-8",
                ByteArrayInputStream(entry.bytes),
            )
        }

        return fetchAndCache(url)
    }

    private fun isCacheable(url: String): Boolean {
        if (!url.startsWith(baseUrl)) return false

        val extension = url.toUri()
            .lastPathSegment
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            ?: return false

        return extension in CACHEABLE_EXTENSIONS
    }

    private fun fetchAndCache(url: String): WebResourceResponse? {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            Log.w(TAG, "could not open connection for $url", e)
            return null
        }

        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"

            val status = connection.responseCode
            if (status !in 200..299) {
                Log.w(TAG, "HTTP $status for $url")
                return null
            }

            val contentType =
                connection.contentType?.substringBefore(';') ?: contentTypeFor(url)

            val bytes = connection.inputStream.use { input ->
                input.readBytes()
            }

            cache.put(url, contentType, bytes)
            onResourceEvent(url, false)
            Log.d(TAG, "fetched and cached: $url")

            WebResourceResponse(
                contentType,
                "UTF-8",
                ByteArrayInputStream(bytes),
            )
        } catch (e: IOException) {
            Log.w(TAG, "fetch failed for $url: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun contentTypeFor(url: String): String {
        val path = Uri.parse(url).path.orEmpty()

        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".js") -> "application/javascript"
            else -> "application/octet-stream"
        }
    }

    private companion object {
        const val TAG = "CachingWebViewClient"
        val CACHEABLE_EXTENSIONS = setOf("html", "css", "js")
    }
}
