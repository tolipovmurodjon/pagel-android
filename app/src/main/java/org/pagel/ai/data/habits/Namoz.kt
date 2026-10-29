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
    private val ZONE: ZoneId = ZoneId.of("Asia/Tashkent")

    enum class Prayer(val uz: String, val emoji: String) {
        BOMDOD("Bomdod", "sunrise"), PESHIN("Peshin", "sun"), ASR("Asr", "sun_cloud"), SHOM("Shom", "sunset"), XUFTON("Xufton", "night"),
    }

    /** One prayer's window: from its time to the next one's (Bomdod ends at sunrise, Xufton at the next Bomdod). */
    data class Window(val prayer: Prayer, val from: LocalTime, val to: LocalTime) {
        fun label() = "${from.hhmm()}–${to.hhmm()}"
    }

    private fun LocalTime.hhmm() = "%02d:%02d".format(hour, minute)

    /**
     * Where and how the times are computed (the Namoz page's settings). The default is Tashkent on the Muslim Board of
     * Uzbekistan's method, the one checked against the published table. [offsets] = minutes added to Bomdod, Peshin,
     * Asr, Shom, Xufton (Boss's own nudges to match his mosque); sunrise is never moved.
     */
    data class Setup(
        val lat: Double = 41.2995,
        val lon: Double = 69.2401,
        val method: String = UZBEKISTAN,
        val hanafi: Boolean = true,
        val offsets: List<Int> = listOf(0, 0, 0, 0, 0),
    )

    const val UZBEKISTAN = "uzbekistan"
    /** The methods the page offers, by key; an unknown key falls back to Uzbekistan's. */
    val METHODS = listOf(UZBEKISTAN to "Uzbekistan (O'zbekiston musulmonlari idorasi)", "mwl" to "Muslim World League", "karachi" to "Karachi")

    private fun params(s: Setup) = when (s.method) {
        "mwl" -> CalculationMethod.MUSLIM_WORLD_LEAGUE.parameters
        "karachi" -> CalculationMethod.KARACHI.parameters
        else -> CalculationMethod.OTHER.parameters.apply {
            fajrAngle = 15.5
            ishaAngle = 15.0
            adjustments.dhuhr = 4
            adjustments.maghrib = 1
            adjustments.isha = -1
        }
    }.apply { madhab = if (s.hanafi) Madhab.HANAFI else Madhab.SHAFI }

    private fun times(day: LocalDate, s: Setup) =
        PrayerTimes(Coordinates(s.lat, s.lon), DateComponents(day.year, day.monthValue, day.dayOfMonth), params(s))

    private fun local(d: java.util.Date): LocalTime = d.toInstant().atZone(ZONE).toLocalTime().withSecond(0).withNano(0)

    /** The day's six times: Bomdod, Quyosh (sunrise), Peshin, Asr, Shom, Xufton. */
    fun day(day: LocalDate, s: Setup = Setup()): List<LocalTime> {
        val t = times(day, s)
        val raw = listOf(t.fajr, t.sunrise, t.dhuhr, t.asr, t.maghrib, t.isha).map(::local)
        val add = listOf(s.offsets.getOrElse(0) { 0 }, 0) + (1..4).map { s.offsets.getOrElse(it) { 0 } }
        return raw.mapIndexed { i, time -> time.plusMinutes(add[i].toLong()) }
    }

    fun windows(day: LocalDate, s: Setup = Setup()): List<Window> {
        val (fajr, sunrise, dhuhr, asr, maghrib, isha) = day(day, s)
        val nextFajr = day(day.plusDays(1), s)[0]
        return listOf(
            Window(Prayer.BOMDOD, fajr, sunrise), Window(Prayer.PESHIN, dhuhr, asr), Window(Prayer.ASR, asr, maghrib),
            Window(Prayer.SHOM, maghrib, isha), Window(Prayer.XUFTON, isha, nextFajr),
        )
    }

    /** The prayer a habit's title names (Boss's own Uzbek names), or null for any other habit. */
    fun prayerOf(title: String): Prayer? = Prayer.entries.firstOrNull { title.trim().equals(it.uz, ignoreCase = true) }

    /** A namoz habit counts only from [START]: earlier days are neither done nor missed. */
    fun counts(day: String): Boolean = day >= START

    /** When [prayer]'s time next comes after [now], on or after [START] (the reminder's alarm). */
    fun nextStart(prayer: Prayer, now: java.time.ZonedDateTime, s: Setup = Setup()): java.time.ZonedDateTime {
        var day = maxOf(now.withZoneSameInstant(ZONE).toLocalDate(), LocalDate.parse(START))
        repeat(3) {
            val w = windows(day, s).first { it.prayer == prayer }
            val at = java.time.ZonedDateTime.of(day, w.from, ZONE)
            if (at.isAfter(now)) return at
            day = day.plusDays(1)
        }
        return java.time.ZonedDateTime.of(day, windows(day, s).first { it.prayer == prayer }.from, ZONE)
    }

    /** The whole of the reminder: "Asr · 15:42". Plain and respectful, nothing more (Boss, namoz brief). */
    fun notice(w: Window) = "${w.prayer.uz} · ${w.from.hhmm()}"

    /** The page's saved settings as a [Setup]; no row yet = Tashkent, Uzbekistan's method, Hanafi Asr, no offsets. */
    fun setupOf(s: org.pagel.ai.data.db.NamozSettingsEntity?): Setup {
        if (s == null) return Setup()
        val offsets = s.offsets.split(",").map { it.trim().toIntOrNull() ?: 0 }.let { it + List((5 - it.size).coerceAtLeast(0)) { 0 } }.take(5)
        val here = s.autoLocation && (s.lat != 0.0 || s.lon != 0.0)
        return Setup(lat = if (here) s.lat else Setup().lat, lon = if (here) s.lon else Setup().lon, method = s.method, hanafi = s.hanafi, offsets = offsets)
    }

    private val HIJRI_MONTHS = listOf(
        "Muharram", "Safar", "Rabiul avval", "Rabiul oxir", "Jumodul avval", "Jumodul oxir",
        "Rajab", "Sha'bon", "Ramazon", "Shavvol", "Zulqa'da", "Zulhijja",
    )

    /** The Hijri date under the header, computed (Umm al-Qura, java.time): "19 Jumodul avval 1448". May differ by a day locally. */
    fun hijri(day: LocalDate): String {
        val h = java.time.chrono.HijrahDate.from(day)
        val d = h.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
        val m = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR)
        val y = h.get(java.time.temporal.ChronoField.YEAR)
        return "$d ${HIJRI_MONTHS[m - 1]} $y"
    }

    /** The next prayer from [now]: today's next one, or tomorrow's Bomdod after Xufton has begun. */
    fun next(now: java.time.LocalDateTime, s: Setup = Setup()): Pair<Window, java.time.LocalDateTime> {
        val today = windows(now.toLocalDate(), s)
        val w = today.firstOrNull { now.toLocalDate().atTime(it.from).isAfter(now) }
        if (w != null) return w to now.toLocalDate().atTime(w.from)
        val tomorrow = windows(now.toLocalDate().plusDays(1), s).first()
        return tomorrow to now.toLocalDate().plusDays(1).atTime(tomorrow.from)
    }

    /** The prayer whose time it is at [now] (Bomdod ends at sunrise, so between sunrise and Peshin there is none). */
    fun current(now: java.time.LocalDateTime, s: Setup = Setup()): Prayer? {
        val t = now.toLocalTime()
        return windows(now.toLocalDate(), s).firstOrNull { w -> if (w.to > w.from) t >= w.from && t < w.to else t >= w.from || t < w.to }?.prayer
    }

    /** "in 1 h 03 m" / "in 12 m" / "now": the countdown, whole minutes. */
    fun countdown(now: java.time.LocalDateTime, at: java.time.LocalDateTime): String {
        val m = java.time.Duration.between(now, at).toMinutes().coerceAtLeast(0)
        return when {
            m < 1 -> "now"
            m < 60 -> "in $m m"
            else -> "in ${m / 60} h %02d m".format(m % 60)
        }
    }
}

private operator fun <T> List<T>.component6(): T = this[5]
