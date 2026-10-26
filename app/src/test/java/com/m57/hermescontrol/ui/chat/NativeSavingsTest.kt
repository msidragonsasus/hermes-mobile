package com.m57.hermescontrol.ui.chat

import org.junit.Assert.*
import org.junit.Test

class NativeSavingsTest {
    @Test fun monthEndTwentyYearsMatchesIndependentAnnuityFormula() {
        val result = simulateSavings(30000.0, 20, 5.0, 18000000.0, 0.0)
        val r = Math.pow(1.05, 1.0 / 12) - 1
        val expected = 30000 * (Math.pow(1 + r, 240.0) - 1) / r
        assertEquals(expected, result.last.value, .001)
        assertEquals(7200000.0, result.last.principal, 0.0)
        assertNull(result.capMonth)
    }

    @Test fun zeroRateIsExactlyPrincipal() {
        val r = simulateSavings(30000.0, 20, 0.0, 18000000.0, 50000.0)
        assertEquals(7250000.0, r.last.value, 0.0)
        assertEquals(r.last.value, r.last.principal, 0.0)
    }

    @Test fun capStopsNewContributionsAndAllowsGrowth() {
        val r = simulateSavings(100000.0, 20, 5.0, 18000000.0, 0.0)
        assertEquals(180, r.capMonth)
        assertEquals(18000000.0, r.last.principal, 0.0)
        assertTrue(r.points.last().value > r.points[15].value)
    }

    @Test fun partialLastPaymentDoesNotExceedCap() {
        val r = simulateSavings(70000.0, 30, 0.0, 18000000.0, 0.0)
        assertEquals(258, r.capMonth)
        assertEquals(18000000.0, r.last.value, 0.0)
    }

    @Test fun zeroContributionAndZeroCapAreSafe() {
        assertEquals(0.0, simulateSavings(0.0, 20, 5.0, 18000000.0, 0.0).last.value, 0.0)
        assertEquals(0.0, simulateSavings(30000.0, 20, 5.0, 0.0, 0.0).last.value, 0.0)
    }

    @Test fun negativeRateProducesLossWithoutNegativeAssets() {
        val r = simulateSavings(30000.0, 20, -5.0, 18000000.0, 0.0)
        assertTrue(r.last.value > 0 && r.last.value < r.last.principal)
    }

    @Test fun initialAssetsAreNotCountedAgainstNewContributionCap() {
        val r = simulateSavings(100000.0, 1, 0.0, 100000.0, 500000.0)
        assertEquals(600000.0, r.last.value, 0.0)
        assertEquals(1, r.capMonth)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonFiniteInput() {
        simulateSavings(Double.NaN, 20, 5.0, 18000000.0, 0.0)
    }
}
