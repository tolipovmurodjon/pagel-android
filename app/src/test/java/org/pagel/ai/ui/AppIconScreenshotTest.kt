package org.pagel.ai.ui

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pagel.ai.R
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Round D 0b: PAGEL's launcher icon is the mint glass P (never the default robot), drawn from the real adaptive icon at
 * 48 and 108 dp, round and squircle masks, on a light and a dark wallpaper, plus the themed (monochrome) layer.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xxhdpi")
class AppIconScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theLauncherIconOnLightAndDarkWallpapers() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val icon = ctx.getDrawable(R.mipmap.ic_launcher)!!
        assertTrue("an adaptive icon, not a bitmap robot", icon is AdaptiveIconDrawable)
        val full = icon.toBitmap(324, 324).asImageBitmap()
        val fg = ctx.getDrawable(R.drawable.ic_launcher_foreground)!!.toBitmap(324, 324).asImageBitmap()
        val bg = ctx.getDrawable(R.drawable.ic_launcher_background)!!.toBitmap(324, 324).asImageBitmap()
        val mono = ctx.getDrawable(R.drawable.ic_launcher_monochrome)!!.toBitmap(324, 324).asImageBitmap()
        compose.setContent {
            Column {
                for (wall in listOf(Brush.linearGradient(listOf(Color(0xFFF4F1EA), Color(0xFFDDE7F0))), Brush.linearGradient(listOf(Color(0xFF1B2333), Color(0xFF0B0F18))))) {
                    Row(Modifier.background(wall).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Squircle (Samsung) and circle masks at 108 dp and 48 dp.
                        Box(Modifier.size(108.dp).clip(RoundedCornerShape(30))) { Image(bg, null, Modifier.size(108.dp)); Image(fg, null, Modifier.size(108.dp)) }
                        Box(Modifier.size(48.dp).clip(RoundedCornerShape(30))) { Image(full, null, Modifier.size(48.dp)) }
                        Box(Modifier.size(48.dp).clip(CircleShape)) { Image(full, null, Modifier.size(48.dp)) }
                        // Android 13 themed icon: the launcher fills the mask with the wallpaper's tone.
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFD7E8DF))) {
                            Image(mono, null, Modifier.size(48.dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color(0xFF0B5C45)))
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/app-icon.png")
    }
}
