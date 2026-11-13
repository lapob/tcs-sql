package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.animation.core.tween
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.topcash.studio.data.AppViewModel

/** Standard page: large collapsing title, pull-to-refresh, offline / sync status strip, lazy content. */
@Composable fun Page(
    vm: AppViewModel?, title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}, fab: @Composable () -> Unit = {}, state: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null, compactTitle: Boolean = false, content: LazyListScope.() -> Unit,
) {
    // compactTitle: the screen shows its own big header, so the bar title fades in only once collapsed (no duplicate name)
    val scroll = if (compactTitle) TopAppBarDefaults.pinnedScrollBehavior() else TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val pageHaze = dev.chrisbanes.haze.rememberHazeState() // the page content is the source of the top bar's frosted glass
    val clear = androidx.compose.ui.graphics.Color.Transparent
    if (compactTitle) {
        androidx.compose.material3.Scaffold(
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                val shown = state.firstVisibleItemIndex > 1
                val alpha by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, label = "title")
                GlassBar(state, pageHaze) { androidx.compose.material3.TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.graphicsLayer { this.alpha = alpha }) },
                    navigationIcon = { if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") } },
                    actions = { if (onBack == null) LocalAreaSwitch.current?.invoke(); actions() }, scrollBehavior = scroll,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = clear, scrolledContainerColor = clear),
                ) }
            },
            floatingActionButton = { FabSlot(fab) },
        ) { pad -> PageBody(vm, pad, state, header, pageHaze, content) }
        return
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            GlassBar(state, pageHaze) { LargeTopAppBar(
                title = { Column { FitTitle(title); if (subtitle != null && scroll.state.collapsedFraction < 0.5f) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = { if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Indietro") } },
                actions = { if (onBack == null) LocalAreaSwitch.current?.invoke(); actions() }, scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = clear, scrolledContainerColor = clear),
            ) }
        },
        floatingActionButton = { FabSlot(fab) },
    ) { pad -> PageBody(vm, pad, state, header, pageHaze, content) }
}

/** Content scrolls under the glass top bar (top padding inside the list, not around it) and under the glass bottom bar. */
@Composable private fun PageBody(vm: AppViewModel?, pad: PaddingValues, state: LazyListState, header: (@Composable () -> Unit)?, haze: dev.chrisbanes.haze.HazeState, content: LazyListScope.() -> Unit) {
    val idle = androidx.compose.runtime.remember { kotlinx.coroutines.flow.MutableStateFlow(false) }
    val refreshing by (vm?.refreshing ?: idle).collectAsStateWithLifecycle()
    val ptr = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    val bottom = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // one light tick when the pull crosses the "release to refresh" point (and again if it goes back and forth)
    androidx.compose.runtime.LaunchedEffect(ptr) {
        androidx.compose.runtime.snapshotFlow { ptr.distanceFraction >= 1f }.collect { armed -> if (armed && !refreshing) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick) }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm?.refresh(true) }, state = ptr, modifier = Modifier.fillMaxSize(),
        indicator = {
            @OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.LoadingIndicator(state = ptr, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = pad.calculateTopPadding()))
        }) {
        // the list follows the finger a little (rubber band), then settles with the indicator
        LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = with(density) { (if (refreshing) 1f else ptr.distanceFraction.coerceIn(0f, 1.6f)) * 28.dp.toPx() } }.hazeSourceCompat(haze), state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = bottom + 120.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            if (vm != null) item(key = "__status") { StatusStrip(vm) }
            header?.let { h -> item(key = "__header") { h() } }
            content()
        }
    }
}

@Composable private fun StatusStrip(vm: AppViewModel) {
    val online by vm.online.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    Column {
        AnimatedVisibility(!online, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Strip(Icons.Rounded.CloudOff, "Offline: stai vedendo gli ultimi dati salvati", LocalStatus.current.due)
        }
        AnimatedVisibility(progress.running, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column {
                Strip(Icons.Rounded.Sync, progress.text.ifBlank { "Sincronizzazione con Zucchetti…" }, MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(MaterialTheme.shapes.small))
            }
        }
    }
}
@Composable private fun Strip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(MaterialTheme.shapes.medium).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Text(text, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp), maxLines = 2)
    }
}

/** The FAB always sits above the glass bottom navigation AND the system gesture bar, and glides when the bar comes and goes. */
@Composable private fun FabSlot(fab: @Composable () -> Unit) {
    val sys = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val lift by androidx.compose.animation.core.animateDpAsState(LocalBottomBar.current + sys, androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 500f), label = "fab")
    androidx.compose.foundation.layout.Box(Modifier.padding(bottom = lift)) { fab() }
}

/** The top bar is clear while the page rests at the top (title on the page background, like iOS / Material large titles);
 *  the frosted glass fades in only once content scrolls underneath it. */
@Composable private fun GlassBar(state: LazyListState, haze: dev.chrisbanes.haze.HazeState, bar: @Composable () -> Unit) {
    val under = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 4
    val g by androidx.compose.animation.core.animateFloatAsState(if (under) 1f else 0f, tween(220), label = "glass")
    androidx.compose.foundation.layout.Box {
        androidx.compose.foundation.layout.Box(Modifier.matchParentSize().graphicsLayer { alpha = g }.glass(haze))
        bar()
        // no hard line under the glass: a short soft fade carries it into the page (like the site)
        val veil = MaterialTheme.colorScheme.surfaceContainer
        androidx.compose.foundation.layout.Box(Modifier.align(Alignment.BottomCenter).offset { androidx.compose.ui.unit.IntOffset(0, 14.dp.roundToPx()) }
            .fillMaxWidth().height(14.dp).graphicsLayer { alpha = g }
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(veil.copy(alpha = 0.55f), veil.copy(alpha = 0f)))))
    }
}

/** A page title on one line that shrinks to fit (down to 20 sp) instead of being cut ("Buongiorno, Lapo" on a narrow phone). */
@Composable private fun FitTitle(text: String) {
    val style = androidx.compose.material3.LocalTextStyle.current
    val color = androidx.compose.material3.LocalContentColor.current
    val max = if (style.fontSize.isSp) style.fontSize else 28.sp
    androidx.compose.foundation.text.BasicText(text, style = style.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = minOf(20f, max.value).sp, maxFontSize = max, stepSize = 1.sp))
}
