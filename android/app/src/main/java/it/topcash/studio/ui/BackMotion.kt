package it.topcash.studio.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner

/**
 * Going back has two characters, like the system apps on Android 15/16:
 *  - predictive gesture (gradual): the page turns into a card that shrinks, rounds its corners and follows the finger
 *    towards the edge it was swiped from, while the previous page comes up from underneath, darker, then lit.
 *    The timeline is linear because NavHost moves it with the finger; letting go finishes it, cancelling puts it back.
 *  - back key / button (instant): Material 3 "shared axis X": the page slides away accelerating and fades out fast,
 *    the previous page arrives from the left decelerating (emphasized easing), its shade lifting.
 * The gesture is read from the same dispatcher NavHost listens to, without intercepting it.
 */
internal object BackMotion {
    const val GESTURE_MS = 400
    private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)   // Material 3 emphasized accelerate
    private var dispatcher: NavigationEventDispatcher? = null
    private var lastGestureEnd = 0L
    private var sawGesture = false
    /** state read by every page to draw corners and shade */
    var popping by mutableStateOf(false)
    var gesture by mutableStateOf(false)
    private var edgeSign = 1   // +1: the page leaves to the right (swipe from the left edge), -1: to the left

    @Composable fun Attach() {
        val d = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
        dispatcher = d
        LaunchedEffect(d) {
            d?.transitionState?.collect { s ->
                // set before NavHost composes the page underneath, so its first frame is already shaded
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
        if (live) edgeSign = if ((s as NavigationEventTransitionState.InProgress).latestEvent.swipeEdge == NavigationEvent.EDGE_RIGHT) -1 else 1
        gesture = g; popping = true
    }
    fun forward() { popping = false; gesture = false }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition {
        begin()
        return if (gesture)
            slideInHorizontally(tween(GESTURE_MS, easing = LinearEasing)) { -(it * 0.10f * edgeSign).toInt() } +
                scaleIn(tween(GESTURE_MS, easing = LinearEasing), initialScale = 0.95f)
        else
            slideInHorizontally(tween(420, easing = EmphasizedDecelerate)) { -(it * 0.08f).toInt() } +
                fadeIn(tween(300, 60, EmphasizedDecelerate))
    }

    fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition {
        begin()
        return if (gesture)
            slideOutHorizontally(tween(GESTURE_MS, easing = LinearEasing)) { (it * 0.55f * edgeSign).toInt() } +
                scaleOut(tween(GESTURE_MS, easing = LinearEasing), targetScale = 0.86f) +
                // stays solid while the finger holds it, fades only once it is on its way out
                fadeOut(keyframes { durationMillis = GESTURE_MS; 1f at 0; 1f at (GESTURE_MS * 0.5f).toInt(); 0f at GESTURE_MS })
        else
            slideOutHorizontally(tween(260, easing = Accelerate)) { (it * 0.14f).toInt() } +
                scaleOut(tween(260, easing = Accelerate), targetScale = 0.97f) +
                fadeOut(tween(170, easing = Accelerate))
    }
}

/** Every page: opaque, so the leaving card hides what is under it; rounded while it follows the finger;
 *  the page coming back starts shaded and lights up. Everything is read in the draw phase: no recomposition per frame. */
@Composable fun Modifier.navCard(scope: AnimatedVisibilityScope): Modifier {
    val t = scope.transition
    val popping = BackMotion.popping
    val gesture = BackMotion.gesture
    val corner = t.animateDp(transitionSpec = { tween(if (gesture) BackMotion.GESTURE_MS / 3 else 1, easing = LinearEasing) }, label = "corner") {
        if (it == EnterExitState.PostExit && popping && gesture) 30.dp else 0.dp
    }
    val shade = t.animateFloat(transitionSpec = {
        if (gesture) tween(BackMotion.GESTURE_MS, easing = LinearEasing) else tween(420, easing = EmphasizedDecelerate)
    }, label = "shade") { if (it == EnterExitState.PreEnter && popping) (if (gesture) 0.42f else 0.22f) else 0f }
    return this.fillMaxSize()
        .graphicsLayer {
            val r = corner.value.toPx()
            if (r > 0.5f) { shape = RoundedCornerShape(r); clip = true } else clip = false
        }
        .background(MaterialTheme.colorScheme.background)
        .drawWithContent {
            drawContent()
            val a = shade.value
            if (a > 0.005f) drawRect(Color.Black.copy(alpha = a))
            // the card in the hand gets a hairline of light along its rounded edge: it reads as a sheet, even dark on dark
            val r = corner.value.toPx()
            if (r > 0.5f) drawRoundRect(Color.White.copy(alpha = 0.10f * (r / 30.dp.toPx()).coerceAtMost(1f)),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        }
}
