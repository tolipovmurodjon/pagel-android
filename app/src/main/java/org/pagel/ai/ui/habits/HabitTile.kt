package org.pagel.ai.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.pagel.ai.data.habits.HabitLook
import org.pagel.ai.ui.glass.Emoji
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.TileColors

/**
 * A habit's picture is a line icon we draw, never an emoji (Boss 2026-10-31: "emoji makes it AI slop"): the same lit
 * gradient tile as a project, with a white icon. The key is stored in the habit's look field ("water/sky"), the same
 * field the emoji keys used, so an old saved emoji key still resolves ([LEGACY]) and nothing breaks.
 */
object HabitIcons {
    private fun line(name: String, vararg paths: String) = TabIcons.icon(name, *paths)
    private fun ring(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

    /** Every icon the picker offers, in the order it shows them. */
    val all: Map<String, ImageVector> by lazy {
        linkedMapOf(
            "water" to line("water", "M12 22a7 7 0 0 0 7-7c0-2-1-3.9-3-5.5s-3.5-4-4-6.5c-.5 2.5-2 4.9-4 6.5C6 11.1 5 13 5 15a7 7 0 0 0 7 7z"),
            "flame" to TabIcons.Flame,
            "dumbbell" to line("dumbbell", "M3 9v6", "M6 6v12", "M18 6v12", "M21 9v6", "M6 12h12"),
            "run" to line("run", "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"),
            "person" to line("person", ring(12f, 4.5f, 1.5f), "M12 7v6", "M8 10h8", "M10 21l2-8 2 8"),
            "book" to TabIcons.BookOpen,
            "pencil" to line("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4"),
            "bulb" to (TabIcons.lucide("lightbulb") ?: TabIcons.Sparkles),
            "code" to line("code", "m16 18 6-6-6-6", "m8 6-6 6 6 6"),
            "headphones" to line("headphones", "M3 14h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a9 9 0 0 1 18 0v7a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3"),
            "coffee" to line("coffee", "M10 2v2", "M14 2v2", "M16 8a1 1 0 0 1 1 1v8a4 4 0 0 1-4 4H7a4 4 0 0 1-4-4V9a1 1 0 0 1 1-1h14a4 4 0 1 1 0 8h-1", "M6 2v2"),
            "food" to line("food", "M3 2v7c0 1.1.9 2 2 2h4a2 2 0 0 0 2-2V2", "M7 2v20", "M21 15V2a5 5 0 0 0-5 5v6c0 1.1.9 2 2 2h3Zm0 0v7"),
            "leaf" to line("leaf", "M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10Z", "M2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12"),
            "pill" to line("pill", "m10.5 20.5 10-10a4.95 4.95 0 1 0-7-7l-10 10a4.95 4.95 0 1 0 7 7Z", "m8.5 8.5 7 7"),
            "heart" to line("heart", "M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z"),
            "moon" to TabIcons.Moon,
            "bed" to line("bed", "M2 20v-8a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v8", "M4 10V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v4", "M12 4v6", "M2 18h20"),
            "sunrise" to TabIcons.Sunrise,
            "sun" to TabIcons.Sun,
            "target" to line("target", ring(12f, 12f, 9f), ring(12f, 12f, 5f), ring(12f, 12f, 1f)),
            "coin" to line("coin", ring(12f, 12f, 9f), "M12 7v10", "M15 9.5c-.5-1-1.7-1.5-3-1.5-1.7 0-3 .8-3 2s1.3 1.6 3 2 3 .8 3 2-1.3 2-3 2c-1.3 0-2.5-.5-3-1.5"),
            "trophy" to line("trophy", "M6 9H4.5a2.5 2.5 0 0 1 0-5H6", "M18 9h1.5a2.5 2.5 0 0 0 0-5H18", "M4 22h16", "M10 14.66V17c0 .55-.47.98-.97 1.21C7.85 18.75 7 20.24 7 22", "M14 14.66V17c0 .55.47.98.97 1.21C16.15 18.75 17 20.24 17 22", "M18 2H6v7a6 6 0 0 0 12 0V2Z"),
            "phone" to TabIcons.Phone,
            "calendar" to TabIcons.Calendar,
            "inbox" to TabIcons.Inbox,
            "clock" to TabIcons.Clock,
            "rocket" to TabIcons.forProject("uzcosmos"),
            "landmark" to TabIcons.forProject("samarqand"),
            "snow" to line("snow", "M12 2v20", "M2 12h20", "m5 5 14 14", "m19 5-14 14"),
            "sparkles" to TabIcons.Sparkles,
        )
    }

    /** An old saved emoji key to the nearest icon (the bundled emoji pictures are retired for habits). */
    private val LEGACY = mapOf(
        "coffee" to "coffee", "water" to "water", "food" to "food", "salad" to "leaf", "chicken" to "food", "apple" to "leaf", "milk" to "water",
        "gym" to "dumbbell", "biceps" to "dumbbell", "lotus" to "person", "shoe" to "run", "walk" to "run", "footprints" to "run",
        "book" to "book", "books" to "book", "inbox" to "inbox", "memo" to "pencil", "brain" to "bulb", "laptop" to "code",
        "headphone" to "headphones", "bulb" to "bulb", "moon" to "moon", "bed" to "bed", "sleeping" to "bed", "phone_off" to "phone",
        "toothbrush" to "sparkles", "shower" to "water", "pill" to "pill", "scale" to "heart", "ruler" to "target", "broom" to "sparkles",
        "money" to "coin", "calendar" to "calendar", "alarm" to "clock", "stopwatch" to "clock", "target" to "target", "rocket" to "rocket",
        "sunrise" to "sunrise", "sun" to "sun", "sun_cloud" to "sun", "sunset" to "sunrise", "night" to "moon", "mosque" to "landmark",
        "seedling" to "leaf", "plant" to "leaf", "tree" to "leaf", "tulip" to "leaf", "fire" to "flame", "ice" to "snow", "snowflake" to "snow",
        "link" to "sparkles", "trophy" to "trophy", "check" to "sparkles", "party" to "sparkles", "sparkles" to "sparkles",
    )

    /** Each icon's twin among the bundled emoji, so a list that still draws emoji shows the same thing. */
    private val TWIN = mapOf(
        "water" to "water", "flame" to "fire", "dumbbell" to "gym", "run" to "shoe", "person" to "lotus", "book" to "book", "pencil" to "memo",
        "bulb" to "bulb", "code" to "laptop", "headphones" to "headphone", "coffee" to "coffee", "food" to "food", "leaf" to "seedling",
        "pill" to "pill", "heart" to "sparkles", "moon" to "moon", "bed" to "bed", "sunrise" to "sunrise", "sun" to "sun", "target" to "target",
        "coin" to "money", "trophy" to "trophy", "phone" to "phone_off", "calendar" to "calendar", "inbox" to "inbox", "clock" to "alarm",
        "rocket" to "rocket", "landmark" to "mosque", "snow" to "snowflake", "sparkles" to "sparkles",
    )

    fun emojiOf(iconKey: String?): String? = TWIN[iconKey]

    /** The icon key a habit wears: Boss's own pick, else what an old emoji key or the title's words say. */
    fun keyFor(title: String, chosen: String?): String {
        val pick = HabitLook.emojiOf(chosen)
        if (pick != null && pick in all) return pick
        return LEGACY[Emoji.keyFor(title, chosen)] ?: "sparkles"
    }

    fun iconFor(title: String, chosen: String?): ImageVector = all.getValue(keyFor(title, chosen))

    /** The tile colour when Boss picked none: by what the habit is about, as the lists do. */
    fun defaultColour(title: String, chosen: String?): String = tileOf(Emoji.keyFor(title, chosen))

    /** Every old emoji key has an icon (a test holds this, so no saved look is ever blank). */
    internal fun legacyKeys(): Set<String> = LEGACY.keys
}

/** The habit's lit gradient tile with a white line icon: the project tile's look (brain rules/gradient-project-icons). */
@Composable
fun HabitTile(icon: ImageVector, colour: String, size: Dp, modifier: Modifier = Modifier) {
    val (a, b) = TileColors.stops("", colour)
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.3f))
            .background(Brush.linearGradient(listOf(a, b)))
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(size * 0.3f))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.56f)) }
}
