package com.scraft.glassreport.domain.model

/** 長辺を縮めないことを表す値。実機の写真の最大は 4032x3024（12.2MP）。 */
const val PHOTO_EDGE_UNLIMITED = Int.MAX_VALUE

/**
 * 写真の画質。**撮影後に長辺を [maxEdge] まで縮めて JPEG 化する**（無加工なら縮めない）。
 *
 * 既定を 1920 にしているのは、1280（0.9MP）では現場の欠陥（傷など）が潰れるため。
 */
enum class PhotoQuality(
    val label: String,
    val maxEdge: Int,
    /** 設定画面に添える目安。 */
    val note: String,
) {
    EDGE_1280("1280", 1_280, "軽い・約100KB"),
    EDGE_1920("1920", 1_920, "既定・約250KB"),
    ORIGINAL("無加工", PHOTO_EDGE_UNLIMITED, "4032x3024・約2MB"),
    ;

    fun next(): PhotoQuality = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = EDGE_1920

        fun from(name: String?): PhotoQuality = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * 動画の画質。実機は 4K まで出せるが、**4K は選択肢に入れない**
 * （テザリング運用で転送が現実的でないため）。目安は実測ビットレート。
 */
enum class VideoQuality(
    val label: String,
    val width: Int,
    val height: Int,
    /** 設定画面に添える目安（3秒あたりの容量）。 */
    val note: String,
) {
    SD("SD", 640, 480, "640x480・3秒1.5MB"),
    HD("HD", 1_280, 720, "1280x720・3秒4.7MB"),
    FHD("FHD", 1_920, 1_080, "1920x1080・3秒7.5MB"),
    ;

    fun next(): VideoQuality = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = FHD

        fun from(name: String?): VideoQuality = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** 撮影の画質設定ひとまとめ。設定画面で変え、撮影画面が即座に従う。 */
data class CaptureQualities(
    val photo: PhotoQuality = PhotoQuality.DEFAULT,
    val video: VideoQuality = VideoQuality.DEFAULT,
)
