package org.pagel.ai.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * The app's one sheet (Add, task panel, every picker): a transparent Material sheet whose body is the page itself, the
 * mint aurora, so the glass panes inside bend real colour (rules/liquid-glass-navbar). A light ink scrim (18 %), a
 * capsule handle, a 28 dp top, and the toast line while it is open: above a short sheet, at the foot of a full one.
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
        // The scrim dims the page only behind a half sheet; a full sheet IS the page (Boss 2026-10-28: the 18 % ink
        // turned the status bar grey above the full task panel).
        scrimColor = if (full) Color.Transparent else Glass.Ink.copy(alpha = 0.18f),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
    ) {
        SheetSystemBars()
        // A full sheet's aurora runs under the status bar and the nav bar (edge to edge); its content keeps clear of both.
        Box(Modifier.fillMaxWidth().then(if (full) Modifier else Modifier.statusBarsPadding())) {
            // [full]: the sheet takes the whole height from its first frame. Material's sheet keeps the height it opened
            // with when its content grows later (CI 40546542662: the task panel stayed half open, Priority off screen).
            AuroraBackground(Modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight() else Modifier).clip(SheetShape)) {
                Column(modifier.fillMaxWidth().then(if (full) Modifier.fillMaxHeight().statusBarsPadding() else Modifier).navigationBarsPadding().imePadding()) {
                    Handle()
                    content()
                }
            }
            if (toasts.sheets == level) {
                if (full) {
                    ToastHost(toasts, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(horizontal = Space.gutter, vertical = Space.md))
                } else {
                    // Above the sheet's top edge, in the dimmed page (checker 2026-10-27: "Added · …" covered the Add
                    // sheet's chips). A popup, because the sheet clips its own content to its shape.
                    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(0.dp)) {
                        Popup(popupPositionProvider = AboveTheSheet, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                            ToastHost(toasts, Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.md))
                        }
                    }
                }
            }
        }
    }
}

/** Puts a popup's bottom on its anchor's top: the toast sits just above an open sheet. */
private object AboveTheSheet : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2, anchorBounds.top - popupContentSize.height)
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

/**
 * The sheet's own dialog window: transparent status and navigation bars, no contrast scrim on the nav bar, dark icons
 * on the light page (RULES.md "Layout" 4: system bars show the page's own background, never a painted band; Boss
 * 2026-10-28: a solid white band sat under the panel at the nav bar).
 */
@Composable
private fun SheetSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    DisposableEffect(view) {
        var p: android.view.ViewParent? = view.parent
        var window: android.view.Window? = null
        while (p != null && window == null) { window = (p as? androidx.compose.ui.window.DialogWindowProvider)?.window; p = p.parent }
        window?.let { w ->
            @Suppress("DEPRECATION")
            run { w.statusBarColor = android.graphics.Color.TRANSPARENT; w.navigationBarColor = android.graphics.Color.TRANSPARENT }
            if (android.os.Build.VERSION.SDK_INT >= 29) { w.isNavigationBarContrastEnforced = false; w.isStatusBarContrastEnforced = false }
            androidx.core.view.WindowCompat.getInsetsController(w, w.decorView).apply { isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true }
        }
        onDispose {}
    }
}
