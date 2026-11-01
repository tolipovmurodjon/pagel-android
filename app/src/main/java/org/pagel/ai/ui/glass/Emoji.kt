package org.pagel.ai.ui.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import org.pagel.ai.R

/**
 * Human-made emoji (round D 0c, Boss: "notifications with emoji like coffee etc, human-made emojis"): Microsoft's
 * Fluent Emoji 3D (github.com/microsoft/fluentui-emoji, MIT, credit in NOTICE), bundled as 144 px WebP in
 * res/drawable-nodpi, only the ones PAGEL uses. A habit gets one by keywords in its title (English and Uzbek); Boss can
 * pick another in the habit's page, which wins.
 */
object Emoji {
    /** Every bundled emoji by its key, in the order the picker shows them. */
    val all: Map<String, Int> = linkedMapOf(
        "coffee" to R.drawable.emoji_coffee, "water" to R.drawable.emoji_water, "food" to R.drawable.emoji_food,
        "salad" to R.drawable.emoji_salad, "chicken" to R.drawable.emoji_chicken, "apple" to R.drawable.emoji_apple,
        "milk" to R.drawable.emoji_milk, "gym" to R.drawable.emoji_gym, "biceps" to R.drawable.emoji_biceps,
        "lotus" to R.drawable.emoji_lotus, "shoe" to R.drawable.emoji_shoe, "walk" to R.drawable.emoji_walk,
        "footprints" to R.drawable.emoji_footprints, "book" to R.drawable.emoji_book, "books" to R.drawable.emoji_books,
        "inbox" to R.drawable.emoji_inbox, "memo" to R.drawable.emoji_memo, "brain" to R.drawable.emoji_brain,
        "laptop" to R.drawable.emoji_laptop, "headphone" to R.drawable.emoji_headphone, "bulb" to R.drawable.emoji_bulb,
        "moon" to R.drawable.emoji_moon, "bed" to R.drawable.emoji_bed, "sleeping" to R.drawable.emoji_sleeping,
        "phone_off" to R.drawable.emoji_phone_off, "toothbrush" to R.drawable.emoji_toothbrush, "shower" to R.drawable.emoji_shower,
        "pill" to R.drawable.emoji_pill, "scale" to R.drawable.emoji_scale, "ruler" to R.drawable.emoji_ruler,
        "broom" to R.drawable.emoji_broom, "money" to R.drawable.emoji_money, "calendar" to R.drawable.emoji_calendar,
        "alarm" to R.drawable.emoji_alarm, "stopwatch" to R.drawable.emoji_stopwatch, "target" to R.drawable.emoji_target,
        "rocket" to R.drawable.emoji_rocket, "sunrise" to R.drawable.emoji_sunrise, "sun" to R.drawable.emoji_sun,
        "sun_cloud" to R.drawable.emoji_sun_cloud, "sunset" to R.drawable.emoji_sunset, "night" to R.drawable.emoji_night,
        "mosque" to R.drawable.emoji_mosque, "seedling" to R.drawable.emoji_seedling, "plant" to R.drawable.emoji_plant,
        "tree" to R.drawable.emoji_tree, "tulip" to R.drawable.emoji_tulip, "fire" to R.drawable.emoji_fire,
        "ice" to R.drawable.emoji_ice, "snowflake" to R.drawable.emoji_snowflake, "link" to R.drawable.emoji_link,
        "trophy" to R.drawable.emoji_trophy, "check" to R.drawable.emoji_check, "party" to R.drawable.emoji_party,
        "sparkles" to R.drawable.emoji_sparkles,
    )

    /** First match wins, so "Log today's food" is food before "log" could be a note. Words are matched lower-case. */
    private val KEYWORDS: List<Pair<String, List<String>>> = listOf(
        "food" to listOf("food", "ovqat", "meal", "calor", "kalori", "lunch", "dinner", "breakfast", "nonushta", "tushlik", "kechki ovqat"),
        "chicken" to listOf("protein", "oqsil"),
        "salad" to listOf("salad", "vegetable", "sabzavot"),
        "apple" to listOf("fruit", "meva", "sugar", "shakar"),
        "coffee" to listOf("coffee", "kofe", "qahva", "tea ", "choy"),
        "water" to listOf("water", "suv ", "drink", "hydrat"),
        "gym" to listOf("gym", "workout", "lift", "trening", "mashq", "full-body", "train "),
        "lotus" to listOf("stretch", "yoga", "meditat", "floor routine", "breath", "nafas", "cho'z"),
        "shoe" to listOf("run", "yugur", "steps", "qadam", "jog"),
        "walk" to listOf("walk", "sayr", "piyoda"),
        "inbox" to listOf("inbox", "saves"),
        "book" to listOf("read", "pages", "kitob", "o'qi"),
        "headphone" to listOf("listen", "podcast", "audio", "eshit"),
        "moon" to listOf("wind down", "winding down", "wind-down", "shut down", "close the day"),
        "bed" to listOf("sleep", "bed", "uxla"),
        "phone_off" to listOf("phone away", "screen", "telefon"),
        "scale" to listOf("weigh", "vazn", "tarozi"),
        "ruler" to listOf("measure", "waist", "o'lcha"),
        "toothbrush" to listOf("teeth", "tooth", "floss", "tish"),
        "shower" to listOf("shower", "dush", "cold"),
        "pill" to listOf("vitamin", "pill", "dori", "creatine"),
        "memo" to listOf("journal", "write", "yoz", "diary", "kundalik"),
        "brain" to listOf("learn", "study", "o'rgan", "lesson", "dars"),
        "laptop" to listOf("code", "kod", "program"),
        "calendar" to listOf("review", "plan"),
        "broom" to listOf("clean", "tozala", "tidy"),
        "money" to listOf("money", "spend", "pul", "budget"),
        "sunrise" to listOf("morning", "ertalab", "wake"),
    )

    /** The habit's emoji key: Boss's own pick, else the first keyword its title holds, else a seedling (a new habit). */
    fun keyFor(title: String, chosen: String? = null): String {
        // A chosen look may carry its tile colour too ("coffee/amber", HabitLook): the emoji is the part before "/".
        val pick = chosen?.substringBefore('/')
        // A habit's own pick may be one of the drawn line icons (HabitIcons.names), kept as its name.
        if (pick != null && (pick in all || pick in HabitIcons.names)) return pick
        val t = " ${title.lowercase()} "
        return KEYWORDS.firstOrNull { (_, words) -> words.any { it in t } }?.first ?: "seedling"
    }

    @DrawableRes
    fun res(key: String): Int = all[key] ?: R.drawable.emoji_seedling

    /** What a notification is about: its large icon and the emoji its text starts with. */
    enum class Notice(val key: String, val glyph: String) {
        MORNING_PLAN("coffee", "☕"), CLOSE_THE_DAY("moon", "🌙"), STREAK("fire", "🔥"), GYM("gym", "🏋️"),
        FOOD("food", "🍽️"), WATER("water", "💧"), INBOX("inbox", "📥"), OVERDUE("alarm", "⏰"), DAY_CLEARED("party", "🎉"),
        HABIT("seedling", "🌱"), PRAYER("mosque", "🕌"),
    }

    /** The glyph a habit's notification text starts with (the bundled picture's own Unicode twin). */
    fun glyph(key: String): String = GLYPHS[key] ?: "🌱"

    /**
     * The bundled picture for a Unicode emoji ("🪥" → "toothbrush"), with or without its U+FE0F; null if none is bundled.
     * A moment's words are drawn with the picture, not the font: an older font draws 🪥 as an empty box (checker 3 #11).
     */
    fun keyOfGlyph(glyph: String): String? = glyph.replace("\uFE0F", "").let { g -> GLYPHS.entries.firstOrNull { it.value.replace("\uFE0F", "") == g }?.key }

    private val GLYPHS = mapOf(
        "coffee" to "☕", "water" to "💧", "food" to "🍽️", "salad" to "🥗", "chicken" to "🍗", "apple" to "🍎", "milk" to "🥛",
        "gym" to "🏋️", "biceps" to "💪", "lotus" to "🧘", "shoe" to "👟", "walk" to "🚶", "footprints" to "👣", "book" to "📖",
        "books" to "📚", "inbox" to "📥", "memo" to "📝", "brain" to "🧠", "laptop" to "💻", "headphone" to "🎧", "bulb" to "💡",
        "moon" to "🌙", "bed" to "🛏️", "sleeping" to "😴", "phone_off" to "📴", "toothbrush" to "🪥", "shower" to "🚿",
        "pill" to "💊", "scale" to "⚖️", "ruler" to "📏", "broom" to "🧹", "money" to "💰", "calendar" to "🗓️", "alarm" to "⏰",
        "stopwatch" to "⏱️", "target" to "🎯", "rocket" to "🚀", "sunrise" to "🌅", "sun" to "☀️", "sun_cloud" to "🌤️",
        "sunset" to "🌇", "night" to "🌃", "mosque" to "🕌", "seedling" to "🌱", "plant" to "🪴", "tree" to "🌳", "tulip" to "🌷",
        "fire" to "🔥", "ice" to "🧊", "snowflake" to "❄️", "link" to "🔗", "trophy" to "🏆", "check" to "✅", "party" to "🎉",
        "sparkles" to "✨",
    )

    /**
     * A notification's large icon: the emoji's bitmap (decoded off Main by the caller: receivers run on a worker via
     * goAsync). Null if it cannot be decoded, so a notification never fails for want of a picture.
     */
    fun largeIcon(ctx: Context, key: String): Bitmap? = runCatching { BitmapFactory.decodeResource(ctx.resources, res(key)) }.getOrNull()
}
