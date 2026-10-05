package com.scraft.glassreport.core.ui

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.scraft.glassreport.R

/**
 * ロゴの不透明度。
 *
 * 【実機で分かったこと】このグラスの単眼ディスプレイは**加算式**（光を足す方式）で、
 * しかも輝度に余裕がない。**screencap に写っていても肉眼では見えない**ことがある
 * （framebuffer の値がそのまま目に届かない）。実測した組み合わせ:
 *
 * - `#39FF14`(primary) × 0.7・26dp … 見えるが小さく「よく分からない」
 * - `Color.Black` … 黒＝光を出さない＝透明。原理的に消える（capture には写る）
 * - `#1FAA0C`(secondary) × 0.5・39dp … **肉眼では見えない**（暗すぎた）
 * - `#39FF14`(primary) × 1.0・39dp … 現在の設定
 *
 * 眩しさを抑える必要が出た場合、**この値を下げるのではなく**（下げると消える）、
 * 塗りではなく輪郭線のベクターに差し替えて発光面積を減らすこと。
 */
private const val LOGO_ALPHA = 1.0f

/**
 * `logo_mukuil.xml` の viewport（176x34）の縦横比。
 *
 * `Icon` は painter の既定サイズを使うため、`Modifier.height()` だけでは幅が追従しない。
 * 高さに追従して幅も伸びるよう、ここで縦横比を固定する。
 */
private const val LOGO_ASPECT = 176f / 34f

/**
 * 株式会社ムクイルの公式シンボルと英字表記 MUKUIL。
 *
 * `logo_mukuil.xml` を、グラスの表示色で tint して使う。
 * 大きさは `modifier` に `Modifier.height(...)` を渡せば、[LOGO_ASPECT] で幅も追従する。
 */
@Composable
fun VendorLogo(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Icon(
        painter = painterResource(R.drawable.logo_mukuil),
        contentDescription = stringResource(R.string.vendor_name),
        tint = tint.copy(alpha = LOGO_ALPHA),
        modifier = modifier.aspectRatio(LOGO_ASPECT),
    )
}
