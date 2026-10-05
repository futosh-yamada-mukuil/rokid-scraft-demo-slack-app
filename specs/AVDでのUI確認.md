# Rokid実機なしでUIを確認する

Android StudioのAVD「Rokid_Test」に、既存のRokid向けCompose画面を表示する。
画面は480 × 640px、密度は実機の記録に合わせて240dpi、縦向きとする。
実機の上側約64%へ情報を配置する既存レイアウトを使用し、下側にはPC操作用ボタンを表示する。

## 1. 準備

- Android StudioとAndroid SDK（Android Emulator / Platform-Tools / プロジェクトのcompileSdk）
- PCのCPUに対応するAndroid System Image（Intel/AMDはx86_64、Apple Siliconはarm64-v8a）
- Python 3

Android Studioの「Tools → SDK Manager → SDK Platforms」で「Show Package Details」を有効にし、
Android 12L（API 32）のGoogle APIs System Imageをインストールする。
API 32があれば優先し、なければインストール済みのAPI 28以上の通常Androidイメージを使う。
Wear OS / TVのイメージは使用しない。

`.env`、Teamsアカウント、Google APIキー、Rokidアプリ、Bluetooth接続はUI確認には不要。
初回は既存のビルド依存（オフラインSTTモデル・AAR）を取得するためインターネット接続が必要。
プレビューではその認識エンジンを実行しない。

## 2. 1コマンドで表示

リポジトリ直下で実行する。

```bash
bash run.sh emu
```

次の処理を自動で行う。

1. インストール済みイメージからAVD「Rokid_Test」を作成する。
2. AVDをPC上の独立ウィンドウで起動する。起動済みなら同じAVDを使い、カメラ・音声設定が異なる場合は端末データを保持して再起動する。
3. `emulator`ビルドを作り、仮想端末へインストールする。
4. 「MUKUIL UIプレビュー」のロビー画面を表示する。

既存の別のAVDは変更しない。同名AVDのサイズ・密度が違う場合は上書きせず停止する。
別名で作成する場合は `ROKID_AVD_NAME=Rokid_Test_2 bash run.sh emu` とする。
SDKの場所が標準と異なる場合は `ANDROID_HOME`、AVDの保存先は `ANDROID_AVD_HOME` を指定する。
描画はソフトウェア方式を既定とする。PCのGPUを使う場合は `ROKID_EMULATOR_GPU=host` を指定する。
変更を反映するには起動中のAVDを閉じてから再実行する。
エミュレーターを閉じれば終了できる。次回も同じコマンドで再表示できる。
初回はAndroid自体の初期化に数分かかる場合がある。スクリプトは最大10分待機する。

## 3. PCからの操作

| 操作 | グラスでの意味 |
|---|---|
| 下部の↑ / ↓ボタン | 前後スワイプ（選択・スクロール） |
| 下部の「決定」 | タップ |
| 下部の「戻る」 | BACK（画面に応じて戻る・下書き保持） |
| キーボードの矢印 / Enter | スワイプ / タップ |
| キーボードのEnterを素早く2回 | ダブルタップ |
| 「返信サンプルを受信」 | ローカルの会話に新着返信を追加 |

画面内のボタンもマウスでクリックできる。起動直後の画面で「戻る」を押すとアプリが閉じる。
ロビーの「操作メニュー」から写真撮影、記録一覧、設定へ移動できる。
「写真を撮る」では縦／横を選び、AVDの仮想カメラを撮影する。
コメントを「はい」にすると、5秒の待機後に固定のサンプル文章を表示する。マイクは使わない。
送信確認で「はい」を選ぶとローカルの会話へ写真投稿を追加し、送信完了画面を表示する。

PCカメラで実際に撮影し、PCマイクでコメントを認識してSlackへ送る場合は `bash run.sh slack` を使う。
このモードはPCカメラ・マイクを自動で有効にし、Googleキーがない場合の未選択エンジンにはOffline STTを使う。
初回はmacOSのカメラ・マイク許可が必要。[Slack接続の手順](Slackでの遠隔支援.md)を参照。

## 4. 特定の画面を直接開く

```bash
bash run.sh emu settings       # 設定
bash run.sh emu feed           # 記録一覧・返信
bash run.sh emu capture-photo  # 写真撮影
bash run.sh emu auth           # 認証コードの画面
```

認証画面の `DEMO1234` と `example.invalid` は表示確認用の架空の値。
実際のログインは行わず、待機画面を維持する。「戻る」で終了／元画面へ戻る。

## 5. Android Studioから実行

1. このリポジトリをAndroid Studioで開き、Gradle Syncする。
2. `python3 scripts/create-rokid-avd.py` を実行する。
3. 「Tools → Device Manager」で「Rokid_Test」を選ぶ。必要に応じて一覧を更新する。
4. 「Build → Select Build Variant」で `:rokid` のバリアントを **emulator** にする。
5. Run対象を「Rokid_Test」にして実行する。

コマンドで初回起動するとカメラ等の権限も設定される。Android Studioから初回起動する場合は、
写真画面で表示される権限ダイアログを許可する。

生成APK：`app/rokid/build/outputs/apk/emulator/rokid-emulator.apk`

## 6. 確認できることと実機確認が必要なこと

確認できるのは画面配置、文字のサイズ、選択状態、遷移、写真・コメントの確認、サンプル受信通知。
Teamsの送受信と認証、実音声認識、実翻訳、音声読み上げ、Rokid固有のセンサーやタッチパッド、
装着時の見え方・カメラ画質・消費電力は検証しない。
翻訳操作には固定のUIサンプルを使い、音声読み上げは無効とする。

`emulator` APKは別アプリID `com.scraft.glassreport.emulator` を使い、実機アプリの設定やトークンと分離する。
Teams設定・Google APIキーを空にし、INTERNET権限とTeams常駐サービスも含めない。
「送信しました」はサンプル処理の完了を表す。実際の開発チャンネルへ投稿されることはない。
このブランチの実機向け `debug` / `release` ビルドはSlack中継サーバへ接続する。
実機ではCameraXの背面カメラを使い、AVDのPCカメラ設定は適用しない。

## 7. トラブル時

- イメージがない：SDK ManagerでPCのCPUに対応するSystem Imageを追加する。
- AVDが起動しない：`.local/emulator/emulator.log` を確認する。
- 画面が閉じた：`bash run.sh emu` を再実行する。
- 画面操作が効かない：画面下の↑↓・決定を使う。キーボード入力はエミュレーターにフォーカスを置く。
- ビルドがJDKを見つけられない：Android Studio付属JBRなど対応するJDKを `JAVA_HOME` に指定する。
- `jlink does not exist`：完全なJDKが必要。起動スクリプトはJDK自動選択と既存Daemonの再利用を避けて実行する。
  Android Studioで発生する場合はGradle JDKとDaemon JVM criteriaをAndroid Studio付属JBRへ合わせる。

## 8. 今回の確認（2026年10月3日）

Intel Macの既存Android 36.1 / Google Play / x86_64イメージで確認した。
「Rokid_Test」は480 × 640px / 240dpi。API 32のRokid OSを再現したものではなく、
アプリの画面サイズ・密度・UI操作を確認するためのAVDである。

- `bash run.sh emu` によるAVDの起動、ビルド、インストール、ロビー表示：成功。
- ロビー、操作メニュー、設定、記録一覧、認証の各画面：表示確認済み。
- 下部の↑↓・決定によるメニュー選択と画面遷移、戻る：確認済み。
- 横4:3の仮想カメラ撮影、固定コメントの認識、送信確認、ローカル投稿、新着返信通知：確認済み。
- `assembleEmulator` / `assembleDebug`：成功。
- Android単体テスト394件、AVD作成スクリプトのテスト3件、`ktlintCheck`：成功。
- 実機向けAPKがarm64-v8a、UI専用APKがarm64-v8a / x86_64であることを確認。
- UI専用APKのTeams設定・Googleキーが空、INTERNET権限・Teams監視サービスがないことを確認。

ビルドには既存のCameraX等の非推奨警告がある。実Teams送受信・実認証・音声認識／翻訳／TTS・Rokid実機は未検証。
確認用スクリーンショットはGit対象外の `.local/emulator/` に保存している。

参考：[AVDの作成・管理](https://developer.android.com/studio/run/managing-avds)、
[エミュレーターのコマンド起動](https://developer.android.com/studio/run/emulator-commandline)、
[ビルドバリアント](https://developer.android.com/build/build-variants)。

## Slackの実接続

写真をMUKUiLの開発用プライベートチャンネルへ送る場合は `bash run.sh slack`、Android Studioでは `slackEmulator` を使います。PC中継サーバとSlack Bot設定が必要です。認証不要の `emulator` とは別アプリとしてインストールされ、アプリ名は「MUKUIL Slack AVD」、画面下には「MUKUIL · Slack接続モード」と表示します。撮影プレビューと新しく撮る写真のロゴにも「MUKUIL」と表示します。詳しくは [Slackでの遠隔支援](Slackでの遠隔支援.md) を参照してください。
