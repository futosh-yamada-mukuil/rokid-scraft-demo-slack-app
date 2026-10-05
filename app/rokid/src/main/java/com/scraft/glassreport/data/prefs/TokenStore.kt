// Jetpack Security（EncryptedSharedPreferences / MasterKey）は 1.1.0 で「今後手を入れない」として
// 全体が deprecated になったが、置き換え先は提示されていない。展示会用の本アプリが端末に残すのは
// Teams のトークンだけで、要件は「平文で置かない」こと。他の選択肢（Keystore で自前に暗号化）は
// 手数が増えるだけなのでこのまま使い、警告はこのファイルに閉じて抑制する。
@file:Suppress("DEPRECATION")

package com.scraft.glassreport.data.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val TAG = "TokenStore"
private const val PREFS_NAME = "teams_tokens"
private const val KEY_ACCESS = "access_token"
private const val KEY_REFRESH = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_AUTH_VERSION = "auth_version"

/** チャネル用の現在の同意セット。古い保存トークンを一度だけ再認証へ送るための版。 */
const val CURRENT_AUTH_VERSION = 5

/**
 * 保存しているトークン一式。
 *
 * [expiresAt] は**絶対期限（epoch ms）**。Graph が返すのは相対秒（`expires_in`）なので、
 * 受け取った時刻を足して保存する（再起動を挟んでも判定できるように）。
 */
data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Long,
    val authVersion: Int = CURRENT_AUTH_VERSION,
)

/**
 * 保存値の正規化。**空文字は「無い」ものとして扱う。**
 *
 * `accessToken` が空文字なら未サインイン（null）、`refreshToken` が空文字なら null。
 * 空の `access_token` は、`token` 応答が壊れていれば実際に保存されうる（`TeamsAuth.store`）。
 *
 * [EncryptedTokenStore] と [InMemoryTokenStore] の**契約を揃えるため両方がこれを通す**。
 * Keystore を開けない端末では後者へ退避するので、振る舞いがズレると事故る。
 */
private fun normalize(tokens: Tokens): Tokens? {
    val access = tokens.accessToken.takeIf { it.isNotBlank() } ?: return null
    return Tokens(
        accessToken = access,
        refreshToken = tokens.refreshToken?.takeIf { it.isNotBlank() },
        expiresAt = tokens.expiresAt,
        authVersion = tokens.authVersion,
    )
}

/**
 * Teams のトークンの保存先。
 *
 * このアプリが端末に残す唯一の永続データ（ローカル DB は持たない）。
 * 実体は [EncryptedSharedPreferences]（[TokenStore.create]）、テストは [InMemoryTokenStore]。
 */
interface TokenStore {
    /** 保存済みトークン。まだサインインしていなければ null。 */
    fun load(): Tokens?

    fun save(tokens: Tokens)

    /** サインアウト。`invalid_grant`（リフレッシュトークン失効）でも呼ぶ。 */
    fun clear()

    companion object {
        /**
         * 端末用の [TokenStore] を作る。
         *
         * 端末の Keystore が壊れている等で暗号化 prefs を開けないことがある
         * （中国国内版 OEM ファームでは前例がある）。その場合に落ちると展示会で詰むので、
         * **メモリ上の入れ物へ退避**する（プロセスを終えるまではサインインが保つ）。
         */
        fun create(
            context: Context,
            name: String = PREFS_NAME,
        ): TokenStore =
            runCatching { EncryptedTokenStore(encryptedPrefs(context, name)) }
                .getOrElse { error ->
                    Log.w(TAG, "暗号化 prefs を開けないためメモリ保存に退避する", error)
                    InMemoryTokenStore()
                }

        private fun encryptedPrefs(
            context: Context,
            name: String,
        ): SharedPreferences {
            val masterKey =
                MasterKey
                    .Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

/** [SharedPreferences]（暗号化済み）に置く実装。鍵の値はログに出さない。 */
class EncryptedTokenStore(
    private val prefs: SharedPreferences,
) : TokenStore {
    /**
     * 保存済みトークンを読む。**壊れているキーは「無い」ものとして扱う**（[readOrNull]）。
     *
     * `accessToken` が読めなければ未サインイン、`refreshToken` が読めなければ「更新できない」、
     * `expiresAt` が読めなければ 0＝期限切れ扱いに倒れる。起動直後に例外で落とさないのが目的。
     */
    override fun load(): Tokens? {
        val access = readOrNull(KEY_ACCESS) { prefs.getString(KEY_ACCESS, null) } ?: return null
        return normalize(
            Tokens(
                accessToken = access,
                refreshToken = readOrNull(KEY_REFRESH) { prefs.getString(KEY_REFRESH, null) },
                expiresAt = readOrNull(KEY_EXPIRES_AT) { prefs.getLong(KEY_EXPIRES_AT, 0L) } ?: 0L,
                // 既存バージョンにはこのキーが無い。0 に倒して追加権限の同意を一度だけ取り直す。
                authVersion = readOrNull(KEY_AUTH_VERSION) { prefs.getInt(KEY_AUTH_VERSION, 0) } ?: 0,
            ),
        )
    }

    /**
     * 1 キーぶんの読み出し。**壊れていれば null**（＝そのキーは無かった）を返す。
     *
     * 保存形式を変えたビルドを上書きインストールした場合などに、キーへ別の型が入っていることがある。
     * 素の [SharedPreferences] は型が違えば [ClassCastException] を投げ、
     * [EncryptedSharedPreferences] は復号や型 ID の解釈に失敗すると [SecurityException] を投げる。
     * どちらも頻度は低いが、起きるとアプリが起動直後に落ちるので、握り潰さず未サインインへ倒す。
     *
     * ログに出すのは**キー名だけ**（トークンの値は絶対に残さない）。
     */
    private fun <T> readOrNull(
        key: String,
        read: () -> T,
    ): T? =
        try {
            read()
        } catch (broken: ClassCastException) {
            Log.w(TAG, "保存済みトークンの $key が壊れているため無視する", broken)
            null
        } catch (broken: SecurityException) {
            Log.w(TAG, "保存済みトークンの $key を読めないため無視する", broken)
            null
        }

    override fun save(tokens: Tokens) {
        prefs.edit {
            putString(KEY_ACCESS, tokens.accessToken)
            putString(KEY_REFRESH, tokens.refreshToken)
            putLong(KEY_EXPIRES_AT, tokens.expiresAt)
            putInt(KEY_AUTH_VERSION, tokens.authVersion)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }
}

/**
 * プロセス内だけに保つ実装。ユニットテストと、暗号化 prefs が使えない端末のフォールバック。
 *
 * フォールバック先なので [EncryptedTokenStore] と同じ契約にする（[normalize] を通す）。
 * 違うのは寿命だけ（プロセスを終えると消える）。
 */
class InMemoryTokenStore(
    tokens: Tokens? = null,
) : TokenStore {
    private var saved: Tokens? = tokens?.let(::normalize)

    override fun load(): Tokens? = saved

    override fun save(tokens: Tokens) {
        saved = normalize(tokens)
    }

    override fun clear() {
        saved = null
    }
}
