package com.scraft.glassreport.data.prefs

import android.content.SharedPreferences
import com.scraft.glassreport.net.teams.TeamsAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_700_000_000_000L
private const val ONE_HOUR = 3_600_000L

// prefs のキー名は TokenStore.kt ではファイル内 private なので、ここでは**別に書き下す**。
// 名前を変えると既存端末のサインインが黙って消えるため、テスト側から固定する意味がある。
private const val KEY_ACCESS = "access_token"
private const val KEY_REFRESH = "refresh_token"
private const val KEY_EXPIRES_AT = "expires_at"
private const val KEY_AUTH_VERSION = "auth_version"

/**
 * [TokenStore] の契約。**同じ本体を実装ごとに走らせる**。
 *
 * 端末の Keystore が開けないと [EncryptedTokenStore] から [InMemoryTokenStore] へ退避するので、
 * 振る舞いがズレると「暗号化 prefs が開けた端末でだけ動く」事故になる。
 */
abstract class TokenStoreContract {
    /** 何も保存していない状態の保存先を作る。 */
    protected abstract fun newStore(): TokenStore

    /**
     * 保存先はそのままに**読み手だけ作り直す**（アプリ再起動後の読み直しに相当）。
     * [InMemoryTokenStore] は保存先がインスタンス自身なので同じものを返すしかない。
     */
    protected abstract fun reopen(store: TokenStore): TokenStore

    @Test
    fun load_isNullBeforeAnySave() {
        assertNull(newStore().load())
    }

    /** 絶対期限（epoch ms）で保存し、別インスタンスから読んでも同じ値であること。 */
    @Test
    fun save_thenReopen_keepsAbsoluteExpiry() {
        val store = newStore()
        store.save(Tokens("at-1", "rt-1", expiresAt = NOW + ONE_HOUR))

        val restored = reopen(store).load()

        assertEquals("at-1", restored?.accessToken)
        assertEquals("rt-1", restored?.refreshToken)
        assertEquals(NOW + ONE_HOUR, restored?.expiresAt)
        assertEquals(CURRENT_AUTH_VERSION, restored?.authVersion)
    }

    /** リフレッシュトークンが無いのは**エラーではない**（更新できないだけ）。 */
    @Test
    fun save_withoutRefreshToken_isNormalTokens() {
        val store = newStore()
        store.save(Tokens("at-1", refreshToken = null, expiresAt = NOW + ONE_HOUR))

        val saved = reopen(store).load()

        assertNotNull(saved)
        assertEquals("at-1", saved?.accessToken)
        assertNull(saved?.refreshToken)
        assertEquals(NOW + ONE_HOUR, saved?.expiresAt)
    }

    /** 空文字のリフレッシュトークンも「無い」扱い。 */
    @Test
    fun save_withBlankRefreshToken_readsAsNull() {
        val store = newStore()
        store.save(Tokens("at-1", refreshToken = "  ", expiresAt = NOW + ONE_HOUR))

        val saved = reopen(store).load()

        assertEquals("at-1", saved?.accessToken)
        assertNull(saved?.refreshToken)
    }

    /**
     * 空文字のアクセストークンは未サインイン。
     * `token` 応答が壊れていると `TeamsAuth.store` が実際に空文字を保存しうる。
     */
    @Test
    fun save_withBlankAccessToken_isNotSignedIn() {
        val store = newStore()
        store.save(Tokens("", "rt-1", expiresAt = NOW + ONE_HOUR))

        assertNull(store.load())
        assertNull(reopen(store).load())
    }

    /** 上書き保存で古い値が残らないこと（特にリフレッシュトークンを落としたとき）。 */
    @Test
    fun save_replacesPreviousTokens() {
        val store = newStore()
        store.save(Tokens("at-1", "rt-1", expiresAt = NOW + ONE_HOUR))
        store.save(Tokens("at-2", refreshToken = null, expiresAt = NOW + 2 * ONE_HOUR))

        val saved = reopen(store).load()

        assertEquals("at-2", saved?.accessToken)
        assertNull(saved?.refreshToken)
        assertEquals(NOW + 2 * ONE_HOUR, saved?.expiresAt)
    }

    /** サインアウト。消し残すと「サインイン済みだがトークンが無い」状態になる。 */
    @Test
    fun clear_dropsEverything() {
        val store = newStore()
        store.save(Tokens("at-1", "rt-1", expiresAt = NOW + ONE_HOUR))

        store.clear()

        assertNull(store.load())
        assertNull(reopen(store).load())
    }

    /** サインアウトした後もサインインし直せること。 */
    @Test
    fun clear_thenSave_signsInAgain() {
        val store = newStore()
        store.save(Tokens("at-1", "rt-1", expiresAt = NOW + ONE_HOUR))
        store.clear()

        store.save(Tokens("at-2", "rt-2", expiresAt = NOW + ONE_HOUR))

        val saved = reopen(store).load()
        assertEquals("at-2", saved?.accessToken)
        assertEquals("rt-2", saved?.refreshToken)
    }

    /** 期限が 0（＝欠損）ならアクセストークンは期限切れ。リフレッシュトークンがあれば更新対象。 */
    @Test
    fun zeroExpiry_isExpiredButStillRefreshable() {
        val store = newStore()
        store.save(Tokens("at-1", "rt-1", expiresAt = 0L))

        assertEquals(0L, store.load()?.expiresAt)
        assertTrue(signedIn(store))
    }

    /** 期限が 0 でリフレッシュトークンも無ければ認証不可（トークン自体は残る）。 */
    @Test
    fun zeroExpiry_withoutRefreshToken_cannotAuthenticate() {
        val store = newStore()
        store.save(Tokens("at-1", refreshToken = null, expiresAt = 0L))

        assertNotNull(store.load())
        assertFalse(signedIn(store))
    }

    /** `TeamsAuth.signedIn()` の契約（`refreshToken != null || expiresAt - 余裕 > now`）で確かめる。 */
    private fun signedIn(store: TokenStore) = TeamsAuth(store, tenantId = "t", clientId = "c", now = { NOW }).signedIn()
}

/** プロセス内実装。暗号化 prefs を開けない端末での退避先。 */
class InMemoryTokenStoreTest : TokenStoreContract() {
    override fun newStore(): TokenStore = InMemoryTokenStore()

    // 保存先がインスタンス自身なので、読み手だけを作り直すことはできない。
    override fun reopen(store: TokenStore): TokenStore = store
}

/**
 * prefs 実装。契約に加えて**壊れた保存値**への耐性を見る。
 *
 * 実機の [androidx.security.crypto.EncryptedSharedPreferences] は型が違えば既定値を返すが、
 * 素の `SharedPreferences` は [ClassCastException] を投げる。`EncryptedTokenStore` は
 * `SharedPreferences` を受け取る以上、どちらでも落ちてはいけない。
 */
class EncryptedTokenStoreTest : TokenStoreContract() {
    private val prefs = FakeSharedPreferences()

    override fun newStore(): TokenStore = EncryptedTokenStore(prefs)

    /** 同じ prefs を見る別インスタンス＝アプリ再起動後の読み直し。 */
    override fun reopen(store: TokenStore): TokenStore = EncryptedTokenStore(prefs)

    @Test
    fun load_isNullWhenAccessTokenKeyIsMissing() {
        prefs.putRaw(KEY_REFRESH, "rt-1")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)

        assertNull(EncryptedTokenStore(prefs).load())
    }

    @Test
    fun load_returnsTokensWhenRefreshTokenKeyIsMissing() {
        prefs.putRaw(KEY_ACCESS, "at-1")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)

        val saved = EncryptedTokenStore(prefs).load()

        assertEquals("at-1", saved?.accessToken)
        assertNull(saved?.refreshToken)
        assertEquals(NOW + ONE_HOUR, saved?.expiresAt)
    }

    @Test
    fun load_treatsMissingExpiryAsZero() {
        prefs.putRaw(KEY_ACCESS, "at-1")
        prefs.putRaw(KEY_REFRESH, "rt-1")

        assertEquals(0L, EncryptedTokenStore(prefs).load()?.expiresAt)
    }

    @Test
    fun load_marksLegacyTokensAsNeedingNewConsent() {
        prefs.putRaw(KEY_ACCESS, "at-legacy")
        prefs.putRaw(KEY_REFRESH, "rt-legacy")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)

        val saved = EncryptedTokenStore(prefs).load()

        assertEquals(0, saved?.authVersion)
        assertFalse(TeamsAuth(EncryptedTokenStore(prefs), tenantId = "t", clientId = "c", now = { NOW }).signedIn())
    }

    @Test
    fun load_isNullWhenAccessTokenIsBlank() {
        prefs.putRaw(KEY_ACCESS, "")
        prefs.putRaw(KEY_REFRESH, "rt-1")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)

        assertNull(EncryptedTokenStore(prefs).load())
    }

    /** アクセストークンに別の型が入っていても例外を外へ出さず、未サインインへ倒す。 */
    @Test
    fun load_isNullWhenAccessTokenHasWrongType() {
        prefs.putRaw(KEY_ACCESS, 42L)
        prefs.putRaw(KEY_REFRESH, "rt-1")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)

        assertNull(EncryptedTokenStore(prefs).load())
    }

    /** 期限が String で入っていても落ちず、0（期限切れ）として扱う。 */
    @Test
    fun load_ignoresWrongTypedExpiry() {
        prefs.putRaw(KEY_ACCESS, "at-1")
        prefs.putRaw(KEY_REFRESH, "rt-1")
        prefs.putRaw(KEY_EXPIRES_AT, "1700000000000")

        val saved = EncryptedTokenStore(prefs).load()

        assertEquals("at-1", saved?.accessToken)
        assertEquals("rt-1", saved?.refreshToken)
        assertEquals(0L, saved?.expiresAt)
    }

    /** リフレッシュトークンだけ壊れていても、アクセストークンは使える。 */
    @Test
    fun load_ignoresWrongTypedRefreshToken() {
        prefs.putRaw(KEY_ACCESS, "at-1")
        prefs.putRaw(KEY_REFRESH, 1L)
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)

        val saved = EncryptedTokenStore(prefs).load()

        assertEquals("at-1", saved?.accessToken)
        assertNull(saved?.refreshToken)
        assertEquals(NOW + ONE_HOUR, saved?.expiresAt)
    }

    /** 復号に失敗するキー（EncryptedSharedPreferences は SecurityException を投げる）。 */
    @Test
    fun load_isNullWhenAccessTokenCannotBeDecrypted() {
        prefs.putRaw(KEY_ACCESS, "at-1")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)
        prefs.breakDecryption(KEY_ACCESS)

        assertNull(EncryptedTokenStore(prefs).load())
    }

    @Test
    fun load_ignoresExpiryThatCannotBeDecrypted() {
        prefs.putRaw(KEY_ACCESS, "at-1")
        prefs.putRaw(KEY_REFRESH, "rt-1")
        prefs.putRaw(KEY_EXPIRES_AT, NOW + ONE_HOUR)
        prefs.breakDecryption(KEY_EXPIRES_AT)

        val saved = EncryptedTokenStore(prefs).load()

        assertEquals("at-1", saved?.accessToken)
        assertEquals(0L, saved?.expiresAt)
    }

    /** トークンと認証版の4キーが**すべて**消えること。 */
    @Test
    fun clear_removesAllKeys() {
        val store = EncryptedTokenStore(prefs)
        store.save(Tokens("at-1", "rt-1", expiresAt = NOW + ONE_HOUR))
        assertEquals(setOf(KEY_ACCESS, KEY_REFRESH, KEY_EXPIRES_AT, KEY_AUTH_VERSION), prefs.keys())

        store.clear()

        assertEquals(emptySet<String>(), prefs.keys())
    }

    /** 保存するのは**絶対期限の Long**（相対秒ではない）。キー名も固定する。 */
    @Test
    fun save_writesAbsoluteExpiryUnderStableKeys() {
        EncryptedTokenStore(prefs).save(Tokens("at-1", "rt-1", expiresAt = NOW + ONE_HOUR))

        assertEquals("at-1", prefs.raw(KEY_ACCESS))
        assertEquals("rt-1", prefs.raw(KEY_REFRESH))
        assertEquals(NOW + ONE_HOUR, prefs.raw(KEY_EXPIRES_AT) as Long)
        assertEquals(CURRENT_AUTH_VERSION, prefs.raw(KEY_AUTH_VERSION))
    }
}

/**
 * [SharedPreferences] のフェイク。
 *
 * android.jar のスタブは既定値を返すだけで保存もしないので、契約テストには使えない。
 * 素の `SharedPreferencesImpl` に合わせ、**型が違えば [ClassCastException]**、
 * `putString(key, null)` は削除、`edit()` の変更は `apply`/`commit` で反映する。
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any>()
    private val undecryptable = mutableSetOf<String>()

    /** 壊れた値も含め、prefs へ直に置く（保存形式を変えたビルドの上書きインストール相当）。 */
    fun putRaw(
        key: String,
        value: Any,
    ) {
        values[key] = value
    }

    /** そのキーの読み出しで [SecurityException] を投げるようにする（復号失敗の再現）。 */
    fun breakDecryption(key: String) {
        undecryptable += key
    }

    fun raw(key: String): Any? = values[key]

    fun keys(): Set<String> = values.keys.toSet()

    private fun read(key: String): Any? {
        if (key in undecryptable) throw SecurityException("could not decrypt value")
        return values[key]
    }

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = (read(key) as String?) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = (read(key) as MutableSet<String>?) ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = (read(key) as Int?) ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = (read(key) as Long?) ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = (read(key) as Float?) ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = (read(key) as Boolean?) ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    /** 変更は溜めておき、[apply] / [commit] で初めて反映する（本物と同じ）。 */
    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearAll = false

        override fun putString(
            key: String,
            value: String?,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = stage(key, values)

        override fun putInt(
            key: String,
            value: Int,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ): SharedPreferences.Editor = stage(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ): SharedPreferences.Editor = stage(key, value)

        override fun remove(key: String): SharedPreferences.Editor = stage(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            // null は削除（`putString(key, null)` は remove と同義）。
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            pending.clear()
            clearAll = false
            return true
        }

        override fun apply() {
            commit()
        }

        private fun stage(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            pending[key] = value
            return this
        }
    }
}
