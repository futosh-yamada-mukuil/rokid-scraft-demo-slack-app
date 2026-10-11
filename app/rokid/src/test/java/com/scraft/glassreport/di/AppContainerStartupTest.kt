package com.scraft.glassreport.di

import android.content.Context
import android.content.SharedPreferences
import com.scraft.glassreport.data.prefs.FakeSharedPreferences
import com.scraft.glassreport.net.MIME_JPEG
import com.scraft.glassreport.test.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * アプリ起動時に `SCRAFTApp.onCreate` が行う依存の組み立てを、実物の [AppContainer] でそのまま実行する。
 *
 * 画面は起動直後にここから受け取ったものを使い始める（ロビーの取得・通知の監視・撮影画面の宛先と音声認識）。
 * 初期化の順序や配線を誤ると、端末では起動直後に落ちる。その種類の不具合を JVM で検出する。
 * 画面の描画・カメラ・録音・JNI は対象外（端末でしか動かない）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerStartupTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    /** 端末の代わり。設定の保存先だけ用意し、Keystore・アセット・署名は「使えない端末」と同じ振る舞いにする。 */
    private fun device(): Context {
        val prefs = mutableMapOf<String, SharedPreferences>()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.packageName).thenReturn("com.scraft.glassreport")
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenAnswer { call ->
            prefs.getOrPut(call.getArgument(0)) { FakeSharedPreferences() }
        }
        return context
    }

    @Test
    fun everythingTheFirstScreensNeedCanBeBuiltAndUsedWithoutThrowing() =
        runTest(mainDispatcherRule.dispatcher) {
            val container = AppContainer(device())

            // ロビーと通知。未設定・未登録でも例外ではなく「表示できる失敗」で返る。
            val lobby = container.lobbyRepository.latest()
            val notification = container.channelNotificationRepository.latest()
            assertTrue(lobby.isFailure && notification.isFailure)
            assertNotNull(lobby.exceptionOrNull()?.message)
            // 画面は表示中ずっと更新の合図を購読する。購読を始めて、離れるところまで。
            val watching = backgroundScope.launch { container.channelNotificationRepository.updates.collect {} }
            runCurrent()
            watching.cancel()
            assertNull(container.seenPosts.claimNewest(emptyList()))

            // 認証（端末登録）の状態確認は通信しない。
            container.signInFlow.signedIn()
            assertNull(container.signInFlow.accessToken())

            // 撮影画面が開くときに受け取るもの。
            container.sinks.forEach { assertTrue(it.accepts(MIME_JPEG)) }
            assertEquals(container.channelConfigured, container.sinks.isNotEmpty())
            val speech = container.newSpeechSource()
            speech.available
            speech.close()
            container.speechEngine
            container.captureSettings.state.value

            // 通知音と読み上げは画面ごとに作って閉じる。
            container.newSpeaker().close()
            assertNotNull(container.newChime())
            assertNotNull(container.translator)
            assertNotNull(container.glassConfig.load())
        }
}
