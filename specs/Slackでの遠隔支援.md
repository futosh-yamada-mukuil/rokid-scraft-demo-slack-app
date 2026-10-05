# Slackでの遠隔支援とAVDの起動方法

このリポジトリのAndroidアプリはSlack向けです。写真・コメントの送信先と返信の取得先は、PC中継サーバの設定で固定します。Teamsへの通信はアプリの実行経路から外しました。旧Teamsクライアントとそのテストは互換性のためコードに残しています。

```text
Rokid_Test AVD（480 × 640 / 240dpi）
    ↕ 端末用トークン + adb reverse
PC中継サーバ（127.0.0.1:8787）
    ↕ Slack Botトークン + HTTPS
設定したSlackワークスペース / 対象チャンネル（プライベート）
```

SlackのBotトークンはPCの `.env` にだけ保存します。APKには入れません。グラスには中継サーバへの端末用トークンを暗号化保存します。中継サーバに残す端末情報はトークンのハッシュと送信写真のIDです。接続を解除すれば、その端末からの送受信は拒否されます。

新しい環境では、以下のSlackアプリ作成・インストール・チャンネルへの追加と、AVDの端末登録が必要です。既存アプリを使う場合は [SlackのYour Apps](https://api.slack.com/apps) から設定を開いてください。

## 1. Slackアプリを準備する（初回のみ）

1. [SlackのYour Apps](https://api.slack.com/apps)へ接続したいワークスペースのアカウントでログインします。
2. **Create an App**（作成済みアプリがある場合は **Create New App**）を押し、**From a manifest → Continue** を選びます。
3. JSON入力をすべて選択して [slack-app-manifest.json](slack-app-manifest.json) の内容に置き換え、接続先ワークスペースを指定します。アプリ名は **SCRAFT Rokid** です。
4. **Next** で5つの権限を確認し、**Create and Install** を押します。必要に応じて許可画面で **Allow** を押します。未インストールの場合は **OAuth & Permissions → Install to Workspace** からインストールします。承認制の場合は管理者の承認が必要です。
5. **Bot User OAuth Token**（`xoxb-`から始まる値）をプロジェクト直下の `.env` の `SLACK_BOT_TOKEN` に保存します。チャット、スクリーンショット、Gitには貼り付けません。
6. Slackで対象のプライベートチャンネル（例: **開発用**）を開き、チャンネル名 → **エージェントとアプリ → エージェントまたはアプリを追加する** から **SCRAFT Rokid** を検索して追加します。旧表示では **インテグレーション → アプリを追加する** です。プライベートチャンネルはBotの追加が必須です。

必要なBot権限は `files:write`（写真・コメント送信）、`files:read`（写真のスレッドID取得）、`groups:read`（接続先と参加確認）、`groups:history`（プライベートチャンネルとスレッドの取得）、`users:read`（投稿者名）です。公開チャンネルやDMの履歴権限は要求しません。

`.env.example` を `.env` にコピーし、次の例のトークン・ワークスペースID・チャンネルIDを自分の接続先の値に置き換えます。Slackをブラウザで開いたURLの `/client/<ワークスペースID>/<チャンネルID>` から確認できます。

```dotenv
SLACK_BOT_TOKEN=ここへBotトークンを保存
SLACK_WORKSPACE_ID=T00000000000
SLACK_CHANNEL_ID=C00000000000
SLACK_RELAY_URL=
```

AVDの中継URLはビルド設定で `http://127.0.0.1:8787` に固定されるため、AVDだけなら `SLACK_RELAY_URL` は空で構いません。Slack接続の設定を変更した場合は中継サーバを再起動してください。

## 2. PC中継サーバを起動する

Android Studioで開くフォルダはクローンしたプロジェクトのルート **rokid-scraft-demo-slack-app** です。
ターミナルもそのフォルダで操作します。以下の `/path/to/` は実際の保存先に置き換えてください。

```bash
cd /path/to/rokid-scraft-demo-slack-app
pnpm install --frozen-lockfile
bash scripts/run-slack-relay.sh
```

サーバ用ターミナルは開いたままにします。[PCのSlack接続画面](http://localhost:8787/slack)に設定したチャンネル名と **（プライベート）** が表示されれば準備完了です。未設定・権限不足・Bot未参加の場合は、この画面に理由を表示します。

Node.js 22.13以降が必要です。スクリプトはPC内の127.0.0.1にだけ待ち受けます。8787番ポートをほかのプロセスが使用している場合は、その用途を確認してから止めてください。

## 3. Slackへ接続するAVDを起動する

別のターミナルで実行します。

```bash
cd /path/to/rokid-scraft-demo-slack-app
bash run.sh slack
```

AVD **Rokid_Test** を起動し、実際のSlackに接続する **MUKUIL Slack AVD** をインストールします。PCのカメラとマイクを自動で接続します。起動中のAVDが仮想カメラや音声無効の設定なら、端末データを保持して再起動します。macOSのカメラ・マイクの許可が表示されたら許可してください。画面下の **↑・↓・決定・戻る** でグラスの操作を再現します。Slackの端末登録が必要な場合は、**Slack 接続** 画面を表示します。

1. AVDに表示された8文字の端末コードを確認します。
2. PCブラウザで [端末登録画面](http://localhost:8787/slack/pair) を開き、コードを入力して **この端末を接続する** を押します。
3. 数秒後にAVDが自動で元の画面に戻ります。コードは10分で期限切れになり、登録後は再利用できません。

Android Studioから起動する場合は **Build Variants → slackEmulator**、実行端末 **Rokid_Test** を選びます。PCサーバへの接続に、次のコマンドも必要です。

```bash
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 reverse tcp:8787 tcp:8787
```

シリアルは `adb devices -l` で確認した対象AVDの値を使ってください。`bash run.sh slack` はこの接続を自動で設定します。

### Androidのホーム画面から起動する

インストール後は、Androidのアプリ一覧に会社ロゴの **MUKUIL Slack AVD** が表示されます。
検証用の **Rokid_Test** では、ホーム画面の左上にもアイコンを追加して起動を確認しています。
同じAVDの端末データを消去しない限り、インストール済みアプリとホーム画面の配置を使い続けられます。

新しいAVDでホーム画面にも配置する場合は、アプリ一覧の **MUKUIL Slack AVD** を長押しし、
**Add to home screen（ホーム画面に追加）** を選びます。ほかのランチャーではホーム画面へドラッグして配置します。
このAVDで一覧を開きにくい場合は、次のコマンドで開けます。検索用キーボードが表示されたらAndroidの戻る操作で閉じます。

```bash
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 shell input keyevent KEYCODE_ALL_APPS
```

アイコンからの起動でも、Slackの送受信にはPC中継サーバと `adb reverse` の接続が必要です。
AVDを再起動した後は、この節の接続コマンドを再実行してください。`bash run.sh slack` でも再設定できます。

## 4. 写真を送信してPCから返信する

1. AVDの **操作メニュー → 写真を撮る** を選びます。
2. **縦／横** を選択し、PCのカメラで写真を撮影します。Rokid実機では端末の背面カメラで撮影します。
3. コメントを残す場合は録音して文字起こしします。今回はコメントなしでも写真を送信できます。
4. **Slackに送信しますか？ → はい** を選びます。これはローカルサンプルではなく、実際のチャンネルへの送信です。
5. PCのSlackで設定したチャンネルを開き、**SCRAFT Rokid** が送った写真・コメントを確認します。
6. 写真の **スレッドで返信** を押し、現場への指示を書き込みます。その写真を送った端末で返信が表示されます。

通常のチャンネル投稿も端末のロビーに表示します。スレッド返信は送信元の端末にだけ振り分けます。ほかの端末が送った写真への返信は通知しません。画像の表示は既存グラスUIの `[画像]` 表示を使用します。

中継サーバは30秒間結果をキャッシュし、チャンネルの最新100投稿と、アプリから送った最新20件の写真スレッドを確認します。20件より前の写真に対するスレッド返信は監視範囲外です。指示を継続する場合は新しい写真を送って、そのスレッドへ返信してください。長いスレッドはページをたどり、4000件を超える場合は明示的にエラーを表示します。通知は即時配信ではなく、通常30〜40秒程度の遅延があります。Slackの制限や接続エラー時には遅延が増えます。

## 5. 翻訳・読み上げ・音声コメント

Slackから取得した本文も、既存のGoogle翻訳・TTS・新着通知へ渡します。設定画面の **表示・返信言語** で日本語・英語・中国語（簡体字）・韓国語を選べます。Google機能を使うには `.env` に `GOOGLE_STT_TTS_TRANSLATE_API_KEY` を設定し、Speech-to-Text、Text-to-Speech、Cloud Translation APIと課金を有効にしてAPKを再ビルドしてください。

キーがない場合は原文と翻訳失敗の表示を残し、自動読み上げを行いません。録音コメントは実際の認識処理を使います。以前のUIプレビューの固定音声コメントはSlack接続モードでは使いません。

音声コメントは、Googleキーが未設定かつエンジン未選択なら、同梱の **Offline STT** で認識します。Googleキーは不要です。設定画面で明示的にGoogle STTを選んだ場合は、キーの設定またはOffline STTへの切り替えが必要です。翻訳・Googleの読み上げは引き続きGoogleキーが必要です。

`bash run.sh slack` はPCマイクの入力と音声出力を有効にし、AVDの **Virtual microphone uses host audio input** をオンにします。設定変更が必要なAVDは自動で再起動するため、手動で終了する必要はありません。macOSの **システム設定 → プライバシーとセキュリティ → マイク／カメラ** でも、エミュレーターの実行元への許可を確認してください。

複数カメラがある場合は、カメラ一覧で番号を確認して指定できます。

```bash
cd /path/to/rokid-scraft-demo-slack-app
"$HOME/Library/Android/sdk/emulator/emulator" -webcam-list
ROKID_EMULATOR_CAMERA=webcam1 bash run.sh slack
```

既定は最初に見つかったPCカメラです。カメラが見つからない場合はエラーを表示して停止します。テスト画像でUIだけ確認する場合は `ROKID_EMULATOR_CAMERA=emulated bash run.sh slack`、音声を使わない場合は `ROKID_EMULATOR_AUDIO=0 bash run.sh slack` を指定できます。これらのAVD設定は実機のカメラ・マイクには適用されません。

GoogleキーにAndroidアプリ制限を設定している場合は、AVDのパッケージ `com.scraft.glassreport.slack` と使用するdebug署名も許可してください。実機のパッケージとは異なります。

## 6. 外部送信しないUI確認との使い分け

| 起動コマンド | Android StudioのVariant | 役割 |
|---|---|---|
| `bash run.sh emu` | `emulator` | 認証不要のUIサンプル。外部送信・Google通信なし |
| `bash run.sh slack` | `slackEmulator` | PCサーバ経由で実際のSlackに接続 |
| `bash run.sh glass` | `debug` | 接続されたRokid実機。HTTPSの中継URLが必要 |

実機版は `.env` の `SLACK_RELAY_URL` に実機から到達できるHTTPS URLを設定します。PC管理ページ `/slack`・`/slack/pair`・`/slack/revoke` は外部へ公開せず、同じPCのlocalhostから操作します。中継を外部運用する場合はHTTPSの配置・ネットワーク・運用認証の設計が別途必要です。今回の起動スクリプトはローカルAVD向けです。

端末の接続解除は [PCのSlack接続画面](http://localhost:8787/slack) の **接続を解除** で行います。中継サーバの端末・写真IDは `.local/slack/relay-state.json` に保存し、Gitには追加しません。サーバを終了すると新着確認も止まります。

## 公式仕様

- [Slackファイル送信](https://docs.slack.dev/messaging/working-with-files/): `files.getUploadURLExternal` → 写真バイトの送信 → `files.completeUploadExternal` を使用します。旧 `files.upload` は使いません。
- [スレッド取得と権限・制限](https://docs.slack.dev/reference/methods/conversations.replies/): 社内向けのSlackアプリとしてポーリングします。公開配布向けの低い利用制限で運用する構成ではありません。
- [Slackアプリのインストール](https://docs.slack.dev/authentication/installing-with-oauth/): Slack標準のOAuthはPCでのアプリインストール時に使用します。グラスの8文字コードは、この中継サーバ独自の端末登録です。
- [Android Emulatorのカメラ設定](https://developer.android.com/studio/run/emulator-commandline): `-camera-back webcam0` でPCカメラをAndroidの背面カメラに接続します。
- [Android Emulatorのマイク設定](https://developer.android.com/studio/run/emulator-extended-controls): ホストのマイク入力とOS側の許可が必要です。

## 検証記録（2026-10-04）

- Android単体テスト400件、APIテスト61件、Webテスト44件、AVD作成スクリプトのテスト3件が成功。型チェック、Biome、ktlint、シェル構文チェックも成功しました。
- `debug`・`slackEmulator` のAPKをビルドし、AVDに `slackEmulator` をインストールしました。480×640 / 240dpiをADBで確認しています。
- Slack Web APIをローカルで模擬した検証サーバを使い、PCの端末登録画面 → AVDで撮影 → 写真送信（25,993バイトのJPEG）→ スレッド返信の原文表示まで通して確認しました。この検証から実際のSlackへの通信は行っていません。
- 接続画面から撮影へ戻る際に向きの選択がリセットされる問題を修正し、選択値をNavigationの保存状態に保持するようにしました。
- in-appブラウザーでSCRAFT Rokidを作成し、検証用ワークスペースのプライベートチャンネルへ追加しました。Botトークンは値を出力せず、Git対象外の `.env`（権限0600）へ保存しています。中継サーバを再起動し、対象チャンネルとBotの参加を実際のSlack APIで確認しました。
- AVDをPCの端末登録画面から登録し、仮想カメラで撮影したJPEGを実際のSlackへ1枚送信しました。その写真にPCブラウザーからスレッド返信を書き込み、AVDの返信画面に同じ本文と投稿者名が表示されることを確認しました。投稿リンクと撮影データはこのリポジトリには含めません。
- 検証環境のGoogleキーは未設定です。原文表示と翻訳失敗の表示は確認しましたが、クラウド翻訳・TTSは未検証です。PCカメラ・マイクと同梱オフライン音声認識を使う起動方法に変更しました。Rokid実機での動作は未検証です。
- PCカメラ `webcam0` の実映像をAVDに表示し、960×1280のJPEGを撮影しました。PCマイクで5秒・160,000バイトのPCM録音を確認しました。実際の声の録音 → Offline STT → 写真・コメントのSlack投稿も成功しています。検証したコメントでは「写真」が「書真」と認識されました。入力・送信エラーは解消しましたが、認識結果の誤りはあり得ます。
- 今回の変更で録音・音声認識の単体テスト31件、AVD設定テスト8件、`ktlintCheck`、シェル構文、実機向け `assembleDebug` とAVD向け `assembleSlackEmulator` が成功しました。AVD上の合成音声の認識は日本語・英語・中国語が期待文と一致しました。韓国語の合成音声では語句の欠落があり、4言語の完全一致テストは未達です。
- Androidのアプリ一覧に `MUKUIL Slack AVD` と会社ロゴが表示されることを確認し、ホーム画面の左上にアイコンを追加しました。アイコンからSlackの会話画面を開けること、AVDの再起動とAPKの上書きインストール後もホームアイコンが残ることを確認しました。
