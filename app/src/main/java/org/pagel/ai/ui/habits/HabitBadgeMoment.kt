package org.pagel.ai.ui.habits

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.pagel.ai.data.habits.HabitBadge
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MOMENT_DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

/** The picture that is shared: always 1080 x 1350 (4:5), whatever the phone's density. */
internal const val SHARE_W = 1080
internal const val SHARE_H = 1350

internal fun scaledForShare(source: Bitmap): Bitmap = Bitmap.createScaledBitmap(source, SHARE_W, SHARE_H, true)

/** The share file's content:// address, from the app's own FileProvider (read access is granted per share). */
internal fun shareUriFor(ctx: Context, file: File) = FileProvider.getUriForFile(ctx, "${ctx.packageName}.shares", file)

/**
 * A won badge as a full-screen moment (Boss 2026-10-31): our drawn badge large on a soft glass background, its name, the day it
 * was achieved, a gentle spring in, and two actions: Share (a clean 1080 x 1350 card through the Android share sheet) and
 * Save image (Pictures/PAGEL, no storage permission needed). The card you see is the picture that is shared.
 */
@Composable
internal fun BadgeMoment(b: HabitBadge, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val spring by animateFloatAsState(if (entered) 1f else 0.88f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "moment")
    suspend fun card(): Bitmap = scaledForShare(layer.toImageBitmap().asAndroidBitmap())
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFFD8F3E8), Color(0xFFBFE6F2))))) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(300.dp, 375.dp).scale(spring).graphicsLayer { alpha = spring.coerceIn(0f, 1f) }
                        .drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) },
                ) { ShareCard(b) }
                Spacer(Modifier.weight(1f))
                Row(Modifier.fillMaxWidth().padding(bottom = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    MomentButton("Share", TabIcons.ArrowUpRight, filled = true, Modifier.weight(1f)) {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) { saveToCache(ctx, card()) }
                            val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, shareUriFor(ctx, file))
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                        }
                    }
                    MomentButton("Save image", TabIcons.Check, filled = false, Modifier.weight(1f)) {
                        scope.launch { withContext(Dispatchers.IO) { saveToPictures(ctx, card()) } }
                    }
                }
                MomentButton("Close", TabIcons.ArrowLeft, filled = false, Modifier.fillMaxWidth().padding(bottom = Space.lg), onClose)
            }
        }
    }
}

/** The 4:5 card: badge, name, the day, a small wordmark. No emoji; our fonts. */
@Composable
internal fun ShareCard(b: HabitBadge) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White, Color(0xFFE3F4EC)))).padding(Space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        BadgeArt(b.kind, b.n, b.earned, 180.dp)
        Spacer(Modifier.height(Space.lg))
        Text(b.title, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Glass.Ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Space.xs))
        Text(b.earnedOn?.let { "Achieved on ${it.format(MOMENT_DATE)}" } ?: "Not yet", style = MaterialTheme.typography.titleSmall, color = Glass.PrimaryInk)
        Spacer(Modifier.weight(1f))
        Text("PAGEL", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = androidx.compose.ui.unit.TextUnit(2f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Glass.Muted)
    }
}

@Composable
private fun MomentButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Row(
        modifier.height(56.dp).pressScale(press).background(if (filled) Glass.Primary else Color.White.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape)
            .clickable(press, indication = null, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(icon, null, tint = if (filled) Color.White else Glass.Ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.sm))
        Text(label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold), color = if (filled) Color.White else Glass.Ink, maxLines = 1)
    }
}

private fun saveToCache(ctx: Context, bmp: Bitmap): File {
    val dir = File(ctx.cacheDir, "shares").apply { mkdirs() }
    return File(dir, "badge.png").also { f -> f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
}

private fun saveToPictures(ctx: Context, bmp: Bitmap) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "pagel-badge-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PAGEL")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
    ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
}
