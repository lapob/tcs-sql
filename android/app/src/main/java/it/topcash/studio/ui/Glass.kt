package it.topcash.studio.ui

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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

/**
 * Graphics profile chosen by the phone itself, never at random: phones Android marks as low-RAM, with under 3 GB of memory,
 * 4 cores or fewer, or Android older than 12 get solid bars instead of the live blur; battery saver does the same while it is on.
 */
object LiteGraphics {
    val on = mutableStateOf(false)
    private var weak: Boolean? = null
    private fun weak(ctx: Context): Boolean = weak ?: run {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        (am?.isLowRamDevice == true || mi.totalMem in 1 until 3L * 1024 * 1024 * 1024 || Runtime.getRuntime().availableProcessors() <= 4 || Build.VERSION.SDK_INT < 31).also { weak = it }
    }
    fun update(ctx: Context) { on.value = weak(ctx) || ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }
}

/** Keeps [LiteGraphics] current (battery saver switched on or off while the app is open). Call once at the root. */
@Composable fun WatchLiteGraphics() {
    val ctx = LocalContext.current
    DisposableEffect(ctx) {
        LiteGraphics.update(ctx)
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { LiteGraphics.update(ctx) } }
        ContextCompat.registerReceiver(ctx, r, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(r) } }
    }
}

@Composable fun Modifier.glass(state: HazeState?, strong: Boolean = false): Modifier {
    if (state == null) return this
    val c = MaterialTheme.colorScheme.surfaceContainer
    if (LiteGraphics.on.value) return this.background(c.copy(alpha = if (strong) 0.98f else 0.96f))
    // Balanced: the blur is computed on a downscaled copy → same frosted look, a fraction of the GPU work (no jank while scrolling)
    return this.hazeBlur(HazeInput.Sources(state), style = if (strong) HazeMaterials.thin(c) else HazeMaterials.ultraThin(c), performanceMode = dev.chrisbanes.haze.HazePerformanceMode.Balanced)
}
