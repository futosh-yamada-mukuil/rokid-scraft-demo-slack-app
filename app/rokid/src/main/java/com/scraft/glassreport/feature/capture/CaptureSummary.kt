package com.scraft.glassreport.feature.capture

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.scraft.glassreport.core.ui.GlassVisibleArea
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle

/** サムネイルの表示サイズ。480x640 の画面に収まるよう小さめに。 */
private val THUMBNAIL_SIZE = 40.dp

/**
 * 撮影サマリーと送信確認のポップアップ。
 *
 * 中身は「サムネイル・尺・（あれば）文字起こし」＋送信先確認の二択。
 * **既定の選択は「いいえ」**（誤送信を防ぐ）。前後スワイプで移動、タップで決定するのは
 * 呼び出し元（`CaptureScreen` → `CaptureViewModel`）の担当で、ここは見た目だけを持つ。
 */
@Composable
internal fun CaptureSummary(
    draft: CaptureDraft,
    confirm: ConfirmChoice,
    onDecide: (ConfirmChoice) -> Unit,
    modifier: Modifier = Modifier,
    note: String = "",
    alert: String = "",
    destination: String = "Slack",
    question: String = "${destination}に送信しますか？",
    sizeNotice: String = "",
    yesLabel: String = "はい",
    noLabel: String = "いいえ",
    verticalChoices: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    GlassVisibleArea(modifier = modifier.background(Color.Black.copy(alpha = 0.9f)), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 30.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, scheme.secondary, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = question,
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (verticalChoices) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    ChoiceButton(yesLabel, confirm == ConfirmChoice.YES, { onDecide(ConfirmChoice.YES) }, Modifier.fillMaxWidth())
                    ChoiceButton(noLabel, confirm == ConfirmChoice.NO, { onDecide(ConfirmChoice.NO) }, Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChoiceButton(
                        label = yesLabel,
                        selected = confirm == ConfirmChoice.YES,
                        onClick = { onDecide(ConfirmChoice.YES) },
                        modifier = Modifier.weight(1f),
                    )
                    ChoiceButton(
                        label = noLabel,
                        selected = confirm == ConfirmChoice.NO,
                        onClick = { onDecide(ConfirmChoice.NO) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (alert.isNotBlank()) {
                Text(
                    text = alert,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumbnail(draft)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    // 尺（動画は秒数／写真はサイズ）。
                    Text(
                        text = "${draft.mode.label} ・ ${draft.measure}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.primary,
                    )
                }
            }

            // 音声コメントの文字起こし。無いときは理由（note）を小さく出す。
            if (draft.comment.isNotBlank()) {
                Text(
                    text = "「${draft.comment}」",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (note.isNotBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.secondary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (sizeNotice.isNotBlank()) {
                Text(
                    text = sizeNotice,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.secondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Thumbnail(draft: CaptureDraft) {
    val scheme = MaterialTheme.colorScheme
    val frame =
        Modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, scheme.secondary, RoundedCornerShape(6.dp))
    val bitmap = draft.thumbnail
    if (bitmap == null) {
        // 端末側でサムネイルを作れなかった場合も欄は空けておく（レイアウトを揺らさない）。
        Box(modifier = frame.background(scheme.secondary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Text(text = draft.mode.label, style = MaterialTheme.typography.bodyMedium, color = scheme.secondary)
        }
    } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = frame,
        )
    }
}

/** 他画面と同じ緑地・黒い太字で現在の選択を示す。 */
@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionStyle = glassActionStyle(selected)
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = actionStyle.fontWeight,
        color = actionStyle.contentColor,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier =
            modifier
                .glassAction(actionStyle, onClick)
                .padding(vertical = 5.dp),
    )
}
