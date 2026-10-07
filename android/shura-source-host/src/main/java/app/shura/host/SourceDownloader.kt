package app.shura.host

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Bounded HTTPS downloader with explicit hosts and per-hop redirect validation. */
class SourceDownloader(private val connectTimeoutMs: Int = 10_000, private val readTimeoutMs: Int = 20_000) {
    data class Result(val file: File, val sha256: String, val bytes: Long)

    fun download(url: String, destination: File, allowedHosts: Set<String>, maxBytes: Long): Result {
        require(maxBytes > 0)
        val limit = minOf(maxBytes, 50_000_000L)
        var current = url
        val visited = mutableSetOf<String>()
        repeat(6) { hop ->
            validate(current, allowedHosts)
            check(visited.add(current)) { "redirect loop" }
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("User-Agent", "Shura-Android/0.1")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    check(hop < 5) { "redirect limit exceeded" }
                    val location = connection.getHeaderField("Location") ?: error("redirect without Location")
                    current = URI(current).resolve(location).toString()
                    return@repeat
                }
                check(code in 200..299) { "HTTP $code" }
                val declared = connection.contentLengthLong
                check(declared < 0 || declared <= limit) { "response exceeds size limit" }
                destination.parentFile?.mkdirs()
                val partial = File(destination.parentFile, destination.name + ".part")
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                try {
                    partial.outputStream().buffered().use { output ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= limit) { "response exceeds size limit" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    try { Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
                    catch (_: Exception) { Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING) }
                } catch (failure: Throwable) { partial.delete(); throw failure }
                return Result(destination, digest.digest().joinToString("") { "%02x".format(it) }, total)
            } finally { connection.disconnect() }
        }
        error("redirect limit exceeded")
    }

    private fun validate(value: String, allowedHosts: Set<String>) {
        val uri = URI(value)
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS required" }
        require(uri.userInfo == null) { "URL credentials forbidden" }
        val host = uri.host?.lowercase() ?: error("missing URL host")
        require(host in allowedHosts.map { it.lowercase() }.toSet()) { "host is not allowed: $host" }
        require(uri.port == -1 || uri.port == 443) { "non-standard HTTPS port forbidden" }
    }
}
