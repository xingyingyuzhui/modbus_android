package io.github.xingyingyuzhui.modbusmobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass
import io.github.xingyingyuzhui.modbusmobile.application.SessionManager
import io.github.xingyingyuzhui.modbusmobile.application.SettingsRepository
import io.github.xingyingyuzhui.modbusmobile.ui.connection.ConnectionScreen
import io.github.xingyingyuzhui.modbusmobile.ui.curve.CurveFullscreenScreen
import io.github.xingyingyuzhui.modbusmobile.ui.mine.MineScreen
import io.github.xingyingyuzhui.modbusmobile.ui.panel.PanelScreen
import io.github.xingyingyuzhui.modbusmobile.ui.panel.PanelWizardScreen
import io.github.xingyingyuzhui.modbusmobile.ui.shell.CurveRoutePattern
import io.github.xingyingyuzhui.modbusmobile.ui.shell.curveRoute
import io.github.xingyingyuzhui.modbusmobile.ui.shell.isCurveRoute
import io.github.xingyingyuzhui.modbusmobile.ui.shell.Destination
import io.github.xingyingyuzhui.modbusmobile.ui.shell.DockInsets
import io.github.xingyingyuzhui.modbusmobile.ui.shell.dockBottomGap
import io.github.xingyingyuzhui.modbusmobile.ui.shell.FloatingDock
import io.github.xingyingyuzhui.modbusmobile.ui.shell.FloatingRail
import io.github.xingyingyuzhui.modbusmobile.ui.shell.LocalDockInsets
import io.github.xingyingyuzhui.modbusmobile.ui.shell.LocalShellMode
import io.github.xingyingyuzhui.modbusmobile.ui.shell.ShellMode
import io.github.xingyingyuzhui.modbusmobile.ui.theme.LocalModbusColors
import io.github.xingyingyuzhui.modbusmobile.ui.theme.LocalUiSettings
import io.github.xingyingyuzhui.modbusmobile.ui.traffic.TrafficScreen
import io.github.xingyingyuzhui.modbusmobile.ui.workbench.WorkbenchScreen

val LocalSession = staticCompositionLocalOf<SessionManager> {
    error("SessionManager 还没有提供")
}

/** 应用级日志记录器；没有提供时（截图 / 单测）日志页自己建一个。 */
val LocalTrafficRecorder = staticCompositionLocalOf<io.github.xingyingyuzhui.modbusmobile.application.TrafficRecorder?> { null }

/** 工程文件存取；没有提供时（截图 / 单测）面板页按空工程显示。 */
val LocalProjectRepository = staticCompositionLocalOf<io.github.xingyingyuzhui.modbusmobile.application.panel.ProjectRepository?> { null }

val LocalPanelRuntime = staticCompositionLocalOf<io.github.xingyingyuzhui.modbusmobile.application.panel.PanelRuntime?> { null }

val LocalSettingsRepository = staticCompositionLocalOf<SettingsRepository> {
    error("SettingsRepository 还没有提供")
}

/** 历史会话和存储用量。没有提供时「我的」页按空历史显示，不碰数据库。 */
val LocalHistoryCatalog = staticCompositionLocalOf<io.github.xingyingyuzhui.modbusmobile.application.history.HistoryCatalog> {
    io.github.xingyingyuzhui.modbusmobile.application.history.EmptyHistoryCatalog
}

/** 从站配置文件。没有提供时导出使用内置的默认映射，不读磁盘。 */
val LocalSlaveSetupStore = staticCompositionLocalOf<io.github.xingyingyuzhui.modbusmobile.application.slave.SlaveSetupStore?> { null }

/** Activity 级的 ViewModelStoreOwner。工作台的表单、结果和轮询意图不随页面切换（含系统返回键）清空。 */
val LocalAppViewModelStoreOwner = staticCompositionLocalOf<androidx.lifecycle.ViewModelStoreOwner?> { null }

/** 打开“添加 / 编辑组件”向导页（面板编辑模式里调用）。 */
val LocalOpenWizard = staticCompositionLocalOf<() -> Unit> { {} }

const val PanelWizardRoute = "panel/wizard"

/** 打开某个曲线组件的全屏页（曲线卡的 ⤢）。 */
val LocalOpenCurve = staticCompositionLocalOf<(String) -> Unit> { {} }

/** 面板页把“当前面板里第一个曲线组件的 id”报给外壳（横屏自动全屏用）；离开面板页时报 null。 */
val LocalReportCurveTarget = staticCompositionLocalOf<(String?) -> Unit> { {} }

val LocalOpenConnection = staticCompositionLocalOf<() -> Unit> {
    error("连接页回调还没有提供")
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ModbusApp(startRoute: String = Destination.Connection.route) {
    val nav = rememberNavController()
    val appStoreOwner = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current
    val settings = LocalUiSettings.current
    val adaptive = currentWindowAdaptiveInfo()
    val portrait = adaptive.windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT &&
        adaptive.windowSizeClass.windowHeightSizeClass != WindowHeightSizeClass.COMPACT
    val current = nav.currentBackStackEntryAsState().value?.destination?.route
    val onCurve = isCurveRoute(current)
    val hideChrome = onCurve || current == PanelWizardRoute
    var suppressCurve by rememberSaveable { mutableStateOf(false) }
    var autoEntered by rememberSaveable { mutableStateOf(false) }
    var curveTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(portrait) {
        if (portrait) suppressCurve = false
    }
    LaunchedEffect(portrait, settings.landscapeCurveFullscreen, suppressCurve, current, curveTarget) {
        // 只在“面板页 + 横屏 + 当前面板有曲线”时自动进全屏，进哪个曲线由面板页报上来（没有曲线就不进，不放空页）
        val target = curveTarget
        if (!portrait && settings.landscapeCurveFullscreen && !suppressCurve && current == Destination.Panel.route && target != null) {
            autoEntered = true
            nav.navigate(curveRoute(target)) { launchSingleTop = true }
        } else if (portrait && autoEntered && onCurve) {
            autoEntered = false
            nav.popBackStack()
        }
    }
    val showDock = !hideChrome && portrait
    val showRail = !hideChrome && !portrait
    val bars = WindowInsets.navigationBars.asPaddingValues()
    val navBottom = bars.calculateBottomPadding()
    val navStart = bars.calculateStartPadding(LocalLayoutDirection.current)
    val dockGap = dockBottomGap(navBottom)
    val dockBottom = if (showDock) dockGap + 64.dp + navBottom else 0.dp
    val dockStart = if (showRail) 12.dp + 62.dp + 12.dp + navStart else 0.dp
    val openConnection: () -> Unit = {
        selectTab(nav, Destination.Connection)
    }
    val closeCurve: () -> Unit = {
        // 横屏下手动关掉：回到竖屏前不再自动进；竖屏下从 ⤢ 进来再关，不影响以后横屏自动进
        if (!portrait) suppressCurve = true
        autoEntered = false
        nav.popBackStack()
    }
    Box(Modifier.fillMaxSize().background(LocalModbusColors.current.bg)) {
        CompositionLocalProvider(
            LocalDockInsets provides DockInsets(start = dockStart, bottom = dockBottom),
            LocalShellMode provides if (portrait) ShellMode.PORTRAIT else ShellMode.LANDSCAPE,
            LocalOpenConnection provides openConnection,
            LocalOpenWizard provides { nav.navigate(PanelWizardRoute) { launchSingleTop = true } },
            LocalOpenCurve provides { id -> nav.navigate(curveRoute(id)) { launchSingleTop = true } },
            LocalReportCurveTarget provides { id -> curveTarget = id },
            LocalAppViewModelStoreOwner provides appStoreOwner,
        ) {
            NavHost(
                navController = nav,
                startDestination = startRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Destination.Connection.route) { ConnectionScreen() }
                composable(Destination.Workbench.route) { WorkbenchScreen() }
                composable(Destination.Panel.route) { PanelScreen() }
                composable(Destination.Log.route) { TrafficScreen() }
                composable(Destination.Mine.route) { MineScreen() }
                composable(PanelWizardRoute) { entry ->
                    val parent = remember(entry) { runCatching { nav.getBackStackEntry(Destination.Panel.route) }.getOrNull() }
                    if (parent == null) {
                        LaunchedEffect(Unit) { nav.popBackStack() }
                    } else {
                        PanelWizardScreen(parent) { nav.popBackStack() }
                    }
                }
                composable(
                    CurveRoutePattern,
                    arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.StringType; nullable = true; defaultValue = null }),
                ) { entry ->
                    // 和面板页共用同一个 ViewModel（工程 / 草稿 / 轮询）；没有面板页（直接进入）就用自己的
                    val parent = remember(entry) { runCatching { nav.getBackStackEntry(Destination.Panel.route) }.getOrNull() }
                    CurveFullscreenScreen(parent ?: entry, entry.arguments?.getString("id"), onClose = closeCurve)
                }
            }
        }
        if (showDock) {
            FloatingDock(
                current = current,
                onSelect = { selectTab(nav, it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = dockGap),
            )
        }
        if (showRail) {
            FloatingRail(
                current = current,
                onSelect = { selectTab(nav, it) },
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
                    .width(62.dp)
                    .fillMaxHeight(),
            )
        }
    }
}

private fun selectTab(nav: NavHostController, destination: Destination) {
    nav.navigate(destination.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
