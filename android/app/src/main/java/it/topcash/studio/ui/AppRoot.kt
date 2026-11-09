package it.topcash.studio.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PointOfSale
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Screen

/** Scopes for shared-element transitions (list avatar → detail header). */
private val navSpring = androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 420f, visibilityThreshold = androidx.compose.ui.unit.IntOffset(1, 1))
private val navSpringF = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
val LocalShared = compositionLocalOf<SharedTransitionScope?> { null }
val LocalAnim = compositionLocalOf<AnimatedVisibilityScope?> { null }

@Composable fun Modifier.shared(key: String): Modifier {
    val s = LocalShared.current ?: return this; val a = LocalAnim.current ?: return this
    return with(s) { this@shared.sharedElement(rememberSharedContentState(key), a) }
}
@Composable fun Modifier.sharedBox(key: String): Modifier {
    val s = LocalShared.current ?: return this; val a = LocalAnim.current ?: return this
    return with(s) { this@sharedBox.sharedBounds(rememberSharedContentState(key), a) }
}

object Actions {
    private fun go(ctx: Context, i: Intent) = try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { Toast.makeText(ctx, "Nessuna app disponibile", Toast.LENGTH_SHORT).show() }
    fun call(ctx: Context, phone: String) = go(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })))
    fun mail(ctx: Context, to: String) = go(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")))
    fun map(ctx: Context, address: String) = go(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(address))))
}

@Composable fun AppRoot(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    // Surface provides the theme's content color to every screen (correct text color in light and dark mode)
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    AnimatedContent(screen, transitionSpec = { (fadeIn(tween(380)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(220)) }, label = "root") { s ->
        when (s) {
            Screen.Loading -> Box(Modifier.fillMaxSize())
            Screen.Setup -> SetupScreen(vm)
            Screen.Login -> LoginScreen(vm)
            Screen.Main -> MainScreen(vm)
            Screen.Device -> DeviceScreen(vm)
        }
    }
    }
}

/** Request to open the intervention editor sheet. */
data class EditorRequest(val interventionId: String? = null, val clientCode: String? = null)

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(Tab("home", "Home", Icons.Rounded.Dashboard), Tab("clients", "Clienti", Icons.Rounded.Groups),
    Tab("interventions", "Interventi", Icons.Rounded.Build), Tab("warehouse", "Magazzino", Icons.Rounded.Inventory2), Tab("more", "Altro", Icons.Rounded.MoreHoriz))

@Composable fun MainScreen(vm: AppViewModel) {
    val nav = rememberNavController()
    val archive by vm.archive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var movement by remember { mutableStateOf<Pair<String, String?>?>(null) } // (type, article)
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // launcher shortcuts (long-press on the app icon)
    val sc by vm.shortcut.collectAsStateWithLifecycle()
    LaunchedEffect(sc, archive != null) {
        if (archive == null) return@LaunchedEffect
        when (sc) { "new" -> editor = EditorRequest(); "load" -> movement = "carico" to null; "search" -> { nav.switchTab("clients"); vm.focusSearch.value = true }; else -> return@LaunchedEffect }
        vm.shortcut.value = null
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val topLevel = tabs.any { it.route == route }

    val haze = dev.chrisbanes.haze.rememberHazeState()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            AnimatedVisibility(topLevel, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                // frosted glass: the pages scroll underneath and show through blurred
                NavigationBar(Modifier.glass(haze, strong = true), containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    tabs.forEach { t ->
                        NavigationBarItem(selected = route == t.route, onClick = { nav.switchTab(t.route) }, label = { Text(t.label) }, icon = {
                            val n = when (t.route) { "interventions" -> archive?.openInterventions?.size ?: 0; "warehouse" -> archive?.wh?.belowMin?.size ?: 0; else -> 0 }
                            if (n > 0) BadgedBox(badge = { Badge { Text("$n") } }) { Icon(t.icon, t.label) } else Icon(t.icon, t.label)
                        })
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val a = archive
        // pages extend under the glass bottom bar (they reserve space with their own bottom content padding)
        SharedTransitionLayout(Modifier.fillMaxSize().hazeSourceCompat(haze)) {
            CompositionLocalProvider(LocalShared provides this, LocalAppHaze provides haze, LocalBottomBar provides if (topLevel) androidx.compose.ui.unit.Dp(80f) else androidx.compose.ui.unit.Dp(0f)) {
                NavHost(nav, startDestination = "home",
                    // tabs: quick "fade through"; details: spring slide that follows the finger on predictive back
                    enterTransition = { if (initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes) fadeIn(tween(210, 70)) + scaleIn(navSpringF, 0.95f) else slideInHorizontally(navSpring) { it / 3 } + fadeIn(tween(220)) },
                    exitTransition = { if (initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes) fadeOut(tween(90)) else slideOutHorizontally(navSpring) { -it / 6 } + fadeOut(tween(220)) },
                    popEnterTransition = { slideInHorizontally(navSpring) { -it / 6 } + fadeIn(tween(220)) },
                    popExitTransition = { slideOutHorizontally(navSpring) { it / 3 } + fadeOut(tween(200)) },
                ) {
                    fun screen(r: String, body: @Composable (Archive) -> Unit) = composable(r) {
                        CompositionLocalProvider(LocalAnim provides this) { if (a == null) LoadingArchive() else body(a) }
                    }
                    screen("home") { HomeScreen(vm, it, nav, onNewIntervention = { editor = EditorRequest() }, onOpenIntervention = { id -> editor = EditorRequest(interventionId = id) }) }
                    screen("clients") { ClientsScreen(vm, it, nav) }
                    screen("interventions") { InterventionsScreen(vm, it, onOpen = { id -> editor = EditorRequest(interventionId = id) }, onNew = { editor = EditorRequest() }) }
                    screen("machines") { MachinesScreen(it, nav) }
                    screen("warehouse") { WarehouseScreen(vm, it, nav) { t, art -> movement = t to art } }
                    screen("more") { MoreScreen(vm, it, nav) }
                    screen("deadlines") { DeadlinesScreen(it, nav) }
                    screen("licenses") { LicensesScreen(it, nav) }
                    screen("orders") { OrdersScreen(it, nav) }
                    screen("account") { AccountScreen(vm, nav) }
                    composable("article/{code}") { e ->
                        CompositionLocalProvider(LocalAnim provides this) {
                            if (a == null) LoadingArchive() else ArticleScreen(vm, a, e.arguments?.getString("code").orEmpty(), nav) { t, art -> movement = t to art }
                        }
                    }
                    composable("client/{code}") { e ->
                        CompositionLocalProvider(LocalAnim provides this) {
                            val code = e.arguments?.getString("code").orEmpty()
                            val c = a?.byCode?.get(code)
                            if (a == null || c == null) LoadingArchive() else ClientDetailScreen(vm, a, c, nav,
                                onNewIntervention = { editor = EditorRequest(clientCode = code) }, onOpenIntervention = { id -> editor = EditorRequest(interventionId = id) })
                        }
                    }
                }
            }
        }
        val ed = editor
        val mv = movement
        if (mv != null && a != null) MovementSheet(vm, a, mv.first, mv.second, onDismiss = { movement = null })
        if (ed != null && a != null) InterventionEditor(vm, a, ed, onDismiss = { editor = null }, onOpenClient = { code -> editor = null; nav.navigate("client/$code") })
    }
}

private val tabRoutes = setOf("home", "clients", "interventions", "warehouse", "more")
fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true
}
fun NavHostController.openClient(code: String) = navigate("client/$code") { launchSingleTop = true }

@Composable fun LoadingArchive() = Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
    androidx.compose.material3.CircularProgressIndicator()
}
