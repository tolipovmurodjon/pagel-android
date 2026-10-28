package org.pagel.ai.ui

import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.data.compass.Goal
import org.pagel.ai.data.compass.GoalBody
import org.pagel.ai.data.compass.Level
import org.pagel.ai.data.compass.VaultState
import org.pagel.ai.ui.compass.CompassTodayLine
import org.pagel.ai.ui.compass.CompassUi
import org.pagel.ai.ui.compass.Draft
import org.pagel.ai.ui.compass.GoalForm
import org.pagel.ai.ui.compass.RecoveryForm
import org.pagel.ai.ui.glass.GlassSheet
import org.pagel.ai.ui.glass.GlassTheme
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The compass checker's window leaks (2026-10-29): CI shot 108 showed a goal in the project page's picker, because
 * Material's sheet copied the plain window's flags. Every Compass sheet's own window is FLAG_SECURE; Today's window is
 * secure while the quiet line shows; the keyboard gets "do not learn" and the passphrase the password type.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CompassSecurityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun dialogWindow(v: View): Window? {
        var p: android.view.ViewParent? = v.parent
        while (p != null) {
            (p as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { return it }
            p = p.parent
        }
        return null
    }

    private fun secure(w: Window) = w.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun aCompassSheetIsSecureOverAPlainPage() {
        var inside: View? = null
        compose.setContent { GlassTheme { GlassSheet(onDismiss = {}, secure = true) { inside = LocalView.current; Text("sealed words") } } }
        compose.waitForIdle()
        val w = dialogWindow(inside!!)
        assertNotNull("the sheet has its own window", w)
        assertTrue("the sheet's window is FLAG_SECURE", secure(w!!))
        assertTrue("the page under it stays as it was", !secure(compose.activity.window))
    }

    @Test fun todaysWindowIsSecureOnlyWhileTheLineShows() {
        var on by mutableStateOf(true)
        compose.setContent { GlassTheme { if (on) CompassTodayLine("Find every child's gift") } }
        compose.waitForIdle()
        assertTrue(secure(compose.activity.window))
        on = false
        compose.waitForIdle()
        assertTrue(!secure(compose.activity.window))
    }

    private val ui = CompassUi(state = VaultState.OPEN, today = LocalDate.parse("2026-11-03"))

    /** The keyboard request a focused field sends: read off the view after Compose started its input session. */
    private fun editorInfoAfterTap(description: String): EditorInfo? {
        var root: View? = null
        compose.setContent { GlassTheme { root = LocalView.current; content() } }
        compose.onNodeWithContentDescription(description).performClick()
        compose.waitForIdle()
        val info = EditorInfo()
        return if (root?.onCreateInputConnection(info) != null) info else null
    }

    private var content: @androidx.compose.runtime.Composable () -> Unit = {}

    @Test fun aGoalFieldTellsTheKeyboardNotToLearn() {
        content = { GoalForm(Draft(Goal("", Level.PURPOSE, null, null, null, GoalBody()), emptyList()), ui, onSave = { _, _ -> }) }
        val info = editorInfoAfterTap("Why I live")
        assumeTrue("Robolectric gave no input connection for the focused field", info != null)
        assertTrue(info!!.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }

    @Test fun thePassphraseIsAPasswordFieldThatDoesNotLearn() {
        content = { RecoveryForm(on = false, onSet = {}, onOff = {}) }
        val info = editorInfoAfterTap("Passphrase")
        assumeTrue("Robolectric gave no input connection for the focused field", info != null)
        assertTrue(info!!.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, info.inputType and android.text.InputType.TYPE_MASK_VARIATION)
    }
}
