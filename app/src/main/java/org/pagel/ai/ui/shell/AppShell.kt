package org.pagel.ai.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.derivedStateOf
import org.pagel.ai.ui.glass.EaseOutStrong
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.spring
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import org.pagel.ai.ui.glass.ToastCenter
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
import org.pagel.ai.ui.glass.GlassStage
import org.pagel.ai.ui.glass.GlassTheme
import org.pagel.ai.ui.glass.LiquidBottomTab
import org.pagel.ai.ui.glass.LiquidBottomTabs
import org.pagel.ai.ui.glass.Pane
import org.pagel.ai.ui.guide.GuideDestination
import org.pagel.ai.ui.guide.GuideRoute
import org.pagel.ai.ui.guide.GuideScreen
import org.pagel.ai.ui.guide.LocalGuideOpener
import org.pagel.ai.ui.glass.Space
import org.pagel.ai.ui.glass.edgeFade
import org.pagel.ai.ui.glass.TabIcons
import org.pagel.ai.ui.glass.pressScale
import org.pagel.ai.ui.pair.PairRoute
import org.pagel.ai.ui.projects.ProjectsRoute
import org.pagel.ai.ui.projects.ProjectRoute as ProjectScreenRoute
import org.pagel.ai.ui.glass.LocalToasts
import org.pagel.ai.ui.glass.ToastHost
import org.pagel.ai.ui.task.LocalTaskOpener
import org.pagel.ai.ui.task.TaskPanelSheet
import org.pagel.ai.ui.tasks.TasksScreen
import org.pagel.ai.ui.today.TodayRoute as TodayScreenRoute
import org.pagel.ai.ui.inbox.InboxRoute as InboxScreenRoute
import org.pagel.ai.ui.habits.HabitsRoute as HabitsScreenRoute
import org.pagel.ai.ui.upcoming.CompletedRoute as CompletedScreenRoute
import org.pagel.ai.ui.upcoming.UpcomingRoute as UpcomingScreenRoute

/** The glass bar: 64 dp, or 48 dp on a landscape phone, where 64 dp took 30 % of the height (checker 2026-10-28 #10). */
private val BarTall = 64.dp
private val BarCompact = 48.dp
/** A landscape bar keeps to the middle (as iPhone's does), so the + button sits beside it, not on the list. */
private val BarCompactWidth = 440.dp

/** The native app's root (NATIVE-PLAN Phase 1): pair once, then five tabs on a real liquid-glass bar. */
@Composable
fun AppShell(onOpenPhone: () -> Unit, vm: ShellViewModel = hiltViewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    // 0 = reading the account, 1 = signed out, 2 = paired. Only pairing → Today slides (Boss 2026-10-28: the check, then
    // Today slides in); a cold start straight into Today and a sign-out change at once.
    val kind = when (account) { null -> 0; AccountState.SignedOut -> 1; is AccountState.Paired -> 2 }
    GlassTheme {
        GlassStage.Advance(ready = kind != 0)
        androidx.compose.animation.AnimatedContent(
            kind,
            transitionSpec = {
                if (initialState == 1 && targetState == 2) {
                    (androidx.compose.animation.slideInHorizontally(spring(dampingRatio = 1f, stiffness = 380f)) { it / 3 } + androidx.compose.animation.fadeIn())
                        .togetherWith(androidx.compose.animation.fadeOut())
                } else androidx.compose.animation.EnterTransition.None.togetherWith(androidx.compose.animation.ExitTransition.None)
            },
            label = "account",
        ) { k ->
            when (k) {
                // Reading the account off Main takes a few ms; the page colour paints at once, no white flash.
                0 -> AuroraBackground(Modifier.fillMaxSize()) {}
                1 -> PairRoute()
                else -> androidx.compose.runtime.CompositionLocalProvider(LocalToasts provides vm.toasts) {
                    Tabs(onOpenPhone = onOpenPhone, onDisconnect = vm::disconnect)
                }
            }
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
    // The Add sheet's target: null = closed, "" = Today, a name = that project. The open task panel's task id.
    var adding by rememberSaveable { mutableStateOf<String?>(null) }
    var openTask by rememberSaveable { mutableStateOf<String?>(null) }
    // Focus mode (core round 4): one micro-step per full-screen card, over whatever is open.
    var focusTask by rememberSaveable { mutableStateOf<String?>(null) }
    val toasts = LocalToasts.current
    val top = stack.lastOrNull()

    // Scroll-aware top (X, YouTube): scrolling down gives the content all the room; scrolling up brings a small glass
    // title back. [scrolled] is how far the list is from its top, so the title only shows once the big one is gone.
    var scrolled by remember { mutableFloatStateOf(0f) }
    var titleShown by remember { mutableStateOf(false) }
    // The capsule flips only after 24 dp in the new direction (audit #9): a finger's wobble never makes it flicker.
    val flip = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
    val scrollWatch = remember(flip) {
        object : NestedScrollConnection {
            var run = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scrolled = (scrolled - consumed.y).coerceAtLeast(0f)
                val dy = consumed.y
                if (dy != 0f) run = if (run * dy > 0f) run + dy else dy
                if (run < -flip) titleShown = false else if (run > flip) titleShown = true
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(selected, stack.size) { scrolled = 0f; titleShown = false }
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 480
    val barHeight = if (compact) BarCompact else BarTall
    // Height of the glass bar plus its gap above the system bar, so lists end above it, not under it.
    val barSpace = barHeight + 16.dp
    // Where the + button and the toast line sit: above a tall bar, beside a compact one.
    val fabBottom = if (compact) 8.dp else 8.dp + barHeight + 14.dp

    // A light tick when the tab changes, never on the first frame (Apple HIG playing haptics: one per action).
    val haptics = LocalHapticFeedback.current
    var firstTab by remember { mutableStateOf(true) }
    LaunchedEffect(selected) { if (firstTab) firstTab = false else haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }

    // A ritual's "go to Today": every stack back to its root, then the Today tab (Phase 5).
    fun goToday() {
        for (s in listOf(stack, stacks[0])) while (s.size > 1) s.removeLastOrNull()
        selected = 0
    }
    // The evening notification's "Close the day" (MainActivity → OpenRequests): opened on Today's stack.
    val opening by OpenRequests.pending.collectAsStateWithLifecycle()
    LaunchedEffect(opening) {
        val name = opening ?: return@LaunchedEffect
        OpenRequests.route(name)?.let { r ->
            val today = stacks[0]
            while (today.size > 1) today.removeLastOrNull()
            today.add(r)
            selected = 0
        }
        OpenRequests.pending.value = null
    }

    // "What's new" once after an update (never on a fresh install); More → What's new opens it again.
    var whatsNew by rememberSaveable { mutableStateOf(false) }
    val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) { if (kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WhatsNewPrefs.showOnce(appCtx) }) whatsNew = true }
    fun openWhere(where: String) {
        whatsNew = false
        val more = Tab.entries.indexOf(Tab.More)
        when (where) {
            "upcoming" -> { selected = more; stacks[more].add(UpcomingListRoute) }
            "notifications" -> { selected = more; stacks[more].add(NotificationsRoute) }
            "compass" -> { selected = more; stacks[more].add(org.pagel.ai.ui.compass.CompassRoute) }
            "habits" -> selected = Tab.entries.indexOf(Tab.Habits)
            "shutdown" -> { goToday(); stacks[0].add(ShutdownRoute) }
            "more" -> selected = more
            else -> goToday()
        }
    }

    // Back leaves a tab's own stack first, then returns to Today, then leaves the app (RULES.md, navigation).
    BackHandler(enabled = selected != 0 && stack.size == 1) { selected = 0 }

    val inProject = top as? ProjectPageRoute
    val showFab = (onTop && selected == 0) || inProject != null
    val fabAt = if (onTop) fabBottom else Space.lg
    androidx.compose.runtime.CompositionLocalProvider(LocalTaskOpener provides { id: String -> openTask = id }, LocalGuideOpener provides { card: Int -> stack.add(GuideRoute(card)) },
        org.pagel.ai.ui.task.LocalFocusOpener provides { id: String -> focusTask = id }) {
    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            // While the + shows, a list ends above it, not under it (spacing audit #8: the + covered the last row's
            // title at 1.5x font even at the end of the scroll): the +'s top plus 12.
            val clear = if (showFab) maxOf(barSpace, fabAt + 56.dp + Space.md) else barSpace
            val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + clear
            val padding = PaddingValues(top = top, bottom = bottom)
            // Tabs are peers: switching never slides (emilkowalski/skills animate-expo). Each tab gets its own
            // NavDisplay, so only pushes inside a tab animate.
            // Scroll edge (Apple HIG): the CONTENT fades to 35 % where it meets the bar, as an alpha mask. It used to be
            // a page-coloured strip painted on top, which put near-white under the glass on every screen, so the bar
            // had nothing to bend and looked flat (Boss 2026-10-27: "liquid glass not working on More, it's simple").
            val fadeTop = bottom - clear + barSpace + Space.sm
            Box(
                Modifier.fillMaxSize()
                    .nestedScroll(scrollWatch)
                    // No full-screen offscreen layer in a cold start's first frame (GlassStage).
                    // Both edges are an alpha mask on the CONTENT (Boss 2026-10-28: "that colour is applied to the status
                    // bar too"): the list melts away under the clock and toward the bar, the aurora runs unbroken from the
                    // top pixel. Never a painted band.
                    .then(if (!GlassStage.on) Modifier else Modifier.edgeFade(top + 14.dp, if (onTop) fadeTop else null)),
            ) {
            key(selected) { NavDisplay(
                backStack = stack,
                onBack = { stack.removeLastOrNull() },
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>(), rememberViewModelStoreNavEntryDecorator<NavKey>()),
                entryProvider = entryProvider<NavKey> {
                    entry<TodayRoute> {
                        TodayScreenRoute(padding, reselect, links = org.pagel.ai.ui.rituals.RitualLinks(
                            sweep = { stack.add(SweepRoute) }, shutdown = { stack.add(ShutdownRoute) }, review = { stack.add(ReviewRoute) },
                        ))
                    }
                    entry<SweepRoute> { org.pagel.ai.ui.rituals.SweepRoute(padding, onToday = ::goToday) }
                    entry<ShutdownRoute> { org.pagel.ai.ui.rituals.ShutdownRoute(padding, onToday = ::goToday) }
                    entry<ReviewRoute> {
                        org.pagel.ai.ui.rituals.ReviewRoute(padding, onInbox = { selected = 1 }, onSweep = { stack.add(SweepRoute) }, onToday = ::goToday)
                    }
                    entry<InboxRoute> {
                        InboxScreenRoute(padding, reselect, onSorted = { stack.add(SortedTodayRoute) }, onOpen = { o ->
                            when (o) { is org.pagel.ai.ui.inbox.InboxOpen.Project -> stack.add(ProjectPageRoute(o.name)); is org.pagel.ai.ui.inbox.InboxOpen.Notes -> stack.add(NotesRoute(o.project)) }
                        })
                    }
                    entry<SortedTodayRoute> {
                        org.pagel.ai.ui.inbox.SortedTodayRoute(padding, onBack = { stack.removeLastOrNull() }, onOpen = { o ->
                            when (o) { is org.pagel.ai.ui.inbox.InboxOpen.Project -> stack.add(ProjectPageRoute(o.name)); is org.pagel.ai.ui.inbox.InboxOpen.Notes -> stack.add(NotesRoute(o.project)) }
                        })
                    }
                    entry<SomedayRoute> {
                        org.pagel.ai.ui.inbox.SomedayRoute(padding, onBack = { stack.removeLastOrNull() }, onOpen = { stack.add(ProjectPageRoute(it)) })
                    }
                    entry<NotesRoute> { r -> org.pagel.ai.ui.inbox.NotesRoute(r.project, padding, onBack = { stack.removeLastOrNull() }, onOpenProject = { stack.add(NotesRoute(it)) }) }
                    entry<HabitsRoute> { HabitsScreenRoute(padding, reselect, onOpenHabit = { stack.add(HabitDetailRoute(it)) }, onHistory = { stack.add(HabitHistoryRoute) }, onOpenNamoz = { stack.add(NamozRoute) }) }
                    entry<NamozRoute> { org.pagel.ai.ui.namoz.NamozRoute(padding, onBack = { stack.removeLastOrNull() }) }
                    entry<HabitDetailRoute> { r -> org.pagel.ai.ui.habits.HabitDetailRoute(r.id, padding, onBack = { stack.removeLastOrNull() }) }
                    entry<HabitHistoryRoute> { org.pagel.ai.ui.habits.HabitHistoryRoute(padding, onBack = { stack.removeLastOrNull() }, onOpenHabit = { stack.add(HabitDetailRoute(it)) }) }
                    entry<NotebookRoute> { Coming("Notebook", "/notebook", padding) { stack.add(WebRoute(it)) } }
                    entry<MoreRoute> {
                        More(
                            padding, onOpenUpcoming = { stack.add(UpcomingListRoute) }, onOpenCompleted = { stack.add(CompletedListRoute) },
                            onOpenRitual = { stack.add(it) }, onWhatsNew = { whatsNew = true },
                            onOpenProjects = { stack.add(ProjectListRoute) }, onOpenWeb = { stack.add(WebRoute("/today")) }, onOpenPhone = onOpenPhone, onDisconnect = onDisconnect,
                        )
                    }
                    entry<UpcomingListRoute> { UpcomingScreenRoute(padding, reselect) }
                    entry<NotificationsRoute> {
                        val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
                        org.pagel.ai.ui.rituals.NotifyRoute(padding) {
                            runCatching { dagger.hilt.android.EntryPointAccessors.fromApplication(app, org.pagel.ai.service.BootReceiver.Parts::class.java).reminders().replan() }
                        }
                    }
                    entry<CompletedListRoute> { CompletedScreenRoute(padding, reselect) }
                    entry<BlackHoleLabRoute> { org.pagel.ai.ui.motion.blackhole.BlackHoleLabScreen(padding) }
                    entry<org.pagel.ai.ui.compass.CompassRoute> { org.pagel.ai.ui.compass.CompassRouteScreen(padding) }
                    entry<GuideRoute> { r ->
                        GuideScreen(r.card, padding) { to ->
                            when (to) {
                                GuideDestination.AddSheet -> adding = ""
                                // The guide leaves first, so Try it also works from the tab it was opened on.
                                is GuideDestination.OnTab -> {
                                    stack.removeLastOrNull()
                                    val t = Tab.entries.indexOf(to.tab)
                                    while (stacks[t].size > 1) stacks[t].removeLastOrNull()
                                    selected = t
                                }
                                is GuideDestination.Push -> { stack.removeLastOrNull(); stack.add(to.route) }
                            }
                        }
                    }
                    entry<ProjectListRoute> { ProjectsRoute(padding, reselect, onOpenProject = { stack.add(ProjectPageRoute(it)) }) }
                    entry<ProjectPageRoute> { r -> ProjectScreenRoute(r.name, padding, reselect, onOpenTask = { openTask = it }, onAdd = { adding = it },
                        onGone = { stack.removeLastOrNull() }, onOpenProject = { stack.add(ProjectPageRoute(it)) }) }
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
                barHeight = barHeight,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .then(if (compact) Modifier.widthIn(max = BarCompactWidth) else Modifier)
                    .fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                Tab.entries.forEachIndexed { i, tab ->
                    LiquidBottomTab(onClick = { if (selected == i) reselect++ else selected = i }) {
                        // Ink on the glass, never pale grey: the first emulator screenshot showed unselected tabs at
                        // almost no contrast. The selected tab is tinted mint by the bar's own top layer.
                        if (compact) Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(tab.icon, contentDescription = null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            TabLabel(tab.label)
                        } else {
                            Icon(tab.icon, contentDescription = null, tint = Glass.Ink.copy(alpha = 0.72f), modifier = Modifier.size(22.dp))
                            TabLabel(tab.label)
                        }
                    }
                }
            }
        }
        val pageTitle = when (top) {
            ProjectListRoute -> "Projects"; UpcomingListRoute -> "Upcoming"; CompletedListRoute -> "Completed"; BlackHoleLabRoute -> "Lab"
            SweepRoute -> "Empty your head"; ShutdownRoute -> "Close the day"; ReviewRoute -> "Weekly review"; NotificationsRoute -> "Notifications"
            org.pagel.ai.ui.compass.CompassRoute -> "Compass"; is GuideRoute -> "How it works"
            is HabitDetailRoute -> "Habit"; HabitHistoryRoute -> "History"; NamozRoute -> "Namoz"; SortedTodayRoute -> "Sorted today"; SomedayRoute -> "Someday"; is NotesRoute -> "Notes"
            is WebRoute, is ProjectPageRoute -> null; else -> Tab.entries[selected].label
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        // Read through derivedStateOf: the shell redraws when a switch flips, not on every scrolled pixel.
        val farDown by remember(density) { derivedStateOf { scrolled > with(density) { 72.dp.toPx() } } }
        if (pageTitle != null) {
            TitleCapsule(
                title = pageTitle,
                visible = titleShown && farDown,
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.TopCenter),
            ) { reselect++ }
        }
        // The Inbox has no + and its dock sits low: there the toast comes in under the status bar, so "Dropped · Undo"
        // never covers the next card's buttons (checker 2026-10-28 #2).
        BottomActions(
            toasts, backdrop, bottom = fabAt,
            showFab = showFab,
            toastOnTop = onTop && Tab.entries[selected] == Tab.Inbox,
        ) { adding = inProject?.name ?: "" }
        DayClearedBurst()
    }
    }
    if (whatsNew) WhatsNewSheet(onDismiss = { whatsNew = false }, onOpen = ::openWhere)
    adding?.let { target -> AddTaskSheet(onClose = { adding = null }, project = target.ifEmpty { null }) }
    openTask?.let { id ->
        androidx.compose.runtime.key(id) {
            TaskPanelSheet(id, onClose = { openTask = null }, onOpenProject = { name -> openTask = null; if (top != ProjectPageRoute(name)) stack.add(ProjectPageRoute(name)) },
                onFocus = { focusTask = it })
        }
    }
    focusTask?.let { id ->
        androidx.compose.runtime.key("focus-$id") {
            org.pagel.ai.ui.task.FocusSheet(id, onClose = { focusTask = null }, onOpenProject = { name -> focusTask = null; openTask = null; stack.add(ProjectPageRoute(name)) })
        }
    }
}

/**
 * A tab's word: grows with the phone's font up to 1.2x (as iOS tab bars do), then shrinks to fit its slot, down to
 * 8 dp, so it is never cut ("Noteboo'" at 1.5x on a 320 dp phone, walk 29, checker 2026-10-28 #1).
 */
@Composable
private fun TabLabel(label: String) {
    val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    androidx.compose.foundation.text.BasicText(
        label,
        style = MaterialTheme.typography.labelSmall.copy(color = Glass.Ink.copy(alpha = 0.72f), fontSize = (11f * minOf(scale, 1.2f) / scale).sp, lineHeight = (14f * minOf(scale, 1.2f) / scale).sp),
        maxLines = 1,
        softWrap = false,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = (8f / scale).sp, maxFontSize = (11f * minOf(scale, 1.2f) / scale).sp, stepSize = (0.25f / scale).sp),
    )
}

/**
 * The page's name in a small glass capsule under the status bar, shown when Boss scrolls back up (he wants something),
 * hidden while he scrolls down (he wants room). Same glass as the bottom bar; a tap goes back to the top.
 */
@Composable
private fun TitleCapsule(title: String, visible: Boolean, backdrop: Backdrop, modifier: Modifier, onTap: () -> Unit) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        modifier = modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 6.dp),
        enter = androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220, easing = EaseOutStrong)) { -it * 2 } + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(180)) { -it * 2 } + androidx.compose.animation.fadeOut(),
    ) {
        val press = remember { MutableInteractionSource() }
        Box(
            Modifier
                .height(40.dp)
                .pressScale(press)
                .then(if (!GlassStage.on) Modifier.background(Color.White.copy(alpha = 0.86f), CircleShape) else Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = { vibrancy(); blur(8f.dp.toPx()); lens(16f.dp.toPx(), 16f.dp.toPx()) },
                    highlight = { com.kyant.backdrop.highlight.Highlight.Default },
                    shadow = { com.kyant.backdrop.shadow.Shadow(alpha = 0.7f) },
                    onDrawSurface = { drawRect(Color.White.copy(alpha = 0.34f)) },
                ))
                .clickable(press, indication = null, role = Role.Button, onClickLabel = "Back to top") { onTap() }
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) { Text(title, style = MaterialTheme.typography.titleMedium, color = Glass.Ink) }
    }
}

/**
 * The + and the toast line at the bottom, as Material's snackbar does (Boss 2026-10-28: "that toast should be below
 * the float"): the toast sits just above the glass bar and the + springs up over it while it shows, then back. The
 * toast lives here, over the real list, so its glass bends what is under it (audit #8); while a sheet is open the sheet
 * shows it instead.
 */
@Composable
internal fun BoxScope.BottomActions(toasts: ToastCenter, backdrop: Backdrop, bottom: Dp, showFab: Boolean, toastOnTop: Boolean, onAdd: () -> Unit) {
    val density = LocalDensity.current
    var toastH by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val under = toasts.sheets == 0 && !toastOnTop && toastH > 0
    val lift by androidx.compose.animation.core.animateDpAsState(
        if (under) with(density) { toastH.toDp() } + 10.dp else 0.dp, spring(dampingRatio = 0.8f, stiffness = 420f), label = "fab",
    )
    if (showFab) {
        AddButton(
            backdrop,
            Modifier.align(Alignment.BottomEnd).windowInsetsPadding(WindowInsets.navigationBars).padding(end = 20.dp, bottom = bottom)
                .offset { IntOffset(0, -lift.roundToPx()) },
            onAdd,
        )
    }
    if (toasts.sheets == 0) {
        ToastHost(
            toasts,
            if (toastOnTop) Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(start = Space.gutter, end = Space.gutter, top = Space.sm)
            else Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(start = Space.gutter, end = Space.gutter, bottom = bottom)
                .onSizeChanged { toastH = it.height },
            backdrop = backdrop,
        )
    }
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
            .then(if (!GlassStage.on) Modifier.background(Glass.Primary, CircleShape) else Modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { Capsule() },
                effects = { vibrancy(); blur(8f.dp.toPx()); lens(16f.dp.toPx(), 16f.dp.toPx()) },
                onDrawSurface = { drawRect(Glass.Primary.copy(alpha = 0.82f)) },
            ))
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
                Text("Opens on pagel.uz for now.", style = MaterialTheme.typography.bodyMedium, color = Glass.Muted)
                Spacer(Modifier.height(14.dp))
                val press = remember { MutableInteractionSource() }
                Button(onClick = { onOpenWeb(path) }, colors = ButtonDefaults.buttonColors(containerColor = Glass.Primary),
                    interactionSource = press, modifier = Modifier.pressScale(press)) { Text("Open $title") }
            }
        }
    }
}

@Composable
private fun More(padding: PaddingValues, onOpenUpcoming: () -> Unit, onOpenCompleted: () -> Unit, onOpenRitual: (NavKey) -> Unit, onWhatsNew: () -> Unit, onOpenProjects: () -> Unit, onOpenWeb: () -> Unit, onOpenPhone: () -> Unit, onDisconnect: () -> Unit) {
    // Nine rows pass a 640-dp screen's height: More scrolls, so Disconnect is never stuck under the bar.
    // The same gutter and header as Today, Inbox and Habits (spacing audit #14: More's title sat ~8 dp off between tabs
    // and its panes 20 dp from the edge, every other screen 16).
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(padding).padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl)) {
        Column(Modifier.padding(start = Space.textEdge - Space.gutter, top = Space.headerTop)) {
            Text("Lists, rituals, settings", style = MaterialTheme.typography.labelSmall, color = Glass.Muted, maxLines = 1)
            Text("More", style = MaterialTheme.typography.headlineLarge, color = Glass.Ink, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.height(Space.headerToCard))
        Pane(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                // The Compass first: Boss's goals are the core every list below serves (Phase 6a).
                MoreRow(org.pagel.ai.ui.compass.CompassIcons.Compass, "Compass", Glass.Primary) { onOpenRitual(org.pagel.ai.ui.compass.CompassRoute) }
                MoreRow(TabIcons.BookOpen, "How it works", Color(0xFF246FE0)) { onOpenRitual(GuideRoute()) }
                // The web's phone bar keeps Upcoming and Completed under More too (dashboard nav.tsx PHONE).
                MoreRow(TabIcons.Calendar, "Upcoming", Color(0xFFD9620B), onOpenUpcoming)
                MoreRow(TabIcons.Done, "Completed", Color(0xFF0E7490), onOpenCompleted)
                MoreRow(TabIcons.StickyNote, "Notes", Color(0xFF0B7A5E)) { onOpenRitual(NotesRoute()) }
                MoreRow(TabIcons.Moon, "Someday", Color(0xFF4F5FA8)) { onOpenRitual(SomedayRoute) }
                // The rituals sit beside the lists, as the web's sidebar (nav.tsx: Empty head, Close day, Review).
                MoreRow(TabIcons.Sparkles, "Empty your head", Color(0xFF7C4DDB)) { onOpenRitual(SweepRoute) }
                MoreRow(TabIcons.Moon, "Close the day", Color(0xFF5B4BC4)) { onOpenRitual(ShutdownRoute) }
                MoreRow(TabIcons.CalendarCheck, "Weekly review", Color(0xFFBE123C)) { onOpenRitual(ReviewRoute) }
                MoreRow(TabIcons.Bell, "Notifications", Color(0xFFC2410C)) { onOpenRitual(NotificationsRoute) }
                MoreRow(TabIcons.Sparkles, "What's new", Color(0xFF0E7490), onWhatsNew)
                MoreRow(TabIcons.Folder, "Projects", Glass.Primary, onOpenProjects)
                MoreRow(TabIcons.Globe, "pagel.uz", Color(0xFF246FE0), onOpenWeb)
                MoreRow(TabIcons.Phone, "Phone", Color(0xFF692FC2), onOpenPhone)
                MoreRow(TabIcons.Zap, "Lab", Color(0xFF3F3D56)) { onOpenRitual(BlackHoleLabRoute) }
                MoreRow(TabIcons.LogOut, "Disconnect", Glass.Risk, onDisconnect)
            }
        }
        Spacer(Modifier.height(Space.paneGap))
        // Real use, measured (core round KPI): counts only, read from pagel.uz.
        org.pagel.ai.ui.projects.YourWeekRoute()
        Spacer(Modifier.height(Space.paneGap))
        Pane(Modifier.fillMaxWidth()) { SoundsRow() }
    }
}

/**
 * Sounds: "Always" (default: the ding plays through the media stream even on silent, as Duolingo and Things do) or
 * "Silent mode" (follows the ringer: no ding on silent or vibrate). A tap switches; the haptic and confetti stay.
 */
@Composable
private fun SoundsRow() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { mode = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { org.pagel.ai.ui.today.FinishSound.mode(ctx) } }
    val always = mode != org.pagel.ai.ui.today.FinishSound.FOLLOW
    val word = if (always) "Always" else "Silent mode"
    val press = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().pressScale(press)
            .clickable(press, indication = null, role = Role.Button, onClickLabel = "Change") {
                val next = if (always) org.pagel.ai.ui.today.FinishSound.FOLLOW else org.pagel.ai.ui.today.FinishSound.ALWAYS
                mode = next
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    org.pagel.ai.ui.today.FinishSound.setMode(ctx, next)
                    // A sample of what a tick sounds like now.
                    org.pagel.ai.ui.today.Ding.play(ctx)
                }
            }
            .semantics(mergeDescendants = true) { contentDescription = "Sounds: $word" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFEB8909).copy(alpha = 0.85f), Color(0xFFEB8909))), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(TabIcons.Volume, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.size(14.dp))
        Text("Sounds", style = MaterialTheme.typography.titleMedium, color = Glass.Ink, modifier = Modifier.weight(1f))
        if (mode != null) Text(word, style = MaterialTheme.typography.bodyMedium, color = Glass.PrimaryInk, maxLines = 1)
    }
}

/** One More row: a lit icon tile and one word, like the web's sidebar (rules/one-icon-over-many-words). */
@Composable
private fun MoreRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, color: Color, onClick: () -> Unit) {
    val press = remember { MutableInteractionSource() }
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().pressScale(press).clickable(press, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(34.dp).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(color.copy(alpha = 0.85f), color)), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.size(14.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (color == Glass.Risk) Glass.Risk else Glass.Ink)
    }
}

/**
 * The day cleared, from any screen (round B checker #9): one full-screen burst from the screen's centre, the chord and
 * a Success haptic. ShellViewModel watches Room (DayClear) so a tick from the panel, Upcoming or Habits counts too.
 */
@Composable
private fun BoxScope.DayClearedBurst() {
    val ctx = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val haptics = LocalHapticFeedback.current
    val bursts = remember { androidx.compose.runtime.mutableStateListOf<org.pagel.ai.ui.today.Burst>() }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(Unit) {
        var next = 0L
        org.pagel.ai.ui.today.DayClear.events.collect {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            launch(kotlinx.coroutines.Dispatchers.Default) { org.pagel.ai.ui.today.Ding.chord(ctx) }
            bursts += org.pagel.ai.ui.today.Burst(next++, Offset(size.width / 2f, size.height / 2f), power = 2)
        }
    }
    Box(Modifier.matchParentSize().onSizeChanged { size = it }) {
        for (b in bursts) androidx.compose.runtime.key(b.id) {
            org.pagel.ai.ui.today.ConfettiBurst(b, onEnd = { bursts.remove(b) }, modifier = Modifier.fillMaxSize())
        }
    }
}
