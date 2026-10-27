package org.pagel.ai.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import com.kyant.shapes.Capsule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import org.pagel.ai.ui.add.AddTaskSheet
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.pagel.ai.data.account.AccountState
import org.pagel.ai.ui.glass.AuroraBackground
import org.pagel.ai.ui.glass.Glass
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.LiquidBottomTab
import org.pagel.ai.ui.glass.LiquidBottomTabs
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.pair.PairRoute
import org.pagel.ai.ui.projects.ProjectsRoute
import org.pagel.ai.ui.tasks.TasksScreen
import org.pagel.ai.ui.today.TodayRoute as TodayScreenRoute

/** Height of the glass bar plus its gap above the system bar, so lists end above it, not under it. */
private val BarSpace = 64.dp + 16.dp

/** The native app's root (NATIVE-PLAN Phase 1): pair once, then five tabs on a real liquid-glass bar. */
@Composable
fun AppShell(onOpenPhone: () -> Unit, vm: ShellViewModel = hiltViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    GlassTheme {
        when (account) {
            // Reading the account off Main takes a few ms; the page colour paints at once, no white flash.
            null -> AuroraBackground(Modifier.fillMaxSize()) {}
            AccountState.SignedOut -> PairRoute()
            is AccountState.Paired -> Tabs(onOpenPhone = onOpenPhone, onDisconnect = vm::disconnect)
        }
    }
}

@Composable
private fun Tabs(onOpenPhone: () -> Unit, onDisconnect: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var reselect by remember { mutableIntStateOf(0) }
    val stacks = Tab.entries.map { rememberNavBackStack(it.root) }
    val stack = stacks[selected]
    val backdrop = rememberLayerBackdrop()
    val onTop = stack.lastOrNull() == Tab.entries[selected].root
    var adding by rememberSaveable { mutableStateOf(false) }

    // A light tick when the tab changes, never on the first frame (Apple HIG playing haptics: one per action).
    val haptics = LocalHapticFeedback.current
    var firstTab by remember { mutableStateOf(true) }
    LaunchedEffect(selected) { if (firstTab) firstTab = false else haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }

    // Back leaves a tab's own stack first, then returns to Today, then leaves the app (RULES.md, navigation).
    BackHandler(enabled = selected != 0 && stack.size == 1) { selected = 0 }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BarSpace
            val padding = PaddingValues(top = top, bottom = bottom)
            // Tabs are peers: switching never slides (emilkowalski/skills animate-expo). Each tab gets its own
            // NavDisplay, so only pushes inside a tab animate.
            // Scroll edge (Apple HIG): the CONTENT fades to 35 % where it meets the bar, as an alpha mask. It used to be
            // a page-coloured strip painted on top, which put near-white under the glass on every screen, so the bar
            // had nothing to bend and looked flat (Boss 2026-10-27: "liquid glass not working on More, it's simple").
            val fadeTop = bottom + 8.dp
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val start = (size.height - fadeTop.toPx()).coerceAtLeast(0f)
                        val mask = Brush.verticalGradient(0f to Color.Black, (start / size.height) to Color.Black, 1f to Color.Black.copy(alpha = 0.35f))
                        onDrawWithContent { drawContent(); if (onTop) drawRect(mask, blendMode = BlendMode.DstIn) }
                    },
            ) {
            key(selected) { NavDisplay(
                backStack = stack,
                onBack = { stack.removeLastOrNull() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>(), rememberViewModelStoreNavEntryDecorator<NavKey>()),
                entryProvider = entryProvider<NavKey> {
                    entry<TodayRoute> { TodayScreenRoute(padding, reselect) }
                    entry<InboxRoute> { Coming("Inbox", "/learn", padding) { stack.add(WebRoute(it)) } }
                    entry<HabitsRoute> { Coming("Habits", "/habits", padding) { stack.add(WebRoute(it)) } }
                    entry<NotebookRoute> { Coming("Notebook", "/notebook", padding) { stack.add(WebRoute(it)) } }
                    entry<MoreRoute> { More(padding, onOpenProjects = { stack.add(ProjectListRoute) }, onOpenWeb = { stack.add(WebRoute("/today")) }, onOpenPhone = onOpenPhone, onDisconnect = onDisconnect) }
                    entry<ProjectListRoute> { ProjectsRoute(padding, 0) }
                    entry<WebRoute> { r -> TasksScreen(onOpenPhone = onOpenPhone, startPath = r.path) }
                },
            ) }
            }
        }
        if (onTop) {
            LiquidBottomTabs(
                selectedTabIndex = { selected },
                onTabSelected = { selected = it },
                backdrop = backdrop,
                tabsCount = Tab.entries.size,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                Tab.entries.forEachIndexed { i, tab ->
                    LiquidBottomTab(onClick = { if (selected == i) reselect++ else selected = i }) {
                        // Ink on the glass, never pale grey: the first emulator screenshot showed unselected tabs at
                        // almost no contrast. The selected tab is tinted mint by the bar's own top layer.
                        Icon(tab.icon, contentDescription = null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(22.dp))
                        Text(tab.label, fontSize = 11.sp, maxLines = 1, color = Glass.Ink.copy(alpha = 0.72f))
                    }
                }
            }
        }
        if (onTop && selected == 0) {
            AddButton(
                backdrop,
                Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(end = 20.dp, bottom = 8.dp + 64.dp + 14.dp),
            ) { adding = true }
        }
    }
    if (adding) AddTaskSheet(onClose = { adding = false })
}

/**
 * Add a task: a glass drop tinted mint, cut from the same backdrop as the bar, so it bends the page under it too
 * (rules/liquid-glass-navbar: glass on chrome only). Sits above the bar on Today, where the web has its Add row.
 */
@Composable
private fun AddButton(backdrop: Backdrop, modifier: Modifier, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(56.dp)
            .pressScale(press)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = { vibrancy(); blur(8f.dp.toPx()); lens(16f.dp.toPx(), 16f.dp.toPx()) },
                onDrawSurface = { drawRect(Glass.Primary.copy(alpha = 0.82f)) },
            )
            .clickable(press, indication = null, role = Role.Button) { onClick() }
            .semantics { contentDescription = "Add a task" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = 2.4.dp.toPx()
            drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), w, StrokeCap.Round)
            drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), w, StrokeCap.Round)
        }
    }
}

/** A tab whose native screen comes in a later phase; its pagel.uz page opens meanwhile. */
@Composable
private fun Coming(title: String, path: String, padding: PaddingValues, onOpenWeb: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("The native $title comes next, one feature at a time. Until then it opens on pagel.uz.", style = MaterialTheme.typography.bodyMedium, color = Glass.Ink)
                Spacer(Modifier.height(14.dp))
                val press = remember { MutableInteractionSource() }
                Button(onClick = { onOpenWeb(path) }, colors = ButtonDefaults.buttonColors(containerColor = Glass.Primary),
                    interactionSource = press, modifier = Modifier.pressScale(press)) { Text("Open $title") }
            }
        }
    }
}

@Composable
private fun More(padding: PaddingValues, onOpenProjects: () -> Unit, onOpenWeb: () -> Unit, onOpenPhone: () -> Unit, onDisconnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
        Text("More", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                TextButton(onClick = onOpenProjects, modifier = Modifier.fillMaxWidth()) { Text("Projects", color = Glass.Ink, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) { Text("pagel.uz", color = Glass.Ink, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = onOpenPhone, modifier = Modifier.fillMaxWidth()) { Text("Phone (gateway, senses, updates)", color = Glass.Ink, modifier = Modifier.fillMaxWidth()) }
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Disconnect this phone", color = Glass.Risk, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
