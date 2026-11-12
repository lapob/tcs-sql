package it.topcash.studio.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.os.SystemClock
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * One motion per action, the same whether you swipe or press back (no scaling, no rounded card, no cross-fades):
 *  - open a page: it slides in whole from the right edge with a soft shadow on its border,
 *    the page underneath drifts a quarter to the left and dims (parallax);
 *  - back: the exact reverse. With the predictive gesture the page follows the finger (linear timeline that
 *    NavHost seeks; letting go finishes it, cancelling puts it back), with the key it runs on its own (emphasized decelerate).
 * The gesture is read from the same dispatcher NavHost listens to, without intercepting it.
 */
internal object BackMotion {
    const val GESTURE_MS = 360
    const val PUSH_MS = 380
    const val POP_MS = 340
    const val PARALLAX = 0.25f
    const val DIM = 0.28f
    private var dispatcher: NavigationEventDispatcher? = null
    private var lastGestureEnd = 0L
    private var sawGesture = false
    /** read by every page to draw the dim and the edge shadow */
    var popping by mutableStateOf(false)
    var gesture by mutableStateOf(false)
    /** +1: the top page moves on the right edge (normal), -1: swiped from the right edge, it leaves to the left */
    var edgeSign by mutableStateOf(1)

    @Composable fun Attach() {
        val d = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        dispatcher = d
        LaunchedEffect(d) {
            d?.transitionState?.collect { s ->
                // set before NavHost composes the page underneath, so its first frame is already dimmed
                if (s is NavigationEventTransitionState.InProgress) {
                    if (!sawGesture) { popping = true; gesture = true; edgeSign = if (s.latestEvent.swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1 }
                    sawGesture = true
                }
                else if (sawGesture) { sawGesture = false; lastGestureEnd = SystemClock.uptimeMillis() }
            }
        }
    }

    /** decides, once per back transition, whether it follows a finger */
    private fun begin() {
        val s = dispatcher?.transitionState?.value
        val live = s is NavigationEventTransitionState.InProgress
        // NavHost may re-evaluate the spec right after the finger lifts: that is still the gesture's animation
        val g = live || SystemClock.uptimeMillis() - lastGestureEnd < 120
        edgeSign = if (live && s.latestEvent.swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else if (g) edgeSign else 1
        gesture = g; popping = true
    }
    fun forward() { popping = false; gesture = false; edgeSign = 1 }

    private fun push(): FiniteAnimationSpec<IntOffset> = tween(PUSH_MS, easing = EmphasizedDecelerate)
    private fun pop(): FiniteAnimationSpec<IntOffset> = if (gesture) tween(GESTURE_MS, easing = LinearEasing) else tween(POP_MS, easing = EmphasizedDecelerate)

    /** opening: new page from the right edge, the old one drifts left */
    fun AnimatedContentTransitionScope<NavBackStackEntry>.pushEnter(): EnterTransition { forward(); return slideInHorizontally(push()) { it } }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.pushExit(): ExitTransition { forward(); return slideOutHorizontally(push()) { -(it * PARALLAX).toInt() } }

    /** back: the page leaves whole towards the edge, the previous one returns from its parallax position */
    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition { begin(); return slideInHorizontally(pop()) { -(it * PARALLAX * edgeSign).toInt() } }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition { begin(); return slideOutHorizontally(pop()) { it * edgeSign } }

    /** NavHost's own slot for the predictive gesture (its default would shrink the page to 70%): same motion, driven by the finger */
    private fun gestureFrom(edge: Int) { popping = true; gesture = true; edgeSign = if (edge == NavigationEvent.EDGE_RIGHT) -1 else 1 }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.gestureEnter(edge: Int): EnterTransition { gestureFrom(edge); return slideInHorizontally(pop()) { -(it * PARALLAX * edgeSign).toInt() } }
    fun AnimatedContentTransitionScope<NavBackStackEntry>.gestureExit(edge: Int): ExitTransition { gestureFrom(edge); return slideOutHorizontally(pop()) { it * edgeSign } }
}

/** Every page: opaque; the page underneath is dimmed while another one covers it, the page on top casts a soft shadow
 *  on its moving edge. Read in the draw phase only: no recomposition per frame. */
@Composable fun Modifier.navCard(scope: AnimatedVisibilityScope): Modifier {
    val t = scope.transition
    val popping = BackMotion.popping
    val gesture = BackMotion.gesture
    val dim = t.animateFloat(transitionSpec = {
        when { !popping -> tween(BackMotion.PUSH_MS, easing = EmphasizedDecelerate); gesture -> tween(BackMotion.GESTURE_MS, easing = LinearEasing); else -> tween(BackMotion.POP_MS, easing = EmphasizedDecelerate) }
    }, label = "dim") { s -> if ((s == EnterExitState.PostExit && !popping) || (s == EnterExitState.PreEnter && popping)) BackMotion.DIM else 0f }
    val shadowColor = Color.Black
    return this.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .drawWithContent {
            drawContent()
            val a = dim.value
            if (a > 0.004f) drawRect(Color.Black.copy(alpha = a))
            // top page while it moves: a soft shadow just outside its leading edge
            val moving = t.currentState != t.targetState
            val top = moving && ((!popping && t.targetState == EnterExitState.Visible) || (popping && t.targetState == EnterExitState.PostExit))
            if (top) {
                val w = 14.dp.toPx()
                val left = BackMotion.edgeSign > 0
                drawRect(
                    Brush.horizontalGradient(if (left) listOf(Color.Transparent, shadowColor.copy(alpha = 0.22f)) else listOf(shadowColor.copy(alpha = 0.22f), Color.Transparent),
                        startX = if (left) -w else size.width, endX = if (left) 0f else size.width + w),
                    topLeft = Offset(if (left) -w else size.width, 0f), size = Size(w, size.height))
            }
        }
}
