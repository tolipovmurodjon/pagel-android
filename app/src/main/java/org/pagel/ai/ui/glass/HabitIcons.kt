package org.pagel.ai.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The habits' picture set, drawn line icons only (Boss 2026-10-31, brain rules/no-emoji-in-app-ui: "remove emojis").
 * The paths are Lucide's (lucide-react 1.49 data, ISC License, Copyright Lucide Icons and Contributors): 24x24, 2 px
 * round strokes, tinted by Icon(). A habit stores a name from [names] (or an old emoji key, which [forEmoji] maps to the
 * nearest line icon, so nothing Boss picked before is lost).
 */
object HabitIcons {
    private val PATHS: Map<String, Array<String>> = linkedMapOf(
        "bed" to arrayOf("M2 4 v16", "M2 8 h18 a2 2 0 0 1 2 2 v10", "M2 17 h20", "M6 8 v9"),
        "sun-dim" to arrayOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 4 h.01", "M20 12 h.01", "M12 20 h.01", "M4 12 h.01", "M17.657 6.343 h.01", "M17.657 17.657 h.01", "M6.343 17.657 h.01", "M6.343 6.343 h.01"),
        "glass-water" to arrayOf("M5.116 4.104 A1 1 0 0 1 6.11 3 h11.78 a1 1 0 0 1 .994 1.105 L17.19 20.21 A2 2 0 0 1 15.2 22 H8.8 a2 2 0 0 1 -2 -1.79 z", "M6 12 a5 5 0 0 1 6 0 5 5 0 0 0 6 0"),
        "book-open" to arrayOf("M12 5 v16", "M20.001 19 A2 2 0 0 0 22 17 V5 a2 2 0 0 0 -1.999 -2 L16 3.002 A5 5 0 0 0 12 5 a5 5 0 0 0 -4 -2 H4 a2 2 0 0 0 -2 2 v12 a2 2 0 0 0 1.999 2 H8 a5 5 0 0 1 4 2 5 5 0 0 1 4 -2 z"),
        "dumbbell" to arrayOf("M17.596 12.768 a2 2 0 1 0 2.829 -2.829 l-1.768 -1.767 a2 2 0 0 0 2.828 -2.829 l-2.828 -2.828 a2 2 0 0 0 -2.829 2.828 l-1.767 -1.768 a2 2 0 1 0 -2.829 2.829 z", "m2.5 21.5 1.4 -1.4", "m20.1 3.9 1.4 -1.4", "M5.343 21.485 a2 2 0 1 0 2.829 -2.828 l1.767 1.768 a2 2 0 1 0 2.829 -2.829 l-6.364 -6.364 a2 2 0 1 0 -2.829 2.829 l1.768 1.767 a2 2 0 0 0 -2.828 2.829 z", "m9.6 14.4 4.8 -4.8"),
        "footprints" to arrayOf("M4 16 v-2.38 C4 11.5 2.97 10.5 3 8 c.03 -2.72 1.49 -6 4.5 -6 C9.37 2 10 3.8 10 5.5 c0 3.11 -2 5.66 -2 8.68 V16 a2 2 0 1 1 -4 0 Z", "M20 20 v-2.38 c0 -2.12 1.03 -3.12 1 -5.62 -.03 -2.72 -1.49 -6 -4.5 -6 C14.63 6 14 7.8 14 9.5 c0 3.11 2 5.66 2 8.68 V20 a2 2 0 1 0 4 0 Z", "M16 17 h4", "M4 13 h4"),
        "droplets" to arrayOf("M7 16.3 c2.2 0 4 -1.83 4 -4.05 0 -1.16 -.57 -2.26 -1.71 -3.19 S7.29 6.75 7 5.3 c-.29 1.45 -1.14 2.84 -2.29 3.76 S3 11.1 3 12.25 c0 2.22 1.8 4.05 4 4.05 z", "M12.56 6.6 A10.97 10.97 0 0 0 14 3.02 c.5 2.5 2 4.9 4 6.5 s3 3.5 3 5.5 a6.98 6.98 0 0 1 -11.91 4.97"),
        "sun" to arrayOf("M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M12 2 v2", "M12 20 v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12 h2", "M20 12 h2", "m6.34 17.66 -1.41 1.41", "m19.07 4.93 -1.41 1.41"),
        "moon" to arrayOf("M20.985 12.486 a9 9 0 1 1 -9.473 -9.472 c.405 -.022 .617 .46 .402 .803 a6 6 0 0 0 8.268 8.268 c.344 -.215 .825 -.004 .803 .401"),
        "toothbrush" to arrayOf("M15 11 c-2 2 -4 2 -6 4 l-7 7", "m22 4 -7.414 7.414 -2 -2 A2 2 0 0 1 14 6 c0 -.512 .196 -1.024 .586 -1.414 A2 2 0 0 1 16 4 a2 2 0 0 1 3.262 -1.552 l2.152 2.138"),
        "bath" to arrayOf("M10 4 8 6", "M17 19 v2", "M2 12 h20", "M7 19 v2", "M9 5 7.621 3.621 A2.121 2.121 0 0 0 4 5 v12 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 v-5"),
        "apple" to arrayOf("M12 6.528 V3 a1 1 0 0 1 1 -1 h0", "M18.237 21 A15 15 0 0 0 22 11 a6 6 0 0 0 -10 -4.472 A6 6 0 0 0 2 11 a15.1 15.1 0 0 0 3.763 10 3 3 0 0 0 3.648 .648 5.5 5.5 0 0 1 5.178 0 A3 3 0 0 0 18.237 21"),
        "pill" to arrayOf("m10.5 20.5 10 -10 a4.95 4.95 0 1 0 -7 -7 l-10 10 a4.95 4.95 0 1 0 7 7 Z", "m8.5 8.5 7 7"),
        "pen" to arrayOf("M21.174 6.812 a1 1 0 0 0 -3.986 -3.987 L3.842 16.174 a2 2 0 0 0 -.5 .83 l-1.321 4.352 a.5 .5 0 0 0 .623 .622 l4.353 -1.32 a2 2 0 0 0 .83 -.497 z"),
        "laptop" to arrayOf("M18 5 a2 2 0 0 1 2 2 v8.526 a2 2 0 0 0 .212 .897 l1.068 2.127 a1 1 0 0 1 -.9 1.45 H3.62 a1 1 0 0 1 -.9 -1.45 l1.068 -2.127 A2 2 0 0 0 4 15.526 V7 a2 2 0 0 1 2 -2 z", "M20.054 15.987 H3.946"),
        "sprout" to arrayOf("M14 9.536 V7 a4 4 0 0 1 4 -4 h1.5 a.5 .5 0 0 1 .5 .5 V5 a4 4 0 0 1 -4 4 4 4 0 0 0 -4 4 c0 2 1 3 1 5 a5 5 0 0 1 -1 3", "M4 9 a5 5 0 0 1 8 4 5 5 0 0 1 -8 -4", "M5 21 h14"),
        "heart" to arrayOf("M2 9.5 a5.5 5.5 0 0 1 9.591 -3.676 .56 .56 0 0 0 .818 0 A5.49 5.49 0 0 1 22 9.5 c0 2.29 -1.5 4 -3 5.5 l-5.492 5.313 a2 2 0 0 1 -3 .019 L5 15 c-1.5 -1.5 -3 -3.2 -3 -5.5"),
        "brain" to arrayOf("M12 18 V5", "M15 13 a4.17 4.17 0 0 1 -3 -4 4.17 4.17 0 0 1 -3 4", "M17.598 6.5 A3 3 0 1 0 12 5 a3 3 0 1 0 -5.598 1.5", "M17.997 5.125 a4 4 0 0 1 2.526 5.77", "M18 18 a4 4 0 0 0 2 -7.464", "M19.967 17.483 A4 4 0 1 1 12 18 a4 4 0 1 1 -7.967 -.517", "M6 18 a4 4 0 0 1 -2 -7.464", "M6.003 5.125 a4 4 0 0 0 -2.526 5.77"),
        "music" to arrayOf("M9 18 V5 l12 -2 v13", "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0", "M15 16a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
        "phone-off" to arrayOf("M10.1 13.9 a14 14 0 0 0 3.732 2.668 1 1 0 0 0 1.213 -.303 l.355 -.465 A2 2 0 0 1 17 15 h3 a2 2 0 0 1 2 2 v3 a2 2 0 0 1 -2 2 18 18 0 0 1 -12.728 -5.272", "M22 2 2 22", "M4.76 13.582 A18 18 0 0 1 2 4 a2 2 0 0 1 2 -2 h3 a2 2 0 0 1 2 2 v3 a2 2 0 0 1 -.8 1.6 l-.468 .351 a1 1 0 0 0 -.292 1.233 14 14 0 0 0 .244 .473"),
        "timer" to arrayOf("M10 2L14 2", "M12 14L15 11", "M4 14a8 8 0 1 0 16 0a8 8 0 1 0 -16 0"),
        "coffee" to arrayOf("M10 2 v2", "M14 2 v2", "M16 8 a1 1 0 0 1 1 1 v8 a4 4 0 0 1 -4 4 H7 a4 4 0 0 1 -4 -4 V9 a1 1 0 0 1 1 -1 h14 a4 4 0 1 1 0 8 h-1", "M6 2 v2"),
        "utensils" to arrayOf("M3 2 v7 c0 1.1 .9 2 2 2 h4 a2 2 0 0 0 2 -2 V2", "M7 2 v20", "M21 15 V2 a5 5 0 0 0 -5 5 v6 c0 1.1 .9 2 2 2 h3 Zm0 0 v7"),
        "bike" to arrayOf("M15 17.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0", "M2 17.5a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0 -7 0", "M14 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "M12 17.5 V14 l-3 -3 4 -3 2 3 h2"),
        "sparkles" to arrayOf("M11.017 2.814 a1 1 0 0 1 1.966 0 l1.051 5.558 a2 2 0 0 0 1.594 1.594 l5.558 1.051 a1 1 0 0 1 0 1.966 l-5.558 1.051 a2 2 0 0 0 -1.594 1.594 l-1.051 5.558 a1 1 0 0 1 -1.966 0 l-1.051 -5.558 a2 2 0 0 0 -1.594 -1.594 l-5.558 -1.051 a1 1 0 0 1 0 -1.966 l5.558 -1.051 a2 2 0 0 0 1.594 -1.594 z", "M20 2 v4", "M22 4 h-4", "M2 20a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
        "moon-star" to arrayOf("M18 5 h4", "M20 3 v4", "M20.985 12.486 a9 9 0 1 1 -9.473 -9.472 c.405 -.022 .617 .46 .402 .803 a6 6 0 0 0 8.268 8.268 c.344 -.215 .825 -.004 .803 .401"),
        "hand" to arrayOf("M18 11 V6 a2 2 0 0 0 -2 -2 a2 2 0 0 0 -2 2", "M14 10 V4 a2 2 0 0 0 -2 -2 a2 2 0 0 0 -2 2 v2", "M10 10.5 V6 a2 2 0 0 0 -2 -2 a2 2 0 0 0 -2 2 v8", "M18 8 a2 2 0 1 1 4 0 v6 a8 8 0 0 1 -8 8 h-2 c-2.8 0 -4.5 -.86 -5.99 -2.34 l-3.6 -3.6 a2 2 0 0 1 2.83 -2.82 L7 15"),
        "flame" to arrayOf("M12 3 q1 4 4 6.5 t3 5.5 a1 1 0 0 1 -14 0 5 5 0 0 1 1 -3 1 1 0 0 0 5 0 c0 -2 -1.5 -3 -1.5 -5 q0 -2 2.5 -4"),
        "bell" to arrayOf("M10.268 21 a2 2 0 0 0 3.464 0", "M3.262 15.326 A1 1 0 0 0 4 17 h16 a1 1 0 0 0 .74 -1.673 C19.41 13.956 18 12.499 18 8 A6 6 0 0 0 6 8 c0 4.499 -1.411 5.956 -2.738 7.326"),
        "target" to arrayOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M6 12a6 6 0 1 0 12 0a6 6 0 1 0 -12 0", "M10 12a2 2 0 1 0 4 0a2 2 0 1 0 -4 0"),
        "trophy" to arrayOf("M10 14.66 V17 a1 1 0 0 1 -1 1 2 2 0 0 0 -2 2 v2", "M14 14.66 V17 a1 1 0 0 0 1 1 2 2 0 0 1 2 2 v2", "M17.916 10 H19.5 A2.5 2.5 0 0 0 22 7.5 V5 a1 1 0 0 0 -1 -1 h-3", "M4 22 h16", "M6 9 a6 6 0 0 0 12 0 V3 a1 1 0 0 0 -1 -1 H7 a1 1 0 0 0 -1 1 z", "M6.084 10 H4.5 A2.5 2.5 0 0 1 2 7.5 V5 a1 1 0 0 1 1 -1 h3"),
        "leaf" to arrayOf("M11 20 a10 10 0 0 0 10 -10 25.9 25.9 0 0 0 -1.04 -7.281 1 1 0 0 0 -1.755 -.325 C15.833 5.5 13 5.5 9.8 6.1 A7 7 0 0 0 11 20", "M2 21 a5 5 0 0 1 2.911 -4.544 C7.613 15.212 8.351 15.24 11 13"),
        "salad" to arrayOf("M7 21 h10", "M12 21 a9 9 0 0 0 9 -9 H3 a9 9 0 0 0 9 9 Z", "M11.38 12 a2.4 2.4 0 0 1 -.4 -4.77 2.4 2.4 0 0 1 3.2 -2.77 2.4 2.4 0 0 1 3.47 -.63 2.4 2.4 0 0 1 3.37 3.37 2.4 2.4 0 0 1 -1.1 3.7 2.51 2.51 0 0 1 .03 1.1", "m13 12 4 -4", "M10.9 7.25 A3.99 3.99 0 0 0 4 10 c0 .73 .2 1.41 .54 2"),
        "milk" to arrayOf("M8 2 h8", "M9 2 v2.789 a4 4 0 0 1 -.672 2.219 l-.656 .984 A4 4 0 0 0 7 10.212 V20 a2 2 0 0 0 2 2 h6 a2 2 0 0 0 2 -2 v-9.789 a4 4 0 0 0 -.672 -2.219 l-.656 -.984 A4 4 0 0 1 15 4.788 V2", "M7 15 a6.472 6.472 0 0 1 5 0 6.47 6.47 0 0 0 5 0"),
        "wind" to arrayOf("M12.8 19.6 A2 2 0 1 0 14 16 H2", "M17.5 8 a2.5 2.5 0 1 1 2 4 H2", "M9.8 4.4 A2 2 0 1 1 11 8 H2"),
        "flower-2" to arrayOf("M12 5 a3 3 0 1 1 3 3 m-3 -3 a3 3 0 1 0 -3 3 m3 -3 v1 M9 8 a3 3 0 1 0 3 3 M9 8 h1 m5 0 a3 3 0 1 1 -3 3 m3 -3 h-1 m-2 3 v-1", "M10 8a2 2 0 1 0 4 0a2 2 0 1 0 -4 0", "M12 10 v12", "M12 22 c4.2 0 7 -1.667 7 -5 -4.2 0 -7 1.667 -7 5 Z", "M12 22 c-4.2 0 -7 -1.667 -7 -5 4.2 0 7 1.667 7 5 Z"),
        "mountain" to arrayOf("m8 3 4 8 5 -5 5 15 H2 L8 3 z"),
        "zap" to arrayOf("M15.914 4 a1.5 1.5 0 0 0 -2.474 -1.561 l-9 9 A1.5 1.5 0 0 0 5.5 14 h4.002 a.5 .5 0 0 1 .471 .666 L8.086 20 a1.5 1.5 0 0 0 2.475 1.56 l9 -9 A1.5 1.5 0 0 0 18.5 10 h-3.997 a.5 .5 0 0 1 -.472 -.667 z"),
        "star" to arrayOf("M11.525 2.295 a.53 .53 0 0 1 .95 0 l2.31 4.679 a2.123 2.123 0 0 0 1.595 1.16 l5.166 .756 a.53 .53 0 0 1 .294 .904 l-3.736 3.638 a2.123 2.123 0 0 0 -.611 1.878 l.882 5.14 a.53 .53 0 0 1 -.771 .56 l-4.618 -2.428 a2.122 2.122 0 0 0 -1.973 0 L6.396 21.01 a.53 .53 0 0 1 -.77 -.56 l.881 -5.139 a2.122 2.122 0 0 0 -.611 -1.879 L2.16 9.795 a.53 .53 0 0 1 .294 -.906 l5.165 -.755 a2.122 2.122 0 0 0 1.597 -1.16 z"),
        "notebook" to arrayOf("M2 6 h4", "M2 10 h4", "M2 14 h4", "M2 18 h4", "M6 2h12a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2v-16a2 2 0 0 1 2 -2z", "M16 2 v20"),
        "pencil" to arrayOf("M21.174 6.812 a1 1 0 0 0 -3.986 -3.987 L3.842 16.174 a2 2 0 0 0 -.5 .83 l-1.321 4.352 a.5 .5 0 0 0 .623 .622 l4.353 -1.32 a2 2 0 0 0 .83 -.497 z", "m15 5 4 4"),
        "code" to arrayOf("m16 18 6 -6 -6 -6", "m8 6 -6 6 6 6"),
        "languages" to arrayOf("m5 8 6 6", "m4 14 6 -6 2 -3", "M2 5 h12", "M7 2 h1", "m22 22 -5 -10 -5 10", "M14 18 h6"),
        "graduation-cap" to arrayOf("M21.42 10.922 a1 1 0 0 0 -.019 -1.838 L12.83 5.18 a2 2 0 0 0 -1.66 0 L2.6 9.08 a1 1 0 0 0 0 1.832 l8.57 3.908 a2 2 0 0 0 1.66 0 z", "M22 10 v6", "M6 12.5 V16 a6 3 0 0 0 12 0 v-3.5"),
        "wallet" to arrayOf("M19 7 V4 a1 1 0 0 0 -1 -1 H5 a2 2 0 0 0 0 4 h15 a1 1 0 0 1 1 1 v4 h-3 a2 2 0 0 0 0 4 h3 a1 1 0 0 0 1 -1 v-2 a1 1 0 0 0 -1 -1", "M3 5 v14 a2 2 0 0 0 2 2 h15 a1 1 0 0 0 1 -1 v-4"),
        "piggy-bank" to arrayOf("M11 17 h3 v2 a1 1 0 0 0 1 1 h2 a1 1 0 0 0 1 -1 v-3 a3.16 3.16 0 0 0 2 -2 h1 a1 1 0 0 0 1 -1 v-2 a1 1 0 0 0 -1 -1 h-1 a5 5 0 0 0 -2 -4 V3 a4 4 0 0 0 -3.2 1.6 l-.3 .4 H11 a6 6 0 0 0 -6 6 v1 a5 5 0 0 0 2 4 v3 a1 1 0 0 0 1 1 h2 a1 1 0 0 0 1 -1 z", "M16 10 h.01", "M2 8 v1 a2 2 0 0 0 2 2 h1"),
        "house" to arrayOf("M15 21 v-8 a1 1 0 0 0 -1 -1 h-4 a1 1 0 0 0 -1 1 v8", "M3 10 a2 2 0 0 1 .709 -1.528 l7 -6 a2 2 0 0 1 2.582 0 l7 6 A2 2 0 0 1 21 10 v9 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 z"),
        "shower-head" to arrayOf("m4 4 2.5 2.5", "M13.5 6.5 a4.95 4.95 0 0 0 -7 7", "M15 5 5 15", "M14 17 v.01", "M10 16 v.01", "M13 13 v.01", "M16 10 v.01", "M11 20 v.01", "M17 14 v.01", "M20 11 v.01"),
        "snowflake" to arrayOf("m10 20 -1.25 -2.5 L6 18", "M10 4 8.75 6.5 6 6", "m14 20 1.25 -2.5 L18 18", "m14 4 1.25 2.5 L18 6", "m17 21 -3 -6 h-4", "m17 3 -3 6 1.5 3", "M2 12 h6.5 L10 9", "m20 10 -1.5 2 1.5 2", "M22 12 h-6.5 L14 15", "m4 10 1.5 2 L4 14", "m7 21 3 -6 -1.5 -3", "m7 3 3 6 h4"),
        "clock" to arrayOf("M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0", "M12 6 v6 l4 2"),
        "alarm-clock" to arrayOf("M4 13a8 8 0 1 0 16 0a8 8 0 1 0 -16 0", "M12 9 v4 l2 2", "M5 3 2 6", "m22 6 -3 -3", "M6.38 18.7 4 21", "M17.64 18.67 20 21"),
        "sofa" to arrayOf("M20 9 V6 a2 2 0 0 0 -2 -2 H6 a2 2 0 0 0 -2 2 v3", "M2 16 a2 2 0 0 0 2 2 h16 a2 2 0 0 0 2 -2 v-5 a2 2 0 0 0 -4 0 v1.5 a.5 .5 0 0 1 -.5 .5 h-11 a.5 .5 0 0 1 -.5 -.5 V11 a2 2 0 0 0 -4 0 z", "M4 18 v2", "M20 18 v2", "M12 4 v9"),
        "brush-cleaning" to arrayOf("m16 22 -1 -4", "M19 14 a1 1 0 0 0 1 -1 v-1 a2 2 0 0 0 -2 -2 h-3 a1 1 0 0 1 -1 -1 V4 a2 2 0 0 0 -4 0 v5 a1 1 0 0 1 -1 1 H6 a2 2 0 0 0 -2 2 v1 a1 1 0 0 0 1 1", "M19 14 H5 l-1.973 6.767 A1 1 0 0 0 4 22 h16 a1 1 0 0 0 .973 -1.233 z", "m8 22 1 -4"),
        "shirt" to arrayOf("M20.38 3.46 16 2 a4 4 0 0 1 -8 0 L3.62 3.46 a2 2 0 0 0 -1.34 2.23 l.58 3.47 a1 1 0 0 0 .99 .84 H6 v10 c0 1.1 .9 2 2 2 h8 a2 2 0 0 0 2 -2 V10 h2.15 a1 1 0 0 0 .99 -.84 l.58 -3.47 a2 2 0 0 0 -1.34 -2.23 z"),
        "heart-pulse" to arrayOf("M2 9.5 a5.5 5.5 0 0 1 9.591 -3.676 .56 .56 0 0 0 .818 0 A5.49 5.49 0 0 1 22 9.5 c0 2.29 -1.5 4 -3 5.5 l-5.492 5.313 a2 2 0 0 1 -3 .019 L5 15 c-1.5 -1.5 -3 -3.2 -3 -5.5", "M3.22 13 H9.5 l.5 -1 2 4.5 2 -7 1.5 3.5 h5.27"),
        "activity" to arrayOf("M22 12 h-2.48 a2 2 0 0 0 -1.93 1.46 l-2.35 8.36 a.25 .25 0 0 1 -.48 0 L9.24 2.18 a.25 .25 0 0 0 -.48 0 l-2.35 8.36 A2 2 0 0 1 4.49 12 H2"),
        "scale" to arrayOf("M12 3 v18", "m19 8 3 8 a5 5 0 0 1 -6 0 zV7", "M3 7 h1 a17 17 0 0 0 8 -2 17 17 0 0 0 8 2 h1", "m5 8 3 8 a5 5 0 0 1 -6 0 zV7", "M7 21 h10"),
        "ruler" to arrayOf("M21.3 15.3 a2.4 2.4 0 0 1 0 3.4 l-2.6 2.6 a2.4 2.4 0 0 1 -3.4 0 L2.7 8.7 a2.41 2.41 0 0 1 0 -3.4 l2.6 -2.6 a2.41 2.41 0 0 1 3.4 0 Z", "m14.5 12.5 2 -2", "m11.5 9.5 2 -2", "m8.5 6.5 2 -2", "m17.5 15.5 2 -2"),
        "headphones" to arrayOf("M3 14 h3 a2 2 0 0 1 2 2 v3 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-7 a9 9 0 0 1 18 0 v7 a2 2 0 0 1 -2 2 h-1 a2 2 0 0 1 -2 -2 v-3 a2 2 0 0 1 2 -2 h3"),
        "eye" to arrayOf("M2.062 12.348 a1 1 0 0 1 0 -.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1 -19.876 0", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
        "camera" to arrayOf("M13.997 4 a2 2 0 0 1 1.76 1.05 l.486 .9 A2 2 0 0 0 18.003 7 H20 a2 2 0 0 1 2 2 v9 a2 2 0 0 1 -2 2 H4 a2 2 0 0 1 -2 -2 V9 a2 2 0 0 1 2 -2 h1.997 a2 2 0 0 0 1.759 -1.048 l.489 -.904 A2 2 0 0 1 10.004 4 z", "M9 13a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"),
        "palette" to arrayOf("M12 22 a1 1 0 0 1 0 -20 10 9 0 0 1 10 9 5 5 0 0 1 -5 5 h-2.25 a1.75 1.75 0 0 0 -1.4 2.8 l.3 .4 a1.75 1.75 0 0 1 -1.4 2.8 z", "M13 6.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", "M17 10.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", "M6 12.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0", "M8 7.5a0.5 0.5 0 1 0 1 0a0.5 0.5 0 1 0 -1 0"),
        "gamepad-2" to arrayOf("M6 11L10 11", "M8 9L8 13", "M15 12L15.01 12", "M18 10L18.01 10", "M17.32 5 H6.68 a4 4 0 0 0 -3.978 3.59 c-.006 .052 -.01 .101 -.017 .152 C2.604 9.416 2 14.456 2 16 a3 3 0 0 0 3 3 c1 0 1.5 -.5 2 -1 l1.414 -1.414 A2 2 0 0 1 9.828 16 h4.344 a2 2 0 0 1 1.414 .586 L17 18 c.5 .5 1 1 2 1 a3 3 0 0 0 3 -3 c0 -1.545 -.604 -6.584 -.685 -7.258 -.007 -.05 -.011 -.1 -.017 -.151 A4 4 0 0 0 17.32 5 z"),
        "sunrise" to arrayOf("M12 2 v8", "m4.93 10.93 1.41 1.41", "M2 18 h2", "M20 18 h2", "m19.07 10.93 -1.41 1.41", "M22 22 H2", "m8 6 4 -4 4 4", "M16 18 a4 4 0 0 0 -8 0"),
        "sunset" to arrayOf("M12 10 V2", "m4.93 10.93 1.41 1.41", "M2 18 h2", "M20 18 h2", "m19.07 10.93 -1.41 1.41", "M22 22 H2", "m16 6 -4 4 -4 -4", "M16 18 a4 4 0 0 0 -8 0"),
        "cloud-sun" to arrayOf("M12 2 v2", "m4.93 4.93 1.41 1.41", "M20 12 h2", "m19.07 4.93 -1.41 1.41", "M15.947 12.65 a4 4 0 0 0 -5.925 -4.128", "M13 22 H7 a5 5 0 1 1 4.9 -6 H13 a3 3 0 0 1 0 6 Z"),
        "landmark" to arrayOf("M10 18 v-7", "M11.119 2.205 a2 2 0 0 1 1.762 0 l7.84 3.846 A.5 .5 0 0 1 20.5 7 h-17 a.5 .5 0 0 1 -.22 -.949 z", "M14 18 v-7", "M18 18 v-7", "M3 22 h18", "M6 18 v-7"),
        "drumstick" to arrayOf("M15.4 15.63 a7.875 6 135 1 1 6.23 -6.23 4.5 3.43 135 0 0 -6.23 6.23", "m8.29 12.71 -2.6 2.6 a2.5 2.5 0 1 0 -1.65 4.65 A2.5 2.5 0 1 0 8.7 18.3 l2.59 -2.59"),
        "lightbulb" to arrayOf("M15 14 c.2 -1 .7 -1.7 1.5 -2.5 1 -.9 1.5 -2.2 1.5 -3.5 A6 6 0 0 0 6 8 c0 1 .2 2.2 1.5 3.5 .7 .7 1.3 1.5 1.5 2.5", "M9 18 h6", "M10 22 h4"),
        "rocket" to arrayOf("M12 15 v5 s3.03 -.55 4 -2 c1.08 -1.62 0 -5 0 -5", "M4.5 16.5 c-1.5 1.26 -2 5 -2 5 s3.74 -.5 5 -2 c.71 -.84 .7 -2.13 -.09 -2.91 a2.18 2.18 0 0 0 -2.91 -.09", "M9 12 a22 22 0 0 1 2 -3.95 A12.88 12.88 0 0 1 22 2 c0 2.72 -.78 7.5 -6 11 a22.4 22.4 0 0 1 -4 2 z", "M9 12 H4 s.55 -3.03 2 -4 c1.62 -1.08 5 .05 5 .05"),
        "calendar-check" to arrayOf("M8 2 v3", "M16 2 v3", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z", "M3 9 h18", "m9 15 2 2 4 -4"),
        "tree-pine" to arrayOf("m17 14 3 3.3 a1 1 0 0 1 -.7 1.7 H4.7 a1 1 0 0 1 -.7 -1.7 L7 14 h-.3 a1 1 0 0 1 -.7 -1.7 L9 9 h-.2 A1 1 0 0 1 8 7.3 L12 3 l4 4.3 a1 1 0 0 1 -.8 1.7 H15 l3 3.3 a1 1 0 0 1 -.7 1.7 H17 Z", "M12 22 v-3"),
        "link" to arrayOf("M10 13 a5 5 0 0 0 7.54 .54 l3 -3 a5 5 0 0 0 -7.07 -7.07 l-1.72 1.71", "M14 11 a5 5 0 0 0 -7.54 -.54 l-3 3 a5 5 0 0 0 7.07 7.07 l1.71 -1.71"),
        "inbox" to arrayOf("M22 12L16 12L14 15L10 15L8 12L2 12", "M5.45 5.11 2 12 v6 a2 2 0 0 0 2 2 h16 a2 2 0 0 0 2 -2 v-6 l-3.45 -6.89 A2 2 0 0 0 16.76 4 H7.24 a2 2 0 0 0 -1.79 1.11 z"),
        "book" to arrayOf("M4 19.5 v-15 A2.5 2.5 0 0 1 6.5 2 H19 a1 1 0 0 1 1 1 v18 a1 1 0 0 1 -1 1 H6.5 a1 1 0 0 1 0 -5 H20"),
        "person-standing" to arrayOf("M11 5a1 1 0 1 0 2 0a1 1 0 1 0 -2 0", "m9 20 3 -6 3 6", "m6 8 6 2 6 -2", "M12 10 v4"),
        "check" to arrayOf("M20 6 9 17 l-5 -5"),
    )

    /** Every icon the picker offers, in order. */
    val names: List<String> = PATHS.keys.toList()

    private val cache = HashMap<String, ImageVector>()

    /** The icon for [name]; an unknown name draws the sprout (a new habit). */
    @Synchronized
    fun vector(name: String): ImageVector {
        val key = if (name in PATHS) name else "sprout"
        return cache.getOrPut(key) {
            val b = ImageVector.Builder(name = key, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            for (d in PATHS.getValue(key)) {
                b.addPath(
                    pathData = PathParser().parsePathString(d).toNodes(),
                    stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
                )
            }
            b.build()
        }
    }

    /** The old picture keys (Fluent emoji files) and the nearest line icon for each. */
    val OLD_KEYS: Map<String, String> = mapOf(
        "coffee" to "coffee", "water" to "glass-water", "food" to "utensils", "salad" to "salad", "chicken" to "drumstick",
        "apple" to "apple", "milk" to "milk", "gym" to "dumbbell", "biceps" to "dumbbell", "lotus" to "flower-2",
        "shoe" to "footprints", "walk" to "person-standing", "footprints" to "footprints", "book" to "book-open", "books" to "book",
        "inbox" to "inbox", "memo" to "pen", "brain" to "brain", "laptop" to "laptop", "headphone" to "headphones",
        "bulb" to "lightbulb", "moon" to "moon", "bed" to "bed", "sleeping" to "moon-star", "phone_off" to "phone-off",
        "toothbrush" to "toothbrush", "shower" to "shower-head", "pill" to "pill", "scale" to "scale", "ruler" to "ruler",
        "broom" to "brush-cleaning", "money" to "wallet", "calendar" to "calendar-check", "alarm" to "alarm-clock",
        "stopwatch" to "timer", "target" to "target", "rocket" to "rocket", "sunrise" to "sunrise", "sun" to "sun",
        "sun_cloud" to "sun-dim", "sunset" to "sunset", "night" to "moon", "mosque" to "landmark", "seedling" to "sprout",
        "plant" to "leaf", "tree" to "tree-pine", "tulip" to "flower-2", "fire" to "flame", "ice" to "snowflake",
        "snowflake" to "snowflake", "link" to "link", "trophy" to "trophy", "check" to "check", "party" to "sparkles",
        "sparkles" to "sparkles",
    )

    /** An old emoji key, or a name already in the set, as a set name; anything else is the sprout. */
    fun forEmoji(key: String?): String {
        val k = key?.substringBefore('/')?.trim().orEmpty()
        return when {
            k in PATHS -> k
            else -> OLD_KEYS[k] ?: "sprout"
        }
    }

    /** The habit's icon name: Boss's own pick, else what its title is about (Emoji's keyword table), else the sprout. */
    fun keyFor(title: String, chosen: String? = null): String {
        val pick = chosen?.substringBefore('/')?.trim()
        if (pick != null && pick in PATHS) return pick
        return forEmoji(Emoji.keyFor(title, chosen))
    }
}
