package org.pagel.ai.net.node.commands

import android.os.BatteryManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AndroidDeviceInfoSource]'s two pure int->enum mappings, tested directly against Android's own
 * constants without constructing [AndroidDeviceInfoSource] itself (which needs a real [android.content.Context]
 * — unavailable in this JVM-only test module, no Robolectric). `BatteryManager.BATTERY_STATUS_*`
 * and `PowerManager.THERMAL_STATUS_*` are `public static final int` fields, compile-time-inlined by
 * javac, so referencing them here needs no live Android runtime — only [mapBatteryState]/
 * [mapThermalState]'s own `when` bodies are under test.
 */
class AndroidDeviceInfoSourceMappingTest {

    @Test
    fun `BATTERY_STATUS_CHARGING maps to CHARGING`() {
        assertEquals(BatteryChargeState.CHARGING, AndroidDeviceInfoSource.mapBatteryState(BatteryManager.BATTERY_STATUS_CHARGING))
    }

    @Test
    fun `BATTERY_STATUS_FULL maps to FULL`() {
        assertEquals(BatteryChargeState.FULL, AndroidDeviceInfoSource.mapBatteryState(BatteryManager.BATTERY_STATUS_FULL))
    }

    @Test
    fun `BATTERY_STATUS_DISCHARGING maps to UNPLUGGED`() {
        assertEquals(BatteryChargeState.UNPLUGGED, AndroidDeviceInfoSource.mapBatteryState(BatteryManager.BATTERY_STATUS_DISCHARGING))
    }

    @Test
    fun `BATTERY_STATUS_NOT_CHARGING maps to UNPLUGGED`() {
        assertEquals(BatteryChargeState.UNPLUGGED, AndroidDeviceInfoSource.mapBatteryState(BatteryManager.BATTERY_STATUS_NOT_CHARGING))
    }

    @Test
    fun `BATTERY_STATUS_UNKNOWN maps to UNPLUGGED`() {
        assertEquals(BatteryChargeState.UNPLUGGED, AndroidDeviceInfoSource.mapBatteryState(BatteryManager.BATTERY_STATUS_UNKNOWN))
    }

    @Test
    fun `an unrecognized battery status int maps to UNPLUGGED, never throws`() {
        assertEquals(BatteryChargeState.UNPLUGGED, AndroidDeviceInfoSource.mapBatteryState(-999))
    }

    @Test
    fun `THERMAL_STATUS_NONE maps to NOMINAL, not a literal none`() {
        assertEquals(ThermalState.NOMINAL, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_NONE))
    }

    @Test
    fun `THERMAL_STATUS_LIGHT maps to LIGHT`() {
        assertEquals(ThermalState.LIGHT, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_LIGHT))
    }

    @Test
    fun `THERMAL_STATUS_MODERATE maps to MODERATE`() {
        assertEquals(ThermalState.MODERATE, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_MODERATE))
    }

    @Test
    fun `THERMAL_STATUS_SEVERE maps to SEVERE`() {
        assertEquals(ThermalState.SEVERE, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_SEVERE))
    }

    @Test
    fun `THERMAL_STATUS_CRITICAL maps to CRITICAL`() {
        assertEquals(ThermalState.CRITICAL, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_CRITICAL))
    }

    @Test
    fun `THERMAL_STATUS_EMERGENCY maps to EMERGENCY`() {
        assertEquals(ThermalState.EMERGENCY, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_EMERGENCY))
    }

    @Test
    fun `THERMAL_STATUS_SHUTDOWN maps to SHUTDOWN`() {
        assertEquals(ThermalState.SHUTDOWN, AndroidDeviceInfoSource.mapThermalState(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `an unrecognized thermal status int falls back to NOMINAL, never throws`() {
        assertEquals(ThermalState.NOMINAL, AndroidDeviceInfoSource.mapThermalState(-999))
    }
}
