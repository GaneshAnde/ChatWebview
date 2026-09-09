package com.chatclient.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class FileCache(context: Context) {

    private val cacheDir = File(context.cacheDir, "webcache").apply {
        mkdirs()
    }

    data class Entry(
        val contentType: String,
        val bytes: ByteArray,
    )

    @Synchronized
    fun get(url: String): Entry? {
        val key = cacheKey(url)
        val bodyFile = File(cacheDir, "$key.bin")
        val metadataFile = File(cacheDir, "$key.meta")

        if (!bodyFile.exists() || !metadataFile.exists()) {
            return null
        }

        return try {
            Entry(
                contentType = metadataFile.readText().trim(),
                bytes = bodyFile.readBytes(),
            )
        } catch (_: IOException) {
            bodyFile.delete()
            metadataFile.delete()
            null
        }
    }

    @Synchronized
    fun put(url: String, contentType: String, bytes: ByteArray) {
        val key = cacheKey(url)
        File(cacheDir, "$key.bin").writeBytes(bytes)
        File(cacheDir, "$key.meta").writeText(contentType)
    }

    @Synchronized
    fun clear() {
        cacheDir.listFiles()?.forEach(File::delete)
    }

    private fun cacheKey(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())

        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
