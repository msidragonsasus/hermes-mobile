package com.m57.hermescontrol.data.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

internal const val UPDATE_METADATA_TIMEOUT_MILLIS = 30_000L
internal const val UPDATE_APK_TIMEOUT_MILLIS = 5 * 60_000L

/** Set a whole-call ceiling without extending a stricter inherited timeout/deadline. */
internal fun Call.limitUpdateTimeout(maxMillis: Long): Call =
    apply {
        val ceiling = TimeUnit.MILLISECONDS.toNanos(maxMillis)
        val inherited = timeout().timeoutNanos()
        if (inherited == 0L || inherited > ceiling) timeout().timeout(ceiling, TimeUnit.NANOSECONDS)
    }

/** Covers headers AND body consumption; a blocked execute/read must cancel the actual socket. */
internal suspend fun <T> Call.readUpdateMetadata(block: (Response) -> T): T =
    coroutineScope {
        limitUpdateTimeout(UPDATE_METADATA_TIMEOUT_MILLIS)
        val watcher =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    this@readUpdateMetadata.cancel()
                }
            }
        try {
            execute().use(block).also { ensureActive() }
        } catch (e: IOException) {
            ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }
