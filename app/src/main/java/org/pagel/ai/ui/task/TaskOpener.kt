package org.pagel.ai.ui.task

import androidx.compose.runtime.staticCompositionLocalOf

/** Opens the task panel for a task id from any row in the app; the shell holds which task is open. */
val LocalTaskOpener = staticCompositionLocalOf<(String) -> Unit> { {} }

/** Opens Focus mode (core round 4) for a task id from any screen; the shell holds which task is in focus. */
val LocalFocusOpener = staticCompositionLocalOf<(String) -> Unit> { {} }
