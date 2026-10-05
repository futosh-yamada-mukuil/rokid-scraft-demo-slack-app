package com.scraft.glassreport.feature.auth

/**
 * 認証画面を抜けたあとの行き先。
 *
 * 認証画面には 2 通りの入り方がある。
 * - **起動時**: Teams が設定済みで未サインインなら最初の画面が認証（`MainActivity`）。戻る先が無い。
 * - **画面内から**: 送信が `SignInRequiredException` で弾かれた画面（撮影・記録一覧）が積む。
 *   呼び出し元は撮り直せない下書き（写真＋音声コメント）を抱えたまま待っている。
 *
 * どちらもホームへ送ってしまうと後者の下書きが失われるので、行き先はここで分ける。
 */
enum class AuthExit {
    /**
     * 認証を積んだ画面へ **`popBackStack` で戻す**。
     *
     * `navigate` で積み直すと NavBackStackEntry が別物になり、
     * それにひもづく ViewModel（＝撮影画面が抱えている下書き）ごと作り直されて消える。
     */
    CALLER,

    /** ホームを積む。起動時の認証のように戻る先が無いときだけ。 */
    HOME,
}

/**
 * 呼び出し元（認証を積んだ画面）があるかどうかだけで行き先が決まる。
 * 画面名で分岐しないので、撮影画面でも記録一覧でも同じ形で戻せる。
 */
fun authExitFor(hasCaller: Boolean): AuthExit = if (hasCaller) AuthExit.CALLER else AuthExit.HOME

/**
 * [authExitFor] の判断どおりに認証画面を抜ける。サインイン成功でもスキップでも扱いは同じ。
 *
 * [popBackStack] が false（もう戻れない）を返したら行き先が無くなるので [goHome] に落とす。
 */
fun leaveAuth(
    hasCaller: Boolean,
    popBackStack: () -> Boolean,
    goHome: () -> Unit,
) {
    if (authExitFor(hasCaller) == AuthExit.CALLER && popBackStack()) return
    goHome()
}
