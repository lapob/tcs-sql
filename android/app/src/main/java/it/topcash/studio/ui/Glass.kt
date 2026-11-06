package it.topcash.studio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/**
 * Frosted-glass bars: content scrolls *under* the top bar and the bottom navigation and is blurred live
 * (GPU RenderEffect on Android 12+, translucent scrim on older versions).
 */
val LocalAppHaze = compositionLocalOf<HazeState?> { null }
/** Height of the glass bottom navigation over the current page (0 on detail screens): keeps FABs above it. */
val LocalBottomBar = compositionLocalOf { androidx.compose.ui.unit.Dp(0f) }

fun Modifier.hazeSourceCompat(state: HazeState): Modifier = this.then(Modifier.hazeSource(state))

@Composable fun Modifier.glass(state: HazeState?, strong: Boolean = false): Modifier {
    if (state == null) return this
    val c = MaterialTheme.colorScheme.surfaceContainer
    return this.hazeBlur(HazeInput.Sources(state), style = if (strong) HazeMaterials.thin(c) else HazeMaterials.ultraThin(c))
}
