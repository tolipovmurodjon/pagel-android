package org.pagel.ai.net.node.commands

import android.Manifest
import android.app.Notification
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.pagel.ai.net.node.NodeCommand
import org.pagel.ai.net.node.NodeCommandError
import org.pagel.ai.service.PagelNotificationListener
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** One read of the hardware step counter (steps since boot), or null when the phone has none or it stays silent. */
@Singleton
class StepReader @Inject constructor(@ApplicationContext private val ctx: Context) {
    fun permitted(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    suspend fun read(timeoutMs: Long = 4_000L): Long? {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return null
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Long> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0].toLong())
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        }
    }
}

private fun requireSteps(reader: StepReader): StepReader {
    if (!reader.permitted()) {
        throw NodeCommandError("MOTION_PERMISSION_REQUIRED", "ACTIVITY_RECOGNITION is not granted; open PAGEL and tap GRANT under SENSES")
    }
    return reader
}

/** `motion.pedometer` — raw step total since boot. The server turns two reads into steps per day. */
class MotionPedometerCommand @Inject constructor(private val reader: StepReader) : NodeCommand {
    override val name: String = "motion.pedometer"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val steps = requireSteps(reader).read()
            ?: throw NodeCommandError("STEP_SENSOR_UNAVAILABLE", "no step counter reading (sensor missing or silent)")
        val now = System.currentTimeMillis()
        return buildJsonObject {
            put("stepsSinceBoot", steps)
            put("bootTimeMs", now - SystemClock.elapsedRealtime())
            put("timestampMs", now)
        }
    }
}

/** `motion.activity` — movement over the window since the previous call (cadence, see [MotionMath]). */
@Singleton
class MotionActivityCommand @Inject constructor(private val reader: StepReader) : NodeCommand {
    override val name: String = "motion.activity"

    @Volatile private var prevSteps: Long? = null
    @Volatile private var prevAtMs: Long? = null

    override suspend fun invoke(params: JsonObject): JsonElement {
        val steps = requireSteps(reader).read()
            ?: throw NodeCommandError("STEP_SENSOR_UNAVAILABLE", "no step counter reading (sensor missing or silent)")
        val now = System.currentTimeMillis()
        val reading = MotionMath.classify(prevSteps, prevAtMs, steps, now)
        prevSteps = steps
        prevAtMs = now
        return buildJsonObject {
            put("state", reading.state)
            reading.stepsPerMinute?.let { put("stepsPerMinute", it) }
            reading.windowMs?.let { put("windowMs", it) }
            put("stepsSinceBoot", steps)
            put("timestampMs", now)
        }
    }
}

/** `notifications.list` — what is in the shade right now, read through [PagelNotificationListener]. */
class NotificationsListCommand @Inject constructor(@ApplicationContext private val ctx: Context) : NodeCommand {
    override val name: String = "notifications.list"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val limit = ((params["limit"] as? JsonPrimitive)?.intOrNull ?: 20).coerceIn(1, 100)
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        if (!enabled) {
            throw NodeCommandError("NOTIFICATION_LISTENER_DISABLED", "Notification access is off; open PAGEL and tap GRANT under SENSES")
        }
        val listener = PagelNotificationListener.instance
            ?: throw NodeCommandError("NOTIFICATION_LISTENER_UNAVAILABLE", "access is on but Android has not bound the listener yet")
        val active = (listener.activeNotifications ?: emptyArray())
            .filter { it.packageName != ctx.packageName }
            .sortedByDescending { it.postTime }
            .take(limit)
        return buildJsonObject {
            put("count", active.size)
            put("notifications", buildJsonArray {
                for (n in active) {
                    val extras = n.notification.extras
                    add(buildJsonObject {
                        put("key", n.key)
                        put("package", n.packageName)
                        put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
                        put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
                        put("postedAtMs", n.postTime)
                        put("ongoing", n.isOngoing)
                    })
                }
            })
        }
    }
}

/**
 * `device.apps` — foreground time per app (screen time). The name is OpenClaw's own installed-apps command,
 * already in the gateway allowlist, so using it costs no re-pair. Params: `sinceMs` (default local
 * midnight), `limit` (default 15). Package names only: labels need QUERY_ALL_PACKAGES, which this app avoids.
 */
class DeviceAppsCommand @Inject constructor(@ApplicationContext private val ctx: Context) : NodeCommand {
    override val name: String = "device.apps"

    override suspend fun invoke(params: JsonObject): JsonElement {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
            ?: throw NodeCommandError("USAGE_ACCESS_REQUIRED", "UsageStatsManager is not available")
        val now = System.currentTimeMillis()
        val start = (params["sinceMs"] as? JsonPrimitive)?.longOrNull
            ?: java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val limit = ((params["limit"] as? JsonPrimitive)?.intOrNull ?: 15).coerceIn(1, 100)
        val events = ArrayList<UsageEvent>()
        try {
            val q = usm.queryEvents(start, now)
            val e = UsageEvents.Event()
            while (q.hasNextEvent()) {
                q.getNextEvent(e)
                val t = e.eventType
                if (t == UsageMath.RESUMED || t == UsageMath.PAUSED || t == UsageMath.SCREEN_OFF || t == UsageMath.SHUTDOWN) {
                    events.add(UsageEvent(e.packageName ?: "", t, e.timeStamp))
                }
            }
        } catch (se: SecurityException) {
            throw NodeCommandError("USAGE_ACCESS_REQUIRED", "Usage access is off; open PAGEL and tap GRANT under SENSES")
        }
        val per = UsageMath.foregroundMs(events, start, now, exclude = setOf(ctx.packageName))
        // queryEvents returns nothing (not an error) when access is off on some builds: say so instead of "0 ms".
        if (events.isEmpty() && !hasUsageAccess()) {
            throw NodeCommandError("USAGE_ACCESS_REQUIRED", "Usage access is off; open PAGEL and tap GRANT under SENSES")
        }
        val top = per.entries.sortedByDescending { it.value }.take(limit)
        return buildJsonObject {
            put("windowStartMs", start)
            put("windowEndMs", now)
            put("totalForegroundMs", per.values.sum())
            put("apps", JsonArray(top.map { (pkg, ms) ->
                buildJsonObject {
                    put("package", pkg)
                    put("foregroundMs", ms)
                }
            }))
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = ctx.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        return appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            ctx.packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }
}
