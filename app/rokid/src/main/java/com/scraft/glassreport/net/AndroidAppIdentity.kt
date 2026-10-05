package com.scraft.glassreport.net

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.security.MessageDigest

private const val TAG = "AndroidAppIdentity"

/**
 * Google の API キーに「Android アプリ制限」を掛けたときに要求される 2 ヘッダの値。
 *
 * ```
 * X-Android-Package: com.scraft.glassreport
 * X-Android-Cert:    <署名証明書の SHA-1（コロン無し・大文字）>
 * ```
 *
 * 現状のキーは制限なしで通るが、**付けても無害**なので常に送る。
 * 配布前にコンソールでアプリ制限を掛けても、アプリ側の実装を変えずに済む。
 */
data class AndroidAppIdentity(
    val packageName: String,
    val certSha1: String,
)

/**
 * 実行中の APK の署名から [AndroidAppIdentity] を作る。取得できなければ null（ヘッダを付けない）。
 * debug ビルドは debug キーストアの SHA-1 になるため、コンソールには両方を登録する必要がある。
 */
fun androidAppIdentity(context: Context): AndroidAppIdentity? =
    runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()
        signer?.let { AndroidAppIdentity(context.packageName, sha1Hex(it.toByteArray())) }
    }.getOrElse {
        Log.w(TAG, "署名証明書を取得できないため、アプリ制限ヘッダを付けない", it)
        null
    }

/** SHA-1 を Google が要求する形（コロン無し・大文字の 16 進 40 桁）で返す。 */
internal fun sha1Hex(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-1")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02X".format(byte) }
