package org.pagel.ai.ui.compass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * FLAG_SECURE on the window that shows this composable (the activity, or a sheet's own dialog window): no screenshot,
 * no screen recording, a blank thumbnail in Recents. Counted per window, so a Compass sheet over the Compass screen
 * never clears the screen's flag when it closes.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.window()
        if (window != null) {
            val n = (secured[window] ?: 0) + 1
            secured[window] = n
            if (n == 1) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            if (window != null) {
                val n = (secured[window] ?: 1) - 1
                if (n <= 0) { secured.remove(window); window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } else secured[window] = n
            }
        }
    }
}

private val secured = java.util.WeakHashMap<Window, Int>()

private fun View.window(): Window? {
    var p: android.view.ViewParent? = parent
    while (p != null) {
        (p as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { return it }
        p = p.parent
    }
    return context.findActivity()?.window
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * The 60 s auto-lock: while this composable is on screen and the app is in front, the key stays; leaving the Compass
 * or sending PAGEL to the background starts the timer ([onLeave]); coming back in time stops it ([onStay]).
 */
@Composable
fun AutoLock(onLeave: () -> Unit, onStay: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        onStay()
        val watch = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) onLeave()
            if (e == Lifecycle.Event.ON_START) onStay()
        }
        owner.lifecycle.addObserver(watch)
        onDispose {
            owner.lifecycle.removeObserver(watch)
            onLeave()
        }
    }
}

/** What the system unlock answered. */
sealed interface UnlockAnswer {
    data object Ok : UnlockAnswer
    data object Cancelled : UnlockAnswer
    data class Failed(val why: String) : UnlockAnswer
}

/**
 * Android's own unlock sheet: fingerprint, or the screen-lock PIN/pattern/password instead. The Keystore guard key
 * opens for 60 s after it. Never shows goal words.
 */
fun askUnlock(activity: Activity, title: String, answer: (UnlockAnswer) -> Unit) {
    val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val prompt = BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setSubtitle("Only you can open it")
        .setAllowedAuthenticators(allowed)
        .build()
    prompt.authenticate(
        CancellationSignal(),
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = answer(UnlockAnswer.Ok)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = answer(
                if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) UnlockAnswer.Cancelled
                else UnlockAnswer.Failed(errString.toString()),
            )
        },
    )
}
