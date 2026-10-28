@file:JvmName("OkHttpExtensionsKt")

package eu.kanade.tachiyomi.network

import okhttp3.Call
import okhttp3.Response

/**
 * Real published extensions call the suspend helper generated from this Kotlin
 * file. The generated static bridge is what the APK's DEX resolves.
 */
suspend fun Call.awaitSuccess(): Response {
    val response = execute()
    if (!response.isSuccessful) {
        response.close()
        error("HTTP ${response.code}")
    }
    return response
}
