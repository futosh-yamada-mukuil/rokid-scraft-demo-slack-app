# CLAUDE.md

このリポジトリで作業する際の指針。**起動・ビルド・テストの詳細コマンドはこのファイルを正とする。**

## プロジェクト概要

Rokid Glasses（`RG-glasses` / OEM `RV101`、YodaOS-Sprite = Android 12 / API 32）上で動く
Android アプリとPC中継サーバで構成する「MUKUIL 現場レポート」。

基本運用では、現場作業者が写真と任意の5秒音声コメントをPC中継サーバ経由でSlackのプライベートチャンネルへ投稿し、
遠隔からの返信をロビーの吹き出しとHUD・TTSで受け取る。現在のAndroidの送受信経路はSlack専用。
SlackのBotトークンと接続先はPCの `.env` で管理し、端末は8文字のコードで登録する。
端末には中継サーバ用の接続情報だけを暗号化保存する。Slack BotトークンをAPKへ組み込まない。
旧Teamsクライアント・テストとPC API/Webの独立した開発用ツールは残している。
写真撮影時は縦／横を選び、設定済み宛先を画面右上へ表示する。撮影コメントは文字起こしだけ行い、翻訳しない。
「動画を撮る」は準備中の無効項目である。通常のチャンネル投稿と、その端末が送った写真へのスレッド返信を表示する。

**設計の正本は [`specs/`](specs/)**。まず [`specs/Slackでの遠隔支援.md`](specs/Slackでの遠隔支援.md)（Slack版の構成・初期設定・検証結果）を読むこと。
開発手順は [`specs/開発ガイド.md`](specs/開発ガイド.md)。`実装計画.md`・`内部設計書.md` のTeams版は過去の設計記録。

> 前身は「介護・医療現場のバイタル記録デモ」。2026-08 に本仕様へ大改造した。
> 旧機能（OCR / Room / 骨格追跡）は削除済み。

## 構成（モノレポ）

```
app/rokid/    実機とAVDで動く Android アプリ。Gradle プロジェクトは :rokid
app/api/      Slack中継・端末登録・返信振り分け。従来PC受信・返信APIも保持
app/web/      開発用PCモニタ／返信（Vite + Preact）、設定QRは撤去
app/shared/   API と Web が共有する TypeScript の型・イベント契約
specs/        要求・設計・開発手順の正本
```

顧客支給資料、資格情報を含み得る検証スクリプト、レビュー控えは `.local/` に置く。
`.local/`、`.codex/`、`.claude/` と各種ビルド生成物は製品ソースではなく Git 管理外。

## 技術スタック

- Kotlin / ネイティブ Android（minSdk 28 / **targetSdk 32 は端末に合わせた固定値。上げない** / compileSdk 37）
- Gradle 9.7（wrapper）/ AGP 9.3.1 / JDK 17 ターゲット
- **Kotlin は AGP 同梱の built-in（2.2.10）**。`org.jetbrains.kotlin.android` は AGP 9 で適用不可なので入れない。
  Compose コンパイラは `org.jetbrains.kotlin.plugin.compose`（built-in Kotlin と同版）で供給する
- **UI: Jetpack Compose**（Material3, Navigation-Compose）
- CameraX 1.6.1（静止画・録画）、AudioRecord（16kHz/mono/PCM16 録音）
- OkHttp（端末からFastifyのSlack中継APIへ送る）
- 端末の中継トークンは暗号化して保存する。PCの端末・写真IDは `.local/slack/relay-state.json` に保存
- アーキ: **package-by-feature × クリーン3層 × MVVM**、手動DI。package `com.scraft.glassreport`

### 音声認識と外部サービス

| 用途 | サービス | 要点 |
|---|---|---|
| 投稿・受信 | Slack Web API | PCがBotトークンを管理。写真のスレッド返信を送信元端末へ配信 |
| 音声認識 | SenseVoice Offline STT / Google Cloud STT | 任意の5秒コメントを文字起こし。Googleキー未設定・未選択ならOffline STT |
| 翻訳 | Google Cloud Translation Basic v2 | 受信本文をja/en/zh-CN/koへ変換。撮影コメントは翻訳しない |
| 読み上げ | Google Cloud TTS | 翻訳された本文を選択言語で読み上げ。キー未設定時は自動読み上げしない |

Google共通キーは `.env`（gitignore 済み）からAndroidの`BuildConfig`へ、Slack BotトークンはPC APIにだけ渡す。`.env.example` を参照。
GoogleキーはAPKから抽出可能なため、配布時にAPI制限・利用上限が必要。Offline STTはネット不要。

## 主要ファイル（package-by-feature。詳細は `specs/内部設計書.md`）

| パス | 役割 |
|---|---|
| `MainActivity.kt` / `SCRAFTApp.kt` | 単一Activity＋NavHost / Application＋DI生成 |
| `feature/{auth,feed,menu,capture,comment}/` | 各画面（Screen＝Compose、ViewModel＝状態/ロジック） |
| `domain/model/`・`domain/text/` | ドメインモデルと純粋ロジック（HTML剥がし・相対時刻。**ユニットテスト対象**） |
| `net/slack/` | 中継サーバへの端末登録・写真送信・メッセージ取得 |
| `data/prefs/`・`data/repo/` | `TokenStore` と Repository（差し替え可能） |
| `capture/{camera,asr,tts}/` | 撮影・録音・読み上げを外部実装から隔離 |
| `core/ui/`・`core/input/`・`di/` | グラス用テーマ/部品・DPADデバウンス入力・DIコンテナ |

**`core/input/GlassControls.kt` と CameraX の配線は前身から引き継いだ「正」**。作り直さない。

## ビルドと実行

JDK は Android Studio 同梱の jbr を使う（システムに単体 JDK は無い）。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"   # local.properties が無い場合に必要
./gradlew :rokid:assembleDebug            # APK: app/rokid/build/outputs/apk/debug/
./gradlew :rokid:testDebugUnitTest        # ユニットテスト
```

**ワンコマンド**: `bash run.sh glass`（実機）/ `bash run.sh slack`（Slack接続AVD）/ `bash run.sh emu`（外部送信なしのUIプレビュー）でビルド→インストール→起動。
adb は `~/Library/Android/sdk/platform-tools/adb`。

### 実機（Rokid Glasses）へ
```bash
adb install -r app/rokid/build/outputs/apk/debug/*-debug.apk
adb shell am start -n com.scraft.glassreport/.MainActivity
```
⚠️ **`adb install` は事前に「Hi Rokid」アプリで ADB デバッグを ON にしないと
`not allow package install!` で拒否される。** 詳細 → [`specs/デバイスメモ.md`](specs/デバイスメモ.md)。

### エミュレータ（UI を素早く確認）
グラス相当の AVD `Rokid_Test`（480×640 / 240dpi / portrait）を使う。
近接センサーで画面が消える実機と違い、UI 確認に向く。
```bash
bash scripts/run-slack-relay.sh  # 別ターミナルで起動したままにする
bash run.sh slack               # AVD作成・PCカメラ/マイク・adb reverseも設定
adb -s emulator-5554 exec-out screencap -p > /tmp/shot.png
```

## デバイス固有の落とし穴（必読）

- **インストールロック**: `adb install` は Rokid 独自フックで拒否。Hi Rokid アプリで ADB デバッグ ON が必要。
- **画面の向き**: 正位置は **portrait（ROTATION_0）**。`landscape` を強制すると 90°傾く。全 Activity で `android:screenOrientation="portrait"`。
- **近接センサー**: 未装着時は画面 OFF（`screencap` が真っ黒）。実機目視は装着して行うか、エミュを使う。
- **標準の音声 API が使えない**: GMS 非搭載で `SpeechRecognizer` も `TextToSpeech` も
  サービスが1つも登録されていない。**エミュでは動くが実機では無言で空振りする。**
  STTは同梱SenseVoiceまたはGoogle Cloud STT、TTSはGoogle Cloud TTSを使う。
- **ボイスコマンドは実装不可**: ウェイクワード検出は NXP コプロのファームで非開放。操作はタップ駆動。
- **LED は制御不可**: `/sys/class/leds/*` は `0640 system:system`、binder の `lights_ctrl` も
  SELinux で遮断。ただしカメラ動作中は白 LED が OS 側で自動点灯する。
- 詳細は [`specs/デバイスメモ.md`](specs/デバイスメモ.md)。

---

# Git 運用ルール

（Web プロジェクト由来のルールを本 Android プロジェクト向けに翻案したもの）

## ブランチ運用
- **main への直接 push は禁止**。必ずブランチを切る。1 機能・1 修正ごとに ブランチ → PR → マージ。
- ブランチ名は種別プレフィックス：
  - `feat/...` 新機能（例 `feat/teams-post`）
  - `fix/...` 修正（例 `fix/token-refresh`）
  - `refactor/...` 構造変更
  - `docs/...` ドキュメント
  - `chore/...` 雑務・設定

## コミット
- メッセージは**日本語**、Conventional Commits 風 prefix：
  `feat(teams): ...` / `fix(asr): ...` / `refactor(feed): ...` / `docs: ...`
- 1 行目に要約、本文に「何を・なぜ」。
- 共著者行を付ける場合は、実際に使用した支援ツールを記載する。使用していないツール名は記載しない。
- **コミット前に必ず緑にする**：
  ```bash
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
  export ANDROID_HOME="$HOME/Library/Android/sdk"
  ./gradlew :rokid:testDebugUnitTest  # ユニットテスト（ロジック変更時は必須）
  ./gradlew :rokid:assembleDebug      # ビルドが通ること
  ./gradlew :rokid:lintDebug          # Android Lint（任意だが推奨）
  ```
  見た目変更はエミュ `Rokid_Test` に対象VariantのAPKをインストールして目視確認。

## PR
- `gh pr create --base main --head <branch>` → 確認後 `gh pr merge <branch> --merge --delete-branch`。
- **PR 作成（pr create）が失敗したら、そこで止める**。後続の merge / switch を続けて走らせない。
- PRに支援ツールの表記を付ける場合は、実際に使用したツール名を記載する。
- マージ後は `git switch main && git pull` でローカル main を更新してから次の作業へ。

## やってはいけないこと
- **add→commit→push→pr create→merge→switch→pull を 1 つの bash ブロックに連結しない**。
  途中失敗を見落として main に直接コミットする事故を防ぐ。段階ごとに成否を確認する。
- **`git reset --hard` は使わない**。やり直しは新ブランチか `git reflog` の hash から
  `git switch -c <branch> <sha>` で復旧。
- **秘密をコミットしない**（`.env`、`local.properties`、keystore、`*.jks`、
  サービスアカウント JSON、`token_cache.bin`）。いずれも gitignore 済み。
  **キーの値をログ・コミットメッセージ・PR 本文に書かない。**
  著作物の参考スクショもコミットしない。
- 対話フラグ（`git rebase -i` / `git add -i`）はこの環境で非対応。

## pre-commit フック
- `.githooks/pre-commit` が、**ステージ済みの Kotlin** を ktlint で整形→再ステージし、
  `ktlintCheck`（lint）と `testDebugUnitTest`（テスト）を通す。違反/失敗があればコミットを中止する。
- クローン後に一度だけ有効化：`git config core.hooksPath .githooks`
- ktlint 設定は `.editorconfig`（max_line_length=140、Compose の `@Composable` 関数は命名対象外）。
- 整形/チェック手動実行：`./gradlew :rokid:ktlintFormat` / `./gradlew :rokid:ktlintCheck`。

## コミット粒度
- **細かくコミットする**。1コミット=1つの論理変更（設定・整形・機能・修正を混ぜない）。

## 補足
- `gh` CLI を使う（PR・issue・API）。
