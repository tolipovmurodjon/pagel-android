package org.pagel.ai.ui.task

import androidx.compose.runtime.staticCompositionLocalOf

/** Opens the task panel for a task id from any row in the app; the shell holds which task is open. */
val LocalTaskOpener = staticCompositionLocalOf<(String) -> Unit> { {} }
