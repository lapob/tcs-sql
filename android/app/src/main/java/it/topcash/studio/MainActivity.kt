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
}

private fun AnimatorSet.doOnEndCompat(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
    override fun onAnimationEnd(animation: android.animation.Animator) = block()
})
