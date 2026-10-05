package com.scraft.glassreport.feature.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.scraft.glassreport.core.ui.GlassVisibleArea
import com.scraft.glassreport.core.ui.glassAction
import com.scraft.glassreport.core.ui.glassActionStyle

/** Stopped audio stays local until the user confirms transcription; this panel never sends media. */
@Composable
internal fun CommentReviewPanel(
    choice: CommentReviewChoice,
    onDecide: (CommentReviewChoice) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    GlassVisibleArea(
        modifier = Modifier.background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 30.dp)
                    .border(2.dp, scheme.secondary, RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("録音を一時停止中", style = MaterialTheme.typography.bodyLarge, color = scheme.primary)
            CommentReviewChoice.entries.forEach { entry ->
                val actionStyle = glassActionStyle(choice == entry)
                Text(
                    text =
                        when (entry) {
                            CommentReviewChoice.RETAKE -> "取り直し"
                            CommentReviewChoice.CONFIRM -> "確定"
                            CommentReviewChoice.CANCEL -> "キャンセル"
                        },
                    modifier = Modifier.fillMaxWidth().glassAction(actionStyle) { onDecide(entry) }.padding(vertical = 5.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = actionStyle.fontWeight,
                    color = actionStyle.contentColor,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                "キャンセルで録音を再開\nスワイプで選択・タップで決定",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.secondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
