package com.scraft.glassreport

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.scraft.glassreport.core.ui.theme.ScraftGlassTheme
import com.scraft.glassreport.feature.auth.AuthScreen
import com.scraft.glassreport.feature.auth.leaveAuth
import com.scraft.glassreport.feature.capture.CaptureScreen
import com.scraft.glassreport.feature.feed.FeedScreen
import com.scraft.glassreport.feature.home.BlackoutOverlay
import com.scraft.glassreport.feature.home.HomeScreen
import com.scraft.glassreport.feature.notification.AppVisibility
import com.scraft.glassreport.feature.notification.TeamsChatNotificationHost
import com.scraft.glassreport.feature.notification.TeamsPollingService
import com.scraft.glassreport.feature.readback.ReadbackScreen
import com.scraft.glassreport.feature.settings.SettingsScreen
import com.scraft.glassreport.preview.UiPreviewControls

/**
 * 起動時に開く画面を差し替える intent extra（開発中の確認用）。
 * 例: `adb shell am start -n com.scraft.glassreport/.MainActivity --es start readback`
 * 実機は装着しないと近接センサーで画面が消えるため、目的の画面へ直接飛べると確認が早い。
 */
const val EXTRA_START_ROUTE = "start"

/** 単一 Activity。画面遷移は Navigation-Compose で行う。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.AVD_CONTROLS) {
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
        // ホームで Teams の接続状況を表示し、必要なときにサインインする。
        // Teams 認証は Teams を実際に使った画面から必要になった時だけ開く。
        // intent extra が付いていればそれを最優先（開発中の確認用）。
        val start =
            intent?.getStringExtra(EXTRA_START_ROUTE)?.takeIf { it.isNotBlank() }
                ?: Routes.HOME
        setContent {
            ScraftGlassTheme {
                Box(Modifier.fillMaxSize()) {
                    AppNavHost(startRoute = start, onExit = { finish() })
                    if (BuildConfig.AVD_CONTROLS) {
                        UiPreviewControls(
                            onKey = { key ->
                                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                                dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                            },
                            onBack = { onBackPressedDispatcher.onBackPressed() },
                            onReply = (application as SCRAFTApp).container.uiPreview?.let { { it.addReply() } },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.activityVisible = true
        if (!BuildConfig.UI_PREVIEW) TeamsPollingService.startIfReady(this)
    }

    override fun onStop() {
        AppVisibility.activityVisible = false
        super.onStop()
    }
}

/** 画面のルート。Phase 2 で COMMENT を足す。 */
object Routes {
    const val HOME = "home"
    const val AUTH = "auth"
    const val CAPTURE = "capture"
    const val CAPTURE_PHOTO = "capture-photo"
    const val FEED = "feed"
    const val SETTINGS = "settings"

    /** Phase 4 の確認用（読み上げ・英訳読み上げ）。Feed が入ればメニューから呼ぶ形に置き換わる。 */
    const val READBACK = "readback"
}

/**
 * 画面グラフ。起動直後はホーム画面を出し、そこから撮影・フィード・設定へ分岐する。
 * 操作設計どおりに Feed を起動画面へ移す（startRoute の既定を FEED にする）のは、
 * 通しで動くことを実機で確かめてから。
 */
@Composable
fun AppNavHost(
    startRoute: String = Routes.HOME,
    onExit: () -> Unit = {},
) {
    val activityContext = LocalContext.current
    val app = activityContext.applicationContext as SCRAFTApp
    val nav = rememberNavController()
    var blackout by remember { mutableStateOf(false) }
    // 戻る先が無い（起動直後の画面）なら BACK / ダブルタップでアプリを閉じる。
    val back: () -> Unit = { if (!nav.popBackStack()) onExit() }
    // 「サインインが切れた」と言ってきた画面から認証を積む。どの画面から呼んでも同じ形で、
    // 抜けるときは AUTH 側が popBackStack で呼び出し元へ返す（下書きを抱えたまま待っている）。
    val signIn: () -> Unit = { nav.navigate(Routes.AUTH) { launchSingleTop = true } }

    val currentEntry = nav.currentBackStackEntryAsState().value
    val currentRoute = currentEntry?.destination?.route
    val notificationSourceConfigured = app.container.channelConfigured

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = startRoute) {
            composable(Routes.HOME) {
                HomeScreen(
                    onPhoto = { nav.navigate(Routes.CAPTURE_PHOTO) },
                    onFeed = { nav.navigate(Routes.FEED) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onHide = { blackout = true },
                    onExit = onExit,
                    onBack = back,
                    onSignIn = signIn,
                )
            }
            composable(Routes.AUTH) {
                // 起動時の認証（戻る先が無い）はホームへ。画面内から積まれた認証は
                // **popBackStack で呼び出し元へ返す**（navigate で積み直すと NavBackStackEntry が
                // 別物になり、撮影画面が抱えている下書きごと ViewModel が作り直されて消える）。
                // 呼び出し元の判定は画面名に依らないので、記録一覧から積まれても同じように戻る。
                val done = {
                    TeamsPollingService.startIfReady(activityContext)
                    // Navigation graph 自身の BackStackEntry は previous として見えるが、
                    // 起動時の Auth に実際の呼び出し元は無い。これを caller と誤認すると
                    // pop 後に startDestination の Auth が再表示されて抜けられなくなる。
                    val hasCaller = startRoute != Routes.AUTH && nav.previousBackStackEntry != null
                    leaveAuth(
                        hasCaller = hasCaller,
                        popBackStack = { nav.popBackStack() },
                        goHome = {
                            nav.navigate(Routes.HOME) {
                                popUpTo(Routes.AUTH) { inclusive = true }
                                launchSingleTop = true
                            }
                        },
                    )
                }
                // ロビーから来た場合は同じロビーへ戻す。ロビー側で自動再表示は一度だけに抑え、
                // 次回は利用者が「サインイン」を押したときだけコードを開く。
                // Feedから来た場合は自動取得が再び認証を積むため、従来どおりホームへ戻す。
                val skip = {
                    if (nav.previousBackStackEntry?.destination?.route == Routes.HOME) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                            launchSingleTop = true
                        }
                    }
                    Unit
                }
                AuthScreen(onDone = done, onSkip = skip)
            }
            composable(Routes.CAPTURE) {
                CaptureScreen(
                    onBack = back,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    // Teams への送信がサインイン切れで弾かれたら、その場で入り直せるようにする。
                    onSignIn = signIn,
                )
            }
            composable(Routes.CAPTURE_PHOTO) {
                CaptureScreen(
                    mode = com.scraft.glassreport.feature.capture.CaptureMode.PHOTO,
                    onBack = back,
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onSignIn = signIn,
                )
            }
            composable(Routes.FEED) {
                FeedScreen(
                    onBack = back,
                    // メニューの「写真を撮る」→ 撮影画面（外部設計書の feed → menu → capture）。
                    onCapture = { nav.navigate(Routes.CAPTURE_PHOTO) },
                    // 取得がサインイン切れで弾かれたら、その場で入り直せるようにする。
                    // 撮影画面と同じ経路なので、認証を抜けたらこの画面へ popBackStack で戻る。
                    onSignIn = signIn,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = back)
            }
            composable(Routes.READBACK) {
                ReadbackScreen(onBack = back)
            }
        }

        // Feed 表示中も既読IDだけ同期して二重通知を避ける。認証画面ではコードを隠さないよう監視ごと止める。
        if (currentRoute != null &&
            notificationSourceConfigured &&
            currentRoute != Routes.AUTH
        ) {
            TeamsChatNotificationHost(
                suppressed = currentRoute == Routes.FEED,
            )
        }
        if (blackout) BlackoutOverlay(onRestore = { blackout = false })
    }
}
