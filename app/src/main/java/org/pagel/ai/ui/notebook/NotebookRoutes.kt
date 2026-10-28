package org.pagel.ai.ui.notebook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Notebook → Folders: every vault folder with its allow switch (Phase 6b). */
@Serializable data object FoldersRoute : NavKey
/** One open folder's notes. */
@Serializable data class FolderNotesRoute(val folder: String) : NavKey
/** One note in the native reader. */
@Serializable data class NoteRoute(val path: String) : NavKey
/** The writer: [path] empty = a new note made from [template] ("", "daily" or "book"); else that note, edited. */
@Serializable data class WriteRoute(val path: String = "", val template: String = "") : NavKey
/** Review 5: the recall cards due, one at a time. */
@Serializable data object RecallRoute : NavKey
/** One saved resource from pagel.uz (a search result), read in the same reader. */
@Serializable data class ResourceRoute(val id: String) : NavKey
/** The search box opened from Today's header (the same screen as the Notebook, search first). */
@Serializable data object SearchRoute : NavKey

/** Where the Notebook's screens lead: the shell pushes [open]'s route on the tab's own stack; [back] pops one. */
class NotebookNav(val open: (NavKey) -> Unit = {}, val back: () -> Unit = {})

/** Lucide icons the Notebook needs (ISC License, Copyright Lucide Icons and Contributors), 24×24, 2 px round strokes. */
object NotebookIcons {
    val PenLine = icon("pen-line", "M12 20h9", "M16.376 3.622a1 1 0 0 1 3.002 3.004L7.368 18.635a2 2 0 0 1-.855.506l-2.872.838a.5.5 0 0 1-.62-.62l.838-2.872a2 2 0 0 1 .506-.854z")
    val Link = icon("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")
    val Image = icon("image", "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M7 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
    val Layers = icon("layers", "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83z", "M2 12a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 12", "M2 17a1 1 0 0 0 .58.91l8.6 3.91a2 2 0 0 0 1.65 0l8.58-3.9A1 1 0 0 0 22 17")
    val Lock = icon("lock", "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (d in paths) {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
