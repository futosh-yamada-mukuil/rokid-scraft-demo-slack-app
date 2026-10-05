# app/api — PC受信・翻訳・返信API

Fastifyで動くローカルAPIです。PCモニター経路では単なる表示サーバではなく、受信、保存、音声認識、翻訳、端末別返信を担当します。Teamsを選択したRokidはGraph APIへ直接投稿するため、このAPIを経由しません。

## 主な役割

- Rokid／Androidから写真、動画、WAV音声を受信
- Google STTで日中英韓の音声をAUTO認識し、Cloud TranslationでPC向け日本語へ翻訳
- Preactモニターへ一覧とSSE更新を提供
- PCの日本語テキストまたはマイク音声を、送信元端末の言語へ翻訳
- 端末ID別のローカル返信キューを提供
- Teamsの認証・チャネル取得・送信を行う任意の復旧APIを提供

## 起動

リポジトリルートで次を実行します。

```bash
pnpm dev
```

API単体は `pnpm --filter api dev`、既定ポートは `8787`です。

## 設定

| 値 | 用途 |
|---|---|
| `GOOGLE_STT_TTS_TRANSLATE_API_KEY` | Google STTとCloud Translationの日中英韓翻訳。RokidのTTSも同じキーを使用 |
| `MS_TENANT_ID` / `MS_CLIENT_ID` | Teams Device Code Flow |
| `MS_TEAM_ID` / `MS_CHANNEL_ID` | Teams送受信先 |
| `PORT` | APIポート。既定 `8787` |
| `UPLOAD_DIR` | 受信データ保存先 |

アップロード用共有トークンは起動時にローカルファイルへ生成され、Webの設定QRから端末へ渡します。Teamsトークンは `app/api/.teams-token.json`に保存され、Git管理外です。

## 主要エンドポイント

| Method | Path | 内容 |
|---|---|---|
| `POST` | `/upload`, `/api/upload` | 写真・動画・音声を受信 |
| `GET` | `/api/entries` | 受信一覧 |
| `GET` | `/api/stream` | SSE更新 |
| `GET/POST` | `/api/replies` | 端末別返信の取得・作成 |
| `GET` | `/api/health` | LAN IPとポート |
| `GET` | `/api/config/token` | 設定QR用共有トークン |
| `GET` | `/api/teams/status` | Teams設定・認証状態 |
| `POST/GET` | `/api/teams/signin` | Device Code Flow開始・進捗 |
| `GET/POST` | `/api/teams/channel/messages` | Teamsチャネルの取得・テキスト送信 |
| `POST` | `/api/teams/post/:id` | 保存済み写真をTeamsへ再送 |

アップロードは `image/jpeg`、`video/mp4`の生データ、または `media`と任意の`audio`を含むmultipartを受けます。`X-Auth-Token`、`X-Device`、`X-Language`を使用します。

Teamsの復旧APIは実装・テスト済みですが、現在のPreact画面には操作導線がありません。動画はTeamsの`hostedContents`で扱わないため再送できません。

## 障害時の扱い

Googleの解析に失敗してもメディア受信は成功させ、元コメントを保存します。PCから端末への返信翻訳に失敗した場合は返信を登録せず、502を返します。

音声返信はPCM16・モノラルWAV、WebM Opus、Ogg Opus、FLACに対応します。PCブラウザは録音を16kHz・モノラルWAVに変換して送ります。動画MP4の音声抽出は未実装です。Rokidから`X-Skip-Analysis: true`を付けた写真は、端末側で文字起こししたコメントをそのまま保存します。読み上げ音声の合成・再生はRokidがGoogle TTSへ直接接続して行います。
