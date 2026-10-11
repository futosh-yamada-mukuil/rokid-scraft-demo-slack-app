# rokid-scraft-demo-slack-app

Rokid Glasses／Androidで写真を撮り、PC中継サーバ経由でSlackのプライベートチャンネルへ共有する展示会向けデモです。

このリポジトリの送受信はSlack向けです。BotトークンはPCで管理し、グラスは8文字のコードで端末を登録します。送信先はPCの設定で指定したプライベートチャンネルに固定します。写真へのスレッド返信は送信元の端末に振り分けます。既存の撮影・翻訳・読み上げ・通知UIを使用します。

まず [Slackでの遠隔支援とAVDの起動方法](specs/Slackでの遠隔支援.md) に従ってSlackアプリをインストールし、PCの `.env` に `SLACK_BOT_TOKEN`・`SLACK_WORKSPACE_ID`・`SLACK_CHANNEL_ID` を設定してください。

```bash
# ターミナル1（起動したままにする）
bash scripts/run-slack-relay.sh
# ターミナル2
bash run.sh slack
```

PCの http://localhost:8787/slack/pair でAVDに表示されたコードを登録すると、写真送信と返信の表示を開始できます。`bash run.sh emu` は、認証不要・外部送信なしのUI確認用です。

「写真を撮る」と「動画を撮る」は別項目です。現在「動画を撮る」は準備中で選択できません。写真では縦／横を選んで撮影し、コメントを残す場合だけ5秒録音して、設定で選択したGoogle STTまたはオフラインSTTで文字起こしします。撮影コメントは翻訳せず、そのまま確認画面へ進みます。

撮影プレビューと撮影後の写真の左下には、[株式会社ムクイル公式サイト](https://www.mukuil.com/)の[公式シンボル](https://www.mukuil.com/wp-content/themes/mukuil/assets/images/common/logo_sp.svg)と、ご指定の英字表記 **MUKUIL** を組み合わせて表示します。[元シンボル](specs/assets/mukuil/logo-symbol.svg)のパスを維持し、英字をベクター化した[ロゴデータ](specs/assets/mukuil/mukuil-wordmark.svg)をAndroid用ベクターへ変換しています。グラスに合わせて緑色で表示し、新しく撮影するJPEGにも同じロゴを入れます。
アプリアイコンにも同じ公式シンボルを使用します。

## 現在の機能

| 機能 | Slack向けRokidアプリ |
|---|---|
| 写真＋コメントの送信 | 対応 |
| 動画の撮影・送信 | 準備中 |
| メッセージ取得 | Slack Web APIをPCで使用。チャンネル投稿・送信元端末のスレッド返信に対応 |
| 撮影コメントの翻訳 | しない |
| 読み上げ | Google Cloud TTS |

SlackアプリはPCでワークスペースにインストールします。端末には中継サーバ用の接続情報だけを暗号化保存します。SlackのBotトークンはAPKに含めません。中継サーバは最新100投稿と送信写真の最新20スレッドを確認します。新しい返信はチャンネル履歴の3秒ごとの確認で検知して端末へ知らせ、返信の編集などは30秒ごとの読み直しで反映します（詳細は `specs/Slackでの遠隔支援.md`）。

Slack端末を登録した後は、Androidのforeground serviceがアプリ画面を閉じてもチャネルを確認します。中継サーバから変化の合図が届けばすぐに、届かない間は約30秒間隔で確認します。監視中はOSの常駐通知が1件表示され、新着にはBlow通知音とTTSで知らせます。アプリ画面の表示中は従来の画面上通知が担当し、既読IDを共有して二重通知を防ぎます。接続エラー時は確認間隔を約2分に延ばします。初回起動はアプリを開いてサインインする必要があります。強制停止、端末の電源OFF、ネットワーク切断中は受信できません。スリープ中はAndroidのDozeでネットワーク通信が遅延・停止し、通知も遅れる場合があります。

## 構成

| パス | 役割 |
|---|---|
| `app/rokid/` | Rokid／Androidアプリ。撮影、Slack中継サーバへの端末登録・送受信、通知、TTS |
| `app/api/` | Slack中継・端末登録・返信振り分け、従来PC受信・返信API |
| `app/web/` | 開発用Preact製PCモニター・返信。Rokidへの接続設定QRは提供しない |
| `app/shared/` | APIとWebの共有型 |
| `specs/` | 要求・設計・デモ・開発手順 |

受信データは `app/api/uploads/`、削除済みデータは `app/api/trash/`、ローカル返信は `app/api/replies/` に保存され、Gitには追加されません。顧客支給資料や資格情報は `.local/` と `.env` で管理してください。

## セットアップ

Node.js 22.13以降、pnpm、Android Studio同梱JDK 21を使用します。

```bash
git clone https://github.com/futosh-yamada-mukuil/rokid-scraft-demo-slack-app.git
cd rokid-scraft-demo-slack-app
pnpm install --frozen-lockfile
cp .env.example .env
git config core.hooksPath .githooks
```

Slackの初期設定は [Slack手順](specs/Slackでの遠隔支援.md) を参照してください。PCと端末のGoogle音声認識・翻訳・読み上げは共通の `GOOGLE_STT_TTS_TRANSLATE_API_KEY` を使います。秘密値、トークン、keystoreはコミットしないでください。

## PCモニター（開発用のみ）

PC API／Webは既存データや独立した開発検証のために残しています。Slack向けRokidアプリはこのサーバの `/api/slack/` へ接続します。従来のHTTPモニターAPIは使用しません。

```bash
pnpm dev
```

- Web: `http://localhost:5514`
- API: `http://localhost:8787`

## Android

実機なしで画面を確認する場合は `bash run.sh emu` を実行します。
480 × 640 / 240dpiのAVD「Rokid_Test」を作成・起動し、認証情報不要のUI専用APKを表示します。
会話・コメント・送受信はローカルのサンプルです。詳しくは[AVDでのUI確認](specs/AVDでのUI確認.md)を参照してください。

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
bash scripts/fetch-offline-stt.sh
./gradlew :rokid:testDebugUnitTest
./gradlew :rokid:assembleDebug
```

APKは `app/rokid/build/outputs/apk/debug/` に生成されます。詳しくは[開発ガイド](specs/開発ガイド.md)、Slack版の現状と残課題は[Slack手順の検証記録](specs/Slackでの遠隔支援.md#検証記録2026-10-04)を参照してください。

## 検証

### グラスの受信言語

設定「表示・返信言語」では日本語・中国語（簡体字）・英語・韓国語の4言語を選択し、スワイプで候補を移動、タップで確定します。
以前保存した繁体字中国語・その他の言語や未対応コードは、設定読み込み時に日本語へ正規化し、次回保存でも日本語を記録します。
言語と音声認識エンジンは端末の設定画面で変更します。設定QRの読み取りは提供しません。
Slack本文は元言語を自動判定して選択言語へ翻訳し、ロビー・返信閲覧・通知・読み上げで共有します。
画面全体のUI文言は日本語のままです。

翻訳中は原文と状態を表示し、完了時に差し替えます。画像のみの投稿は翻訳・自動読み上げをしません。
本文が1000文字を超える場合や翻訳失敗時は、欠落を避けるため原文と失敗表示を残し、推測言語で読み上げません。
投稿ID・チャネル・本文・対象言語ごとに最大200件をメモリ内キャッシュし、履歴の毎回翻訳を防ぎます。
同時翻訳は1件、待ち行列込み25秒で打ち切り、失敗は60秒後に再試行可能です。
言語変更で進行中の旧言語翻訳を取消し、過去投稿の通知は繰り返しません。
本文は端末からCloud Translation Basic（v2）へ直接送られます。原文言語は自動検出し、`format=text`で翻訳します。
GeminiはPC・Androidの認識・翻訳には使用せず、端末の翻訳キャッシュを端末ストレージへ永続保存しません。

### 音声コメントの認識

設定「音声認識」で Google STT / Offline STT を切り替えます。未選択の場合、GoogleキーがあればGoogle STT、キーがなければ同梱のOffline STTを使います。明示的な選択は保持します。5秒録音、単タップで早期終了、
ダブルタップで一時停止・取り直し・再開する操作は共通です。無音は認識サービスへ渡しません。
Google STTは端末から `speech.googleapis.com/v1/speech:recognize` へ直接16kHz/mono/PCM16 WAVを送ります。
主言語日本語＋英語・中国語（簡体字）・韓国語を候補に自動判定します。中継サーバは使いません。
Google STT/TTS/Cloud Translation共通の `GOOGLE_STT_TTS_TRANSLATE_API_KEY` をビルド時にAPKへ組み込みます。
旧 `GOOGLE_STT_TTS_API_KEY` / `GOOGLE_TTS_API_KEY` はビルド時の互換fallbackのみで、旧AmiVoice/GeminiキーはAPKへ組み込みません。
APIキーはAPKから抽出可能なため、本番配布時はAPI制限・利用上限を設定してください。
Google Cloud側でSpeech-to-Text/Text-to-Speech/Cloud Translation APIと課金を有効にする必要があります。
Cloud TranslationはAPIキー対応のBasic v2を使用します（Advanced v3はAPIキー非対応）。
認証には`x-goog-api-key`ヘッダを使い、キーをURLやログへ含めません。
キー未設定・権限/課金未設定時は理由を表示し、自動で別エンジンへ送信しません。
撮影後のコメント画面ではカメラのバッファを解放し、認識中のメモリ圧迫を抑えます。
オフライン認識は日中英韓をAUTO認識するSenseVoice int8をAPKに同梱します。空きメモリ不足時は理由を表示し、Googleへ勝手に切り替えません。モデルの固定値・ライセンス・実測は[音声認識の設計](specs/オフライン音声認識.md)、機能別の検証結果と実機未確認項目は[検証記録](specs/検証記録-20260930.md)を参照してください。

`bash run.sh slack` はPCのカメラとマイクをAVDへ接続します。初回のmacOSのカメラ・マイク許可は許可してください。起動済みAVDの設定が異なる場合は、端末登録やアプリ設定を保持して自動で再起動します。カメラを指定する場合は `ROKID_EMULATOR_CAMERA=webcam1 bash run.sh slack`、機器を使わないUI確認は `bash run.sh emu` を使います。Rokid実機では従来のCameraX経路で端末の背面カメラとマイクを使います。

### チェックコマンド

```bash
pnpm test:node
pnpm check
pnpm build
./gradlew :rokid:testDebugUnitTest
```
