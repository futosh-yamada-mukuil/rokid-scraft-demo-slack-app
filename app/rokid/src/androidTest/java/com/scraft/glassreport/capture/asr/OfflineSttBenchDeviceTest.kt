package com.scraft.glassreport.capture.asr

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 同梱モデルを端末上で動かし、認識結果と所要時間を一覧で返す。
 *
 * - 入力は開発者が用意した合成音声だけ（app-private の `files/offline-stt-bench/`）。
 *   `manifest.tsv` に「ファイル名<TAB>期待文」を 1 行ずつ書く。マイク・カメラ・ネットワークは使わない。
 * - 各音声を従来どおり「認識の直前にモデルを読み込む」方法で認識する。
 * - `-e raw true` を付けると、後処理（空白の整形と既知の取り違えの修正）を通さないモデルの生の出力を、
 *   モデルが付けた言語・イベントの札（`<|ja|>` `<|Speech|>` など）と一緒に返す。
 * - `-e warm <件数>` を付けると、先頭からその件数だけ「録音中に先読みした場合」も測る。
 *   先読みを始めてから録音時間ぶん待って認識し、**結果が同じであること**を確かめる。
 *
 * 一致の判定はここでは行わない（精度は呼び出し側が集計する）。先読みで結果が変わった場合だけ失敗にする。
 */
class OfflineSttBenchDeviceTest : Instrumentation() {
    private var warmCount = 0
    private var raw = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        warmCount = arguments?.getString("warm")?.toIntOrNull() ?: 0
        raw = arguments?.getString("raw") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            val directory = File(targetContext.filesDir, "offline-stt-bench")
            val entries =
                File(directory, "manifest.tsv")
                    .readLines()
                    .filter { it.contains('\t') }
                    .map { it.substringBefore('\t') to it.substringAfter('\t') }
            val transcriber =
                if (raw) {
                    OfflineWavTranscriber(targetContext) { "${it.lang}${it.emotion}${it.event} ${it.text}" }
                } else {
                    OfflineWavTranscriber(targetContext)
                }
            check(transcriber.available) { transcriber.unavailableReason }
            val report = StringBuilder()
            runBlocking {
                entries.forEachIndexed { index, (name, expected) ->
                    val wav = File(directory, name).readBytes()
                    var started = SystemClock.elapsedRealtime()
                    val cold = transcriber.transcribe(wav).getOrThrow()
                    val coldMs = SystemClock.elapsedRealtime() - started

                    var warm = "-"
                    var warmMs = -1L
                    if (index < warmCount) {
                        transcriber.prepare()
                        delay(RECORDING_MS)
                        started = SystemClock.elapsedRealtime()
                        warm = transcriber.transcribe(wav).getOrThrow()
                        warmMs = SystemClock.elapsedRealtime() - started
                        check(warm == cold) { "$name: 先読みで結果が変わりました cold=$cold warm=$warm" }
                    }
                    // 入力は合成音声の定型文で、利用者の録音ではない。
                    val line = "BENCH\t$name\t$expected\t$cold\t$coldMs\t$warmMs\n"
                    report.append(line)
                    sendStatus(0, Bundle().apply { putString("stream", line) })
                }
                // 最後に先読みだけして使わない。保持期限で解放されることを確かめる（メモリを抱えたままにしない）。
                transcriber.prepare()
                delay(1_500)
                val heldKb = Debug.getPss()
                check(OfflineWavTranscriber.busy.value) { "先読みした認識器を保持していません" }
                transcriber.cancelPrepared()
                delay(1_500)
                check(!OfflineWavTranscriber.busy.value) { "使わなかった認識器が解放されていません" }
                report.append("BENCH_MEMORY\theldPssKb=$heldKb\treleasedPssKb=${Debug.getPss()}\n")
            }
            transcriber.close()
            result.putString("stream", "OK (${entries.size} files)\n$report")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private companion object {
        /** 録音の上限と同じ長さ。先読みはこの間に終わっている想定。 */
        const val RECORDING_MS = 5_000L
    }
}
