package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * The app's one sheet (Add, task panel, every picker): a transparent Material sheet whose body is the page itself, the
 * mint aurora, so the glass panes inside bend real colour (rules/liquid-glass-navbar). A light ink scrim (18 %), a
 * capsule handle, a 28 dp top, and the toast line on top of it while it is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier, full: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val toasts = LocalToasts.current
    // This sheet's place in the stack: only the top sheet draws the toast.
    val level = remember { toasts.sheets + 1 }
    DisposableEffect(Unit) {
        toasts.sheets += 1
        onDispose { toasts.sheets -= 1 }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = SheetShape,
        containerColor = Color.Transparent,
        contentColor = Glass.Ink,
        tonalElevation = 0.dp,
        scrimColor = Glass.Ink.copy(alpha = 0.18f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        Box(Modifier.fillMaxWidth().statusBarsPadding()) {
            // [full]: the sheet takes the whole height from its first frame. Material's sheet keeps the height it opened
            // with when its content grows later (CI 40546542662: the task panel stayed half open, Priority off screen).
            AuroraBackground(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier).clip(SheetShape)) {
                Column(modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier).navigationBarsPadding().imePadding()) {
                    Handle()
                    content()
                }
            }
            if (toasts.sheets == level) {
                ToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun Handle() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 5.dp).background(Glass.Ink.copy(alpha = 0.18f), RoundedCornerShape(3.dp)))
    }
}

/** Space a sheet keeps under its last row (the system bar is already padded). */
val SheetBottom = 20.dp

/** A sheet section's small mono heading, like the web's tp-steps h3. */
@Composable
fun SheetLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(text.uppercase(), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = Glass.Muted, modifier = modifier.padding(start = 4.dp, bottom = 6.dp))
}
