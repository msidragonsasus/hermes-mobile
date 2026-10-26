package com.m57.hermescontrol.util

import android.util.Log
import io.mockk.every
import java.util.concurrent.CopyOnWriteArrayList

/** Captures the actual Log sink, including throwable details a diagnostic export would inherit. */
fun captureDiagnosticLogs(): MutableList<String> {
    val records = CopyOnWriteArrayList<String>()
    every { Log.d(any<String>(), any<String>()) } answers {
        records.add(secondArg())
        0
    }
    every { Log.i(any<String>(), any<String>()) } answers {
        records.add(secondArg())
        0
    }
    every { Log.w(any<String>(), any<String>()) } answers {
        records.add(secondArg())
        0
    }
    every { Log.e(any<String>(), any<String>()) } answers {
        records.add(secondArg())
        0
    }
    every { Log.d(any<String>(), any<String>(), any<Throwable>()) } answers {
        records.add(secondArg<String>() + thirdArg<Throwable>().stackTraceToString())
        0
    }
    every { Log.i(any<String>(), any<String>(), any<Throwable>()) } answers {
        records.add(secondArg<String>() + thirdArg<Throwable>().stackTraceToString())
        0
    }
    every { Log.w(any<String>(), any<String>(), any<Throwable>()) } answers {
        records.add(secondArg<String>() + thirdArg<Throwable>().stackTraceToString())
        0
    }
    every { Log.e(any<String>(), any<String>(), any<Throwable>()) } answers {
        records.add(secondArg<String>() + thirdArg<Throwable>().stackTraceToString())
        0
    }
    return records
}
