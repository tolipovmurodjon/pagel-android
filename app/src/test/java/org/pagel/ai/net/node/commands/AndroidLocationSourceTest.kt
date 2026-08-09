@file:OptIn(ExperimentalCoroutinesApi::class)

package org.pagel.ai.net.node.commands

import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Covers exactly what [AndroidDeviceInfoSource] doesn't unit-test directly — see that class's doc
 * for why the rest of [AndroidLocationSource] (permission checks, `isLocationEnabled`,
 * `isAppInForeground`) is Android-touching glue this repo has no Robolectric/`androidTest` for.
 * [requestFix]'s listener-cleanup contract is the one exception: the task requires proving
 * cancellation actually unregisters the listener, not just that the dispatcher *would* cancel it, and
 * `LocationManager`/`LocationListener` are plain non-final SDK-stub classes mockk can stand in for in
 * a pure JVM test (same pattern as `BrainSyncTest`'s `mockk<Context>`) — no real Android runtime
 * needed to verify a `removeUpdates` call happened.
 */
class AndroidLocationSourceTest {

    private fun mockContextWithLocationManager(lm: LocationManager): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.getSystemService(Context.LOCATION_SERVICE) } returns lm
        return ctx
    }

    private fun mockLocationManagerWithOneEnabledProvider(): LocationManager {
        val lm = mockk<LocationManager>(relaxed = true)
        every { lm.allProviders } returns listOf(LocationManager.NETWORK_PROVIDER)
        every { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } returns true
        return lm
    }

    @Test
    fun `cancelling the coroutine unregisters the location listener`() = runTest {
        val lm = mockLocationManagerWithOneEnabledProvider()
        val listenerSlot = slot<LocationListener>()
        every {
            lm.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(), any<Executor>(), capture(listenerSlot))
        } just Runs
        val source = AndroidLocationSource(mockContextWithLocationManager(lm))

        val job = launch {
            runCatching { source.requestFix(DesiredAccuracy.BALANCED, timeoutMs = 30_000) }
        }
        runCurrent() // let requestFix run up to the suspension point without advancing virtual time
        assertTrue("requestLocationUpdates must have been called before we can assert its cleanup", listenerSlot.isCaptured)
        job.cancel()
        job.join()

        verify { lm.removeUpdates(listenerSlot.captured) }
    }

    @Test
    fun `letting timeoutMs elapse unregisters the location listener`() = runTest {
        val lm = mockLocationManagerWithOneEnabledProvider()
        val listenerSlot = slot<LocationListener>()
        every {
            lm.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(), any<Executor>(), capture(listenerSlot))
        } just Runs
        val source = AndroidLocationSource(mockContextWithLocationManager(lm))

        val result = runCatching { source.requestFix(DesiredAccuracy.BALANCED, timeoutMs = 5_000) }

        assertTrue(result.exceptionOrNull() is LocationFixFailure.Timeout)
        verify { lm.removeUpdates(listenerSlot.captured) }
    }

    @Test
    fun `no enabled provider matches the request reports Unavailable without ever registering a listener`() = runTest {
        val lm = mockk<LocationManager>(relaxed = true)
        every { lm.allProviders } returns emptyList()
        val source = AndroidLocationSource(mockContextWithLocationManager(lm))

        val result = runCatching { source.requestFix(DesiredAccuracy.BALANCED, timeoutMs = 5_000) }

        assertTrue(result.exceptionOrNull() is LocationFixFailure.Unavailable)
        verify(exactly = 0) { lm.requestLocationUpdates(any<String>(), any<Long>(), any<Float>(), any<Executor>(), any<LocationListener>()) }
    }

    // --- pure companion functions: no LocationManager/Context needed ---

    @Test
    fun `selectProviders omits GPS for coarse accuracy even with fine permission`() {
        val providers = AndroidLocationSource.selectProviders(
            enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            desiredAccuracy = DesiredAccuracy.COARSE,
            hasFinePermission = true,
        )
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), providers)
    }

    @Test
    fun `selectProviders omits GPS when fine permission is not granted, even for precise`() {
        val providers = AndroidLocationSource.selectProviders(
            enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            desiredAccuracy = DesiredAccuracy.PRECISE,
            hasFinePermission = false,
        )
        assertEquals(listOf(LocationManager.NETWORK_PROVIDER), providers)
    }

    @Test
    fun `selectProviders includes GPS for balanced and precise when fine permission is granted`() {
        val providers = AndroidLocationSource.selectProviders(
            enabledProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER),
            desiredAccuracy = DesiredAccuracy.BALANCED,
            hasFinePermission = true,
        )
        assertTrue(LocationManager.GPS_PROVIDER in providers)
        assertTrue(LocationManager.NETWORK_PROVIDER in providers)
    }

    @Test
    fun `selectProviders returns empty when nothing is enabled`() {
        val providers = AndroidLocationSource.selectProviders(
            enabledProviders = emptyList(),
            desiredAccuracy = DesiredAccuracy.PRECISE,
            hasFinePermission = true,
        )
        assertTrue(providers.isEmpty())
    }

    @Test
    fun `mapProviderToSource maps gps provider to GPS regardless of extras`() {
        assertEquals(LocationFixSource.GPS, AndroidLocationSource.mapProviderToSource(LocationManager.GPS_PROVIDER, null))
    }

    @Test
    fun `mapProviderToSource maps network provider with a wifi extra to WIFI`() {
        assertEquals(LocationFixSource.WIFI, AndroidLocationSource.mapProviderToSource(LocationManager.NETWORK_PROVIDER, "wifi"))
    }

    @Test
    fun `mapProviderToSource maps network provider with a cell extra to CELL`() {
        assertEquals(LocationFixSource.CELL, AndroidLocationSource.mapProviderToSource(LocationManager.NETWORK_PROVIDER, "cell"))
    }

    @Test
    fun `mapProviderToSource maps network provider with no extra to UNKNOWN`() {
        assertEquals(LocationFixSource.UNKNOWN, AndroidLocationSource.mapProviderToSource(LocationManager.NETWORK_PROVIDER, null))
    }

    @Test
    fun `mapProviderToSource maps an unrecognized provider to UNKNOWN`() {
        assertEquals(LocationFixSource.UNKNOWN, AndroidLocationSource.mapProviderToSource("passive", null))
    }
}
