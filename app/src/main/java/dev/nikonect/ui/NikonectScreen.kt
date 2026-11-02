package dev.nikonect.ui

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.nikonect.camera.CameraController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val pages = listOf("监看", "参数", "相册", "设置")
private val pageOrder = listOf(1, 0, 2, 3)
@Composable
fun NikonectScreen(controller: CameraController = viewModel()) {
    val state by controller.state.collectAsStateWithLifecycle()
    var history by rememberSaveable(key = "destination_history_v030") { mutableStateOf(arrayListOf(1)) }
    val page = history.last()
    val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val navigate: (Int) -> Unit = { destination -> if (history.last() != destination) history = ArrayList(history + destination) }
    val screenStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(page, state.settings.immersive, state.settings.keepScreenOn, state.settings.landscapeMonitor) {
        val insets = WindowCompat.getInsetsController(activity.window, view)
        insets.isAppearanceLightStatusBars = false; insets.isAppearanceLightNavigationBars = false
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (page == 0 && state.settings.immersive) insets.hide(WindowInsetsCompat.Type.systemBars()) else insets.show(WindowInsetsCompat.Type.systemBars())
        if (state.settings.keepScreenOn && page == 0) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val orientation = if (page == 0 && state.settings.landscapeMonitor) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (activity.requestedOrientation != orientation) activity.requestedOrientation = orientation
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(lifecycle, state.settings.pauseInBackground) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && state.settings.pauseInBackground && !activity.isChangingConfigurations) controller.stopLiveView() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    BackHandler(history.size > 1) {
        history = ArrayList(history.dropLast(1))

    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8EDBCD), onPrimary = Color(0xFF102E2A), background = Color(0xFF101318), surface = Color(0xFF1B2027), secondaryContainer = Color(0xFF23453F), onSecondaryContainer = Color(0xFFC3F5E9))) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(if (page == 0 && state.settings.immersive) WindowInsets.displayCutout else WindowInsets.safeDrawing)) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val destination = page
                    screenStates.SaveableStateProvider(destination) {
                        if (destination == 0) MonitorPage(controller, state, onNavigate = navigate, onBack = { if (history.size > 1) history = ArrayList(history.dropLast(1)) else navigate(1) })
                        else Column(Modifier.fillMaxSize()) {
                            if (!landscape) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("NIKONECT / ${pages[destination]}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                                Text(if (state.connected) "已连接" else "未连接", style = MaterialTheme.typography.labelMedium)
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                when (destination) {
                                    1 -> ControlsPage(controller, state)
                                    2 -> GalleryPage(controller, active = page == 2)
                                    3 -> SettingsPage(controller, state)
                                }
                            }
                        }
                    }
                }
                if (page != 0) NavigationBar(Modifier.height(if (landscape) 48.dp else 64.dp), windowInsets = WindowInsets(0, 0, 0, 0)) {
                    pageOrder.filter { page != 0 || it != 0 }.forEach { destination ->
                        NavigationBarItem(selected = page == destination, onClick = { navigate(destination) }, icon = { AppIcon(PageIcons[destination], pages[destination]) }, label = if (landscape) null else { { Text(pages[destination]) } })
                    }
                }
            }
        }
    }
}
