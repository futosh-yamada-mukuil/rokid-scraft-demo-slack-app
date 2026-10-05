package com.scraft.glassreport.capture.camera

/**
 * 「1枚撮る」だけを表す抽象。画面が CameraX の `ImageCapture` を包んで ViewModel に渡す。
 *
 * ViewModel から CameraX 型を締め出すためのもの。`androidx.camera.core.ImageCapture` は
 * final かつ JVM 上でクラス初期化できない（ユニットテストで生成もモックもできない）ので、
 * ここで挟んでおかないと撮影フローの状態遷移をテストできない。
 */
fun interface PhotoSource {
    suspend fun takePhoto(): CapturedPhoto
}

/**
 * 「録画を始める」だけを表す抽象。停止は返ってきた [VideoSession] に対して行う。
 * 挟む理由は [PhotoSource] と同じ。
 */
fun interface VideoSource {
    fun startRecording(): VideoSession
}
