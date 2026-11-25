package net.extrasensors.watch.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import net.extrasensors.watch.AppGraph
import net.extrasensors.watch.Permissions
import net.extrasensors.watch.graph
import net.extrasensors.watch.service.SensorService
import net.extrasensors.watch.service.ServiceController
import net.extrasensors.watch.ui.pages.BaroPage
import net.extrasensors.watch.ui.pages.ClimbPage
import net.extrasensors.watch.ui.pages.LightPage
import net.extrasensors.watch.ui.pages.PAGE_COUNT
import net.extrasensors.watch.ui.pages.RainPage
import net.extrasensors.watch.ui.pages.SkinPage
import net.extrasensors.watch.ui.pages.SoundPage
import net.extrasensors.watch.ui.pages.SummaryPage
import net.extrasensors.watch.ui.pages.SunPage
import net.extrasensors.watch.ui.pages.SweatPage

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PAGE = "page"
        const val SETTINGS_PAGE = 9
    }

    /** Pedidos de troca de página vindos de notificações e da complicação. */
    private val pageRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = graph
        val initialPage = intent.getIntExtra(EXTRA_PAGE, 0).coerceIn(0, PAGE_COUNT - 1)
        setContent {
            MaterialTheme {
                AppScaffold {
                    App(g, initialPage, pageRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasExtra(EXTRA_PAGE)) pageRequests.tryEmit(intent.getIntExtra(EXTRA_PAGE, 0))
    }
}

private object Routes {
    const val PAGER = "pager"
    const val SETTINGS = "settings"
    const val EDIT = "edit/{key}"
    const val CALIBRATE = "calibrate"
    const val GENDER = "gender"
    const val ABOUT = "about"
    const val SKIN_TYPE = "skin_type"
    fun edit(s: NumericSetting) = "edit/${s.name}"
}

@Composable
private fun App(g: AppGraph, initialPage: Int, pageRequests: MutableSharedFlow<Int>) {
    val nav = rememberSwipeDismissableNavController()
    val context = g.context

    // Notificações são pedidas na primeira abertura, antes de iniciar o serviço.
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        g.appScope.launch { ServiceController.startIfEnabled(context) }
    }
    LaunchedEffect(Unit) {
        if (Permissions.notificationsGranted(context)) ServiceController.startIfEnabled(context)
        else notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Ao abrir o app: envia dados pendentes ao celular e pede UV se a previsão estiver velha.
        g.appScope.launch {
            net.extrasensors.watch.sync.WatchSync.push(context, force = true)
            net.extrasensors.watch.sync.WatchSync.requestUvIfStale(context)
        }
    }

    SwipeDismissableNavHost(navController = nav, startDestination = Routes.PAGER) {
        composable(Routes.PAGER) {
            Pager(g, initialPage, pageRequests, openSettingsList = { nav.navigate(Routes.SETTINGS) }, openAbout = { nav.navigate(Routes.ABOUT) })
        }
        composable(Routes.SETTINGS) {
            SettingsListScreen(
                g,
                onEdit = { nav.navigate(Routes.edit(it)) },
                onCalibrate = { nav.navigate(Routes.CALIBRATE) },
                onGender = { nav.navigate(Routes.GENDER) },
                onSkinType = { nav.navigate(Routes.SKIN_TYPE) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.EDIT) { entry ->
            val key = entry.arguments?.getString("key")
            val setting = NumericSetting.entries.firstOrNull { it.name == key } ?: return@composable
            EditSettingScreen(g, setting) { nav.popBackStack() }
        }
        composable(Routes.CALIBRATE) { CalibrateScreen(g) { nav.popBackStack() } }
        composable(Routes.GENDER) { GenderScreen(g) { nav.popBackStack() } }
        composable(Routes.ABOUT) { AboutScreen() }
        composable(Routes.SKIN_TYPE) { SkinTypeScreen(g) { nav.popBackStack() } }
    }
}

@Composable
private fun Pager(
    g: AppGraph,
    initialPage: Int,
    pageRequests: MutableSharedFlow<Int>,
    openSettingsList: () -> Unit,
    openAbout: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val goTo: (Int) -> Unit = { p -> scope.launch { pagerState.animateScrollToPage(p) } }
    LaunchedEffect(Unit) { pageRequests.collect { pagerState.scrollToPage(it.coerceIn(0, PAGE_COUNT - 1)) } }
    val ctx = g.context
    val send: (String) -> Unit = { action -> ServiceController.send(ctx, action) }
    val openSettingsPage = { goTo(MainActivity.SETTINGS_PAGE) }

    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(
            state = pagerState,
            // A coroa/bezel troca de página.
            rotaryScrollableBehavior = RotaryScrollableDefaults.snapBehavior(pagerState),
        ) { page ->
            when (page) {
                0 -> SummaryPage(g, onOpen = goTo)
                1 -> BaroPage(g)
                2 -> RainPage(g, openSettingsPage)
                3 -> ClimbPage(g, start = { send(SensorService.ACTION_CLIMB_START) }, stop = { send(SensorService.ACTION_CLIMB_STOP) })
                4 -> LightPage(g)
                5 -> SunPage(g, openSettingsPage)
                6 -> SoundPage(g)
                7 -> SkinPage(g)
                8 -> SweatPage(
                    g,
                    start = { send(SensorService.ACTION_SWEAT_START) },
                    finish = { send(SensorService.ACTION_SWEAT_FINISH) },
                    reset = { send(SensorService.ACTION_SWEAT_RESET) },
                    openSettings = openSettingsList,
                )
                else -> SettingsPage(open = openSettingsList, about = openAbout)
            }
        }
    }
}
