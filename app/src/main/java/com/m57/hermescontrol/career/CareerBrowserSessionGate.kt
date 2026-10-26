package com.m57.hermescontrol.career

/** In-memory authority only. Keeping a WebView alive never keeps an in-flight fill authorized. */
internal class CareerBrowserSessionGate(
    initiallyForeground: Boolean,
) {
    var isForeground: Boolean = initiallyForeground
        private set
    var epoch: Long = 0
        private set
    var automaticFillAllowed: Boolean = true
        private set

    fun invalidate() {
        epoch++
    }

    fun pause() {
        isForeground = false
        automaticFillAllowed = false
        invalidate()
    }

    fun resume() {
        isForeground = true
    }

    fun allowAutomaticFill() {
        automaticFillAllowed = true
    }

    fun shouldAutomaticallyFill(
        documentReady: Boolean,
        attemptedGeneration: Int,
        documentGeneration: Int,
    ): Boolean = isForeground && automaticFillAllowed && documentReady && attemptedGeneration != documentGeneration

    fun isCurrent(expectedEpoch: Long): Boolean = isForeground && epoch == expectedEpoch
}
