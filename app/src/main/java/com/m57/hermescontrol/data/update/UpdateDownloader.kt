package com.m57.hermescontrol.data.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** MIME type of an installable APK. */
const val APK_MIME_TYPE = "application/vnd.android.package-archive"

data class DownloadedApk(
    val file: File,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Bounded, hashing APK download shared by both sources. Redirect responses
 * are refused (the connected-server client does not follow them, so cookies
 * and Bearer credentials never travel to another origin). Bytes go to
 * `<dest>.part` and are renamed only after the exact size was received; any
 * failure or cancellation deletes both files.
 */
class UpdateDownloader(
    private val client: OkHttpClient,
) {
    suspend fun download(
        url: HttpUrl,
        expectedSize: Long,
        dest: File,
        onProgress: (Float) -> Unit,
    ): DownloadedApk =
        withContext(Dispatchers.IO) {
            if (expectedSize !in 1..MAX_UPDATE_APK_BYTES) throw UpdateException(UpdateFailure.INVALID_METADATA)
            val part = File(dest.path + ".part")
            part.delete()
            dest.delete()
            val call =
                client.newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", APK_MIME_TYPE)
                        .build(),
                )
            // A blocked socket read does not observe coroutine cancellation; cancel the call itself.
            val watcher =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
            var complete = false
            try {
                val (sha, size) = transfer(call, expectedSize, part, onProgress)
                ensureActive()
                if (!part.renameTo(dest)) throw UpdateException(UpdateFailure.DOWNLOAD_FAILED)
                complete = true
                DownloadedApk(dest, sha, size)
            } catch (e: IOException) {
                ensureActive()
                if (e is BoundRequestRefusedException) throw UpdateException(UpdateFailure.CONNECTION_CHANGED)
                throw UpdateException(UpdateFailure.NETWORK)
            } finally {
                watcher.cancel()
                if (!complete) {
                    part.delete()
                    dest.delete()
                }
            }
        }

    private fun CoroutineScope.transfer(
        call: Call,
        expectedSize: Long,
        part: File,
        onProgress: (Float) -> Unit,
    ): Pair<String, Long> =
        call.execute().use { response ->
            if (response.code in 300..399) throw UpdateException(UpdateFailure.REDIRECT_REFUSED)
            if (!response.isSuccessful) {
                throw UpdateException(
                    when (response.code) {
                        401, 403 -> UpdateFailure.LOGIN_REQUIRED
                        503 -> UpdateFailure.SERVER_UPDATES_DISABLED
                        else -> UpdateFailure.DOWNLOAD_FAILED
                    },
                )
            }
            val body = response.body
            val declared = body.contentLength()
            if (declared != -1L && declared != expectedSize) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            FileOutputStream(part).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > expectedSize) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        onProgress((total.toDouble() / expectedSize).toFloat().coerceIn(0f, 1f))
                    }
                }
                out.fd.sync()
            }
            if (total != expectedSize) throw UpdateException(UpdateFailure.SIZE_MISMATCH)
            digest.digest().toLowerHex() to total
        }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}

/** SHA-256 of a file at rest; used to re-verify a cached APK before every installer launch. */
fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toLowerHex()
}

internal fun ByteArray.toLowerHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
