package org.pagel.ai.ui.capture

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.pagel.ai.data.inbox.InboxRepository
import javax.inject.Inject

/**
 * The share target (Inbox v3): "Share > PAGEL" from any app lands a link or text in the Inbox, through the same outbox as
 * a typed capture, so it works offline. No screen of its own; a short platform toast says what happened.
 */
@AndroidEntryPoint
class ShareActivity : ComponentActivity() {
    @Inject lateinit var inbox: InboxRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A malformed extra from another app must never crash the share: it simply saves nothing.
        val shared = runCatching { parseShare(intent.action, intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)) }.getOrNull()
        val app = applicationContext
        if (shared == null) {
            Toast.makeText(app, "Nothing to save. Only text and links go to the Inbox for now.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        lifecycleScope.launch {
            inbox.capture(listOf(shared.text), shared.source, hold = false)
            Toast.makeText(app, "Saved to Inbox", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
