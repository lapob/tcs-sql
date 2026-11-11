package it.topcash.studio

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import android.view.animation.AnticipateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import it.topcash.studio.data.AppViewModel
import it.topcash.studio.data.Screen
import it.topcash.studio.ui.AppRoot
import it.topcash.studio.ui.TopCashTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(applicationContext)
        val splash = installSplashScreen()
        // keep the splash until we know where to go (cached archive / session check), then zoom it away
        splash.setKeepOnScreenCondition { vm.screen.value == Screen.Loading }
        splash.setOnExitAnimationListener { p ->
            val icon: View = p.iconView
            AnimatorSet().apply {
                playTogether(ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 1.35f), ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 1.35f),
                    ObjectAnimator.ofFloat(p.view, View.ALPHA, 1f, 0f))
                interpolator = AnticipateInterpolator(1.2f); duration = 420L
                doOnEndCompat { p.remove() }
                start()
            }
        }
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShortcut(intent)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(0x00000000, 0x00000000), navigationBarStyle = SystemBarStyle.auto(0x00000000, 0x00000000))
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = vm.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = vm.setForeground(false)
        })
        setContent {
            val theme by vm.theme.collectAsStateWithLifecycle()
            TopCashTheme(theme) { AppRoot(vm) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleShortcut(intent) }
    private fun handleShortcut(i: android.content.Intent?) {
        vm.shortcut.value = when (i?.action) { "it.topcash.studio.NEW_INTERVENTION" -> "new"; "it.topcash.studio.LOAD_STOCK" -> "load"; "it.topcash.studio.SEARCH_CLIENT" -> "search"; else -> return }
    }
}

private fun AnimatorSet.doOnEndCompat(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = block()
})

/** Records an unexpected closing of the app (the error, never personal data) so it can be sent to the office server
 *  at the next start and fixed without needing the phone on a cable. */
object CrashLog {
    @Volatile private var installed = false
    fun file(ctx: android.content.Context) = java.io.File(ctx.filesDir, "crash.txt")
    fun install(ctx: android.content.Context) {
        if (installed) return; installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                file(ctx).writeText("${java.time.OffsetDateTime.now()} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · thread ${t.name}\n" + sw.toString().take(12_000))
            }
            previous?.uncaughtException(t, e)
        }
    }
}
