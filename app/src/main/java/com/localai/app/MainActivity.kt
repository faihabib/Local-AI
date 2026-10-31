package com.localai.app

import android.content.SharedPreferences
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import android.os.Bundle
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import com.localai.app.ui.ChatScreen
import com.localai.app.ui.CloudScreen
import com.localai.app.ui.ImageScreen
import com.localai.app.ui.SettingsScreen
import com.localai.app.ui.prefs
import com.localai.app.ui.theme.LocalAITheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var keepVisible = true
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()

        splash.setKeepOnScreenCondition { keepVisible }

        splash.setOnExitAnimationListener { provider ->
            try {
                val icon = provider.iconView

                // Phase 1 — BOUNCE
                val bounceX = android.animation.ObjectAnimator.ofFloat(
                    icon, View.SCALE_X, 1f, 1.35f, 0.85f, 1.15f, 1f)
                val bounceY = android.animation.ObjectAnimator.ofFloat(
                    icon, View.SCALE_Y, 1f, 1.35f, 0.85f, 1.15f, 1f)
                val bounce = AnimatorSet().apply {
                    playTogether(bounceX, bounceY)
                    duration = 600
                    interpolator = DecelerateInterpolator()
                }

                // Phase 2 — ZOOM OUT + FADE (icon only)
                val zoomX = android.animation.ObjectAnimator.ofFloat(
                    icon, View.SCALE_X, 1f, 2.6f)
                val zoomY = android.animation.ObjectAnimator.ofFloat(
                    icon, View.SCALE_Y, 1f, 2.6f)
                val fadeIcon = android.animation.ObjectAnimator.ofFloat(
                    icon, View.ALPHA, 1f, 0f)
                val zoomOut = AnimatorSet().apply {
                    playTogether(zoomX, zoomY, fadeIcon)
                    duration = 450
                    interpolator = AccelerateInterpolator()
                }

                AnimatorSet().apply {
                    playSequentially(bounce, zoomOut)
                    start()
                }

                icon.postDelayed({ provider.remove() }, 1060)
            } catch (t: Throwable) {
                // Whatever goes wrong: remove the splash, keep the app alive
                try { provider.remove() } catch (_: Throwable) {}
            }
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalAITheme {
                ThemedApp(onReady = { keepVisible = false })
            }
        }


    }
}

@Composable
fun ThemedApp(onReady: () -> Unit = {}) {
    LaunchedEffect(Unit) {
        // Wait a fraction of a second for Compose to fully layout
        kotlinx.coroutines.delay(300)
        onReady()
    }
    val ctx = LocalContext.current
    val sp: SharedPreferences = remember { prefs(ctx) }
    var mode by remember {
        mutableStateOf(sp.getString("theme_mode", "System") ?: "System")
    }

    // Listen for live preference changes so theme flips immediately
    DisposableEffect(sp) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode") {
                mode = sp.getString("theme_mode", "System") ?: "System"
            }
        }
        sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val darkTheme = when (mode) {
        "Light" -> false
        "Dark" -> true
        "Monochrome" -> isSystemInDarkTheme()
        else -> isSystemInDarkTheme()
    }
    val monochrome = mode == "Monochrome"

    LocalAITheme(darkTheme = darkTheme, monochrome = monochrome) {
        MainApp()
    }
}

@Composable
fun MainApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }

    // First-launch onboarding check
    val ctxGuide = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val repo = com.localai.app.data.UserPreferencesRepository(ctxGuide.applicationContext)
        val seen = repo.onboardingSeenFlow.firstOrNull() ?: false
        if (!seen) {
            // Wait for the splash screen to completely finish its exit animation
            kotlinx.coroutines.delay(1600)
            showGuide = true
        }
    }

    if (showGuide) {
        com.localai.app.ui.GuideDialog(onDismiss = {
            showGuide = false
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val repo = com.localai.app.data.UserPreferencesRepository(ctxGuide.applicationContext)
                repo.setOnboardingSeen(true)
            }
        })
    }

    // Animated IME inset in pixels — this value changes smoothly as the
    // keyboard rises or falls (Compose animates WindowInsets internally).
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)

    val navItems = listOf(
        NavItem("Chat", Icons.Default.Chat),
        NavItem("Generate", Icons.Default.AutoAwesome),
        NavItem("Cloud", Icons.Default.Cloud),
        NavItem("Settings", Icons.Default.Settings)
    )

    // ---- System back navigation ----
    // Settings tab  -> Generate tab
    // Generate tab  -> Chat tab
    // Chat tab      -> exit app
    //
    // When a Settings sub-screen is open, its own BackHandler (declared inside
    // SettingsScreen) runs first and closes the sub-screen. Only when the
    // sub-screen is closed do these handlers fire.
    BackHandler(enabled = selectedTab == 3) { selectedTab = 2 }
    BackHandler(enabled = selectedTab == 2) { selectedTab = 1 }
    BackHandler(enabled = selectedTab == 1) { selectedTab = 0 }

    Scaffold(
        bottomBar = {
            // In fullscreen mode the entire nav bar is hidden so the chat
            // surface fills the screen. Double-tap in the chat toggles.
            if (fullscreen) return@Scaffold
            // The nav bar's reserved height shrinks as the animated IME inset
            // grows, so the whole content area follows the keyboard frame-by-
            // frame. No separate tween => perfectly smooth with the keyboard.
            val navBarHeightDp = 80.dp
            val navBarHeightPx = with(density) { navBarHeightDp.toPx() }
            val visiblePx = (navBarHeightPx - imeBottomPx).coerceAtLeast(0f)
            val visibleDp = with(density) { visiblePx.toDp() }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(visibleDp)
                    .clipToBounds()
            ) {
                NavigationBar(
                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    navItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) }
                        )
                    }
                }
            }
        }
        ,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> ChatScreen(
                fullscreen = fullscreen,
                onToggleFullscreen = { fullscreen = !fullscreen },
            )
                1 -> ImageScreen()
                2 -> CloudScreen()
                3 -> SettingsScreen()
            }
        }
    }
}

data class NavItem(val label: String, val icon: ImageVector)
