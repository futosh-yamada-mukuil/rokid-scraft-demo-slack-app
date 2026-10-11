package com.scraft.glassreport.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** 認識エンジンが返す形をそのまま食わせる。ここが崩れると Teams へ投稿するコメントに余計な空白が残る。 */
class TranscriptTextTest {
    @Test
    fun removesWordSpacesBetweenJapaneseCharacters() {
        assertEquals("配管から水が漏れています", TranscriptText.normalize(" 配管 から 水 が 漏れて います "))
    }

    @Test
    fun removesTheSpaceAfterALongVowelMark() {
        // 「ー」は Unicode 上カタカナではなく Common 扱い。現場の語（エラー・モーター・センサー）で必ず通る。
        assertEquals("エラー発生", TranscriptText.normalize("エラー 発生"))
        assertEquals("モーターのセンサーを交換", TranscriptText.normalize("モーター の センサー を 交換"))
    }

    @Test
    fun removesTheSpaceAroundJapanesePunctuation() {
        assertEquals("確認しました。送ります", TranscriptText.normalize("確認しました。 送ります"))
        assertEquals("はい、「異常なし」です", TranscriptText.normalize("はい、 「異常なし」 です"))
    }

    @Test
    fun removesWordSpacesInChinese() {
        assertEquals("请检查照片", TranscriptText.normalize("请 检查 照片"))
    }

    @Test
    fun keepsSpacesThatSeparateWordsInEnglishAndKorean() {
        assertEquals("please check the photo", TranscriptText.normalize("please check the photo"))
        assertEquals("사진을 확인해 주세요", TranscriptText.normalize("사진을 확인해 주세요"))
    }

    @Test
    fun keepsTheSpaceBetweenLatinAndJapanese() {
        assertEquals("Wi-Fi が切れました", TranscriptText.normalize("Wi-Fi が 切れました"))
        assertEquals("3 号機は OK です", TranscriptText.normalize("3 号機 は OK です"))
    }

    @Test
    fun correctsTheObservedMisrecognitionOfShashin() {
        // 2026-10-04 に Slack へ実際に投稿された文字起こし。
        assertEquals("こちらが現場の写真。", TranscriptText.correct("こちらが現場の書真。"))
        assertEquals("写真を送ります", TranscriptText.correct("書真を送ります"))
        assertEquals("現場の写真です", TranscriptText.correct("現場の書真です"))
    }

    @Test
    fun correctionLeavesRealWordsAndOtherLanguagesAlone() {
        // 別の語をまたぐ並び。
        assertEquals("この文書真偽を確認してください", TranscriptText.correct("この文書真偽を確認してください"))
        assertEquals("清書真っ最中です", TranscriptText.correct("清書真っ最中です"))
        assertEquals("報告書真ん中の図を見てください", TranscriptText.correct("報告書真ん中の図を見てください"))
        // かなを含まない文は日本語と断定できないので触らない。
        assertEquals("這本書真好", TranscriptText.correct("這本書真好"))
        assertEquals("書真", TranscriptText.correct("書真"))
        assertEquals("Please check the photo", TranscriptText.correct("Please check the photo"))
    }

    @Test
    fun recordedRecognizerOutputsBecomeTheExpectedSentences() {
        // 同梱モデル（SenseVoice int8）が実際に返した生の出力。specs/オフライン音声認識.md の比較表と
        // 2026-10-04 の AVD 実行ログ（.local/emulator/offline-stt-device-test.txt）から。
        val recorded =
            mapOf(
                "現場 の 写真 を 確認 して ください。" to "現場の写真を確認してください。",
                "请检查现场的照片。" to "请检查现场的照片。",
                "Please check the photos from the site." to "Please check the photos from the site.",
                // 韓国語は語間の空白が意味を持つ。モデルの出力のまま残す。
                "현장 사진 을 확인 해 주세요." to "현장 사진 을 확인 해 주세요.",
                "현장 사진 을 확인 주세요." to "현장 사진 을 확인 주세요.",
            )
        recorded.forEach { (raw, expected) -> assertEquals(expected, TranscriptText.fromOfflineRecognizer(raw)) }
    }

    @Test
    fun commentsActuallyPostedToSlackAreCorrectedOnlyWhereTheyWereWrong() {
        // 2026-10-04 に #開発用 へ投稿された文字起こし（整形後）。誤りは 2 件目の「書真」だけ。
        val posted =
            mapOf(
                "はい、こんにちは、よろしくお願い。" to "はい、こんにちは、よろしくお願い。",
                "こちらが現場の書真。" to "こちらが現場の写真。",
                "はい、よろしくお願いいたします。" to "はい、よろしくお願いいたします。",
            )
        posted.forEach { (before, after) -> assertEquals(after, TranscriptText.fromOfflineRecognizer(before)) }
        // 整形は何度掛けても変わらない（確認画面で保持した文を再送しても崩れない）。
        posted.values.forEach { assertEquals(it, TranscriptText.fromOfflineRecognizer(it)) }
    }

    @Test
    fun joinSkipsEmptySegmentsAndSpacesOnlyWhereTheLanguageNeedsIt() {
        assertEquals("", TranscriptText.join(emptyList()))
        assertEquals("確認しました", TranscriptText.join(listOf("", " 確認しました ", "  ")))
        assertEquals("確認しました写真を送ります", TranscriptText.join(listOf("確認しました", "写真を送ります")))
        assertEquals("checked sending the photo", TranscriptText.join(listOf("checked", "sending the photo")))
    }
}
