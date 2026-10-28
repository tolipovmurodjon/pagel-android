package org.pagel.ai.data.habits

import com.batoulapps.adhan.CalculationMethod
import com.batoulapps.adhan.Coordinates
import com.batoulapps.adhan.Madhab
import com.batoulapps.adhan.PrayerTimes
import com.batoulapps.adhan.data.DateComponents
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Namoz (Boss 2026-10-28: "we will start tracking namaz fully … from 1 November"): the five daily prayers in his Uzbek
 * names, each its own habit in the group "namoz", with today's time window for Tashkent computed on the phone.
 *
 * The method is the Muslim Board of Uzbekistan's timetable (namozvaqti.uz / islom.uz): Fajr 15.5°, Isha 15°, Hanafi
 * Asr, Peshin 4 min after zawal, Xufton 1 min earlier. Checked against the published Tashkent table for all 30 days of
 * November 2026: 180 times, the worst off by 1 minute (NamozTest). PAGEL never rules on anything; it only shows times.
 */
object Namoz {
    const val GROUP = "namoz"
    /** No chain before this day and no missed day counted before it. */
    const val START = "2026-11-01"
    private val TASHKENT = Coordinates(41.2995, 69.2401)
    private val ZONE: ZoneId = ZoneId.of("Asia/Tashkent")

    enum class Prayer(val uz: String, val emoji: String) {
        BOMDOD("Bomdod", "sunrise"), PESHIN("Peshin", "sun"), ASR("Asr", "sun_cloud"), SHOM("Shom", "sunset"), XUFTON("Xufton", "night"),
    }

    /** One prayer's window: from its time to the next one's (Bomdod ends at sunrise, Xufton at the next Bomdod). */
    data class Window(val prayer: Prayer, val from: LocalTime, val to: LocalTime) {
        fun label() = "${from.hhmm()}–${to.hhmm()}"
    }

    private fun LocalTime.hhmm() = "%02d:%02d".format(hour, minute)

    private fun params() = CalculationMethod.OTHER.parameters.apply {
        fajrAngle = 15.5
        ishaAngle = 15.0
        madhab = Madhab.HANAFI
        adjustments.dhuhr = 4
        adjustments.maghrib = 1
        adjustments.isha = -1
    }

    private fun times(day: LocalDate) = PrayerTimes(TASHKENT, DateComponents(day.year, day.monthValue, day.dayOfMonth), params())

    private fun local(d: java.util.Date): LocalTime = d.toInstant().atZone(ZONE).toLocalTime().withSecond(0).withNano(0)

    /** The day's six times: Bomdod, Quyosh (sunrise), Peshin, Asr, Shom, Xufton. */
    fun day(day: LocalDate): List<LocalTime> {
        val t = times(day)
        return listOf(t.fajr, t.sunrise, t.dhuhr, t.asr, t.maghrib, t.isha).map(::local)
    }

    fun windows(day: LocalDate): List<Window> {
        val (fajr, sunrise, dhuhr, asr, maghrib, isha) = day(day)
        val nextFajr = day(day.plusDays(1))[0]
        return listOf(
            Window(Prayer.BOMDOD, fajr, sunrise), Window(Prayer.PESHIN, dhuhr, asr), Window(Prayer.ASR, asr, maghrib),
            Window(Prayer.SHOM, maghrib, isha), Window(Prayer.XUFTON, isha, nextFajr),
        )
    }

    /** The prayer a habit's title names (Boss's own Uzbek names), or null for any other habit. */
    fun prayerOf(title: String): Prayer? = Prayer.entries.firstOrNull { title.trim().equals(it.uz, ignoreCase = true) }

    /** A namoz habit counts only from [START]: earlier days are neither done nor missed. */
    fun counts(day: String): Boolean = day >= START

    /** The whole of the reminder: "Peshin vaqti kirdi · 12:10". Plain and respectful, nothing more (Boss). */
    fun notice(w: Window) = "${w.prayer.uz} vaqti kirdi · ${w.from.hhmm()}"
}

private operator fun <T> List<T>.component6(): T = this[5]
