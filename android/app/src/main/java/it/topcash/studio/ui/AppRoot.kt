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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreHoriz
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Archive
import it.topcash.studio.data.Screen
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Row

/** Scopes for shared-element transitions (list avatar → detail header). */
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
    AnimatedContent(screen, transitionSpec = { fadeIn(tween(300, 80, EmphasizedDecelerate)) togetherWith fadeOut(tween(120)) }, label = "root") { s ->
        when (s) {
            Screen.Loading -> Box(Modifier.fillMaxSize())
            Screen.Setup -> SetupScreen(vm)
            Screen.Login -> LoginScreen(vm)
            Screen.Main -> {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {}
                    androidx.compose.runtime.LaunchedEffect(Unit) { ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
                }
                MainScreen(vm)
            }
            Screen.Device -> DeviceScreen(vm)
        }
    }
    }
}

/** Request to open the intervention editor sheet. */
data class EditorRequest(val interventionId: String? = null, val clientCode: String? = null)

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val allTabs = listOf(Tab("home", "Home", Icons.Rounded.Dashboard), Tab("clients", "Clienti", Icons.Rounded.Groups),
    Tab("interventions", "Interventi", Icons.Rounded.Build), Tab("warehouse", "Magazzino", Icons.Rounded.Inventory2), Tab("more", "Altro", Icons.Rounded.MoreHoriz))
// the accounting area (like Mastro's Contabilità): its own four pages in the bottom bar
private val accTabs = listOf(Tab("acc", "Riepilogo", Icons.Rounded.Insights), Tab("accRec", "Incassi", Icons.Rounded.Payments),
    Tab("accInv", "Fatture", Icons.AutoMirrored.Rounded.ReceiptLong), Tab("accPay", "Pagamenti", Icons.Rounded.AccountBalanceWallet), Tab("more", "Altro", Icons.Rounded.MoreHoriz))
private val accRoutes = setOf("acc", "accRec", "accInv", "accPay")

@Composable fun MainScreen(vm: AppViewModel) {
    val nav = rememberNavController()
    val archive by vm.archive.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var movement by remember { mutableStateOf<Pair<String, String?>?>(null) } // (type, article)
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val me by vm.me.collectAsStateWithLifecycle()
    // warehouse management is reserved to administrators
    val admin = me?.isAdmin == true
    // launcher shortcuts (long-press on the app icon)
    val sc by vm.shortcut.collectAsStateWithLifecycle()
    LaunchedEffect(sc, archive != null, admin) {
        if (archive == null) return@LaunchedEffect
        when (sc) { "new" -> editor = EditorRequest(); "load" -> if (admin) movement = "carico" to null else vm.notify("Il magazzino è riservato all'amministratore"); "search" -> { nav.switchTab("clients"); vm.focusSearch.value = true }; "route" -> nav.navigate("route") { launchSingleTop = true }; else -> return@LaunchedEffect }
        vm.shortcut.value = null
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    // two areas, Interventi and Contabilità (technicians only have Interventi); each remembers its last page
    val seesAcc = me?.seesAccounting != false
    var area by rememberSaveable { mutableStateOf("ops") }
    val lastOf = remember { mutableMapOf("ops" to "home", "acc" to "acc") }
    LaunchedEffect(route) {
        if (route in accRoutes) area = "acc" else if (route in tabRoutes && route != "more") area = "ops"
        if (route in tabRoutes && route != "more") lastOf[area] = route
    }
    val tabs = remember(admin, area) { if (area == "acc") accTabs else if (admin) allTabs else allTabs.filter { it.route != "warehouse" } }
    val topLevel = tabs.any { it.route == route }
    val areaSwitch: (@Composable () -> Unit)? = if (!seesAcc) null else ({ AreaSwitch(area) { a -> if (a != area) { area = a; nav.switchTab(lastOf[a] ?: if (a == "acc") "acc" else "home") } } })

    val haze = dev.chrisbanes.haze.rememberHazeState()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // leaving a tab the glass bar fades away at once (before the next page slides under it); it comes back softly
            // once the tab page has arrived: pages never pass through the bar half-way
            AnimatedVisibility(topLevel, enter = fadeIn(tween(220, 230)) + slideInVertically(tween(320, 230, EmphasizedDecelerate)) { it / 3 }, exit = fadeOut(tween(90))) {
                // frosted glass: the pages scroll underneath and show through blurred
                NavigationBar(Modifier.glass(haze, strong = true), containerColor = androidx.compose.ui.graphics.Color.Transparent, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
                    // changing area the items cross-fade in place (one movement), the bar itself stays still
                    androidx.compose.animation.AnimatedContent(tabs, Modifier.weight(1f), transitionSpec = { fadeIn(tween(220, 70, EmphasizedDecelerate)) togetherWith fadeOut(tween(90)) }, label = "area-tabs") { list ->
                        Row {
                            list.forEach { t ->
                                NavigationBarItem(selected = route == t.route, onClick = { nav.switchTab(t.route) }, label = { Text(t.label, maxLines = 1) }, icon = {
                                    val n = when (t.route) { "interventions" -> archive?.openInterventions?.size ?: 0; "warehouse" -> archive?.wh?.belowMin?.size ?: 0
                                        "accRec" -> archive?.acc?.overdue?.size ?: 0; "accPay" -> archive?.acc?.payables?.count { it.open && (it.days ?: 99) <= 7 } ?: 0; else -> 0 }
                                    if (n > 0) BadgedBox(badge = { Badge { Text("$n") } }) { Icon(t.icon, t.label) } else Icon(t.icon, t.label)
                                })
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val a = archive
        // pages extend under the glass bottom bar (they reserve space with their own bottom content padding)
        SharedTransitionLayout(Modifier.fillMaxSize().hazeSourceCompat(haze)) {
            // the room left for the bar changes in one step: animating it re-measured every list under the page on each
            // frame of the back animation (that was the stutter); the bar itself still fades and slides in
            val barSpace = if (topLevel) androidx.compose.ui.unit.Dp(80f) else androidx.compose.ui.unit.Dp(0f)
            CompositionLocalProvider(LocalShared provides this, LocalAppHaze provides haze, LocalBottomBar provides barSpace, LocalAreaSwitch provides areaSwitch) {
                BackMotion.Attach()
                NavHost(nav, startDestination = "home",
                    // Tabs: "fade through". Pages: one motion per action, the same for swipe and key (BackMotion.kt):
                    // open = slides in from the right over the dimmed page, back = the reverse, following the finger with the gesture.
                    enterTransition = { if (isTabSwitch()) { BackMotion.forward(); fadeIn(tween(220, 60, EmphasizedDecelerate)) } else with(BackMotion) { pushEnter() } },
                    exitTransition = { if (isTabSwitch()) { BackMotion.forward(); fadeOut(tween(80)) } else with(BackMotion) { pushExit() } },
                    popEnterTransition = { if (isTabSwitch()) { BackMotion.forward(); fadeIn(tween(220, 60, EmphasizedDecelerate)) } else with(BackMotion) { enter() } },
                    popExitTransition = { if (isTabSwitch()) { BackMotion.forward(); fadeOut(tween(80)) } else with(BackMotion) { exit() } },
                    predictivePopEnterTransition = { edge -> if (isTabSwitch()) { BackMotion.forward(); fadeIn(tween(220, 60, EmphasizedDecelerate)) } else with(BackMotion) { gestureEnter(edge) } },
                    predictivePopExitTransition = { edge -> if (isTabSwitch()) { BackMotion.forward(); fadeOut(tween(80)) } else with(BackMotion) { gestureExit(edge) } },
                ) {
                    fun screen(r: String, body: @Composable (Archive) -> Unit) = composable(r) {
                        CompositionLocalProvider(LocalAnim provides this) { Box(Modifier.navCard(this)) { if (a == null) LoadingArchive() else body(a) } }
                    }
                    screen("home") { HomeScreen(vm, it, nav, onNewIntervention = { editor = EditorRequest() }, onOpenIntervention = { id -> editor = EditorRequest(interventionId = id) }) }
                    screen("clients") { ClientsScreen(vm, it, nav) }
                    screen("interventions") { InterventionsScreen(vm, it, onOpen = { id -> editor = EditorRequest(interventionId = id) }, onNew = { editor = EditorRequest() }) }
                    screen("machines") { MachinesScreen(it, nav) }
                    screen("dtr") { DtrScreen(it, nav) }
                    screen("ital") { ItalScreen(it, nav) }
                    screen("rch") { RchScreen(it, nav) }
                    screen("inventory") { InventoryScreen(vm, it, nav) }
                    screen("renewals") { RenewalsScreen(vm, it, nav) }
                    screen("route") { RouteScreen(vm, it, nav) { id -> editor = EditorRequest(interventionId = id) } }
                    screen("users") { if (admin) UsersScreen(vm, nav) else LaunchedEffect(Unit) { nav.switchTab("home") } }
                    screen("deleted") { if (admin) DeletedClientsScreen(vm, it, nav) else LaunchedEffect(Unit) { nav.switchTab("home") } }
                    screen("warehouse") { if (admin) WarehouseScreen(vm, it, nav) { t, art -> movement = t to art } else LaunchedEffect(Unit) { nav.switchTab("home") } }
                    screen("more") { MoreScreen(vm, it, nav, area) }
                    screen("deadlines") { DeadlinesScreen(it, nav) }
                    screen("accounting") { if (seesAcc) AccountingScreen(vm, it, nav) else LaunchedEffect(Unit) { nav.switchTab("home") } }
                    AccView.entries.forEach { v -> screen(v.route) { if (seesAcc) AccountingScreen(vm, it, nav, v, topLevel = true) else LaunchedEffect(Unit) { nav.switchTab("home") } } }
                    screen("licenses") { LicensesScreen(vm, it, nav) }
                    screen("orders") { OrdersScreen(it, nav) }
                    screen("account") { AccountScreen(vm, nav) }
                    composable("article/{code}") { e ->
                        CompositionLocalProvider(LocalAnim provides this) { Box(Modifier.navCard(this)) {
                            if (a == null) LoadingArchive() else ArticleScreen(vm, a, e.arguments?.getString("code").orEmpty(), nav) { t, art -> movement = t to art }
                        } }
                    }
                    composable("client/{code}") { e ->
                        CompositionLocalProvider(LocalAnim provides this) { Box(Modifier.navCard(this)) {
                            val code = e.arguments?.getString("code").orEmpty()
                            val c = a?.byCode?.get(code)
                            if (a == null || c == null) LoadingArchive() else ClientDetailScreen(vm, a, c, nav,
                                onNewIntervention = { editor = EditorRequest(clientCode = code) }, onOpenIntervention = { id -> editor = EditorRequest(interventionId = id) })
                        } }
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

private val tabRoutes = setOf("home", "clients", "interventions", "warehouse", "more", "acc", "accRec", "accInv", "accPay")
private fun androidx.compose.animation.AnimatedContentTransitionScope<androidx.navigation.NavBackStackEntry>.isTabSwitch() =
    initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.startDestinationId) { saveState = true }; launchSingleTop = true; restoreState = true
}
fun NavHostController.openClient(code: String) = navigate("client/$code") { launchSingleTop = true }

@Composable fun LoadingArchive() = Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
    androidx.compose.material3.CircularProgressIndicator()
}
