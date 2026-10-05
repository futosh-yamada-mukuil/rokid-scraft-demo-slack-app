/**
 * api と web が共有するワイヤ型。
 *
 * 【重要】このパッケージには **型だけ** を置く。実行時の値（定数・関数）を書かないこと。
 * api は `node --experimental-strip-types` で TS を直接実行するが、Node の型ストリッピングは
 * node_modules 配下の TS を処理しない。pnpm はワークスペース参照を node_modules へ
 * シンボリックリンクするため、ここに実行時の値を置くと api が動かなくなる。
 * `import type { ... } from "shared"` は型ストリッピングで丸ごと消えるので成立している。
 *
 * 実行時に必要な定数は api 側（config.ts）に置く。
 */

/** 受信した1件の種別。動画は受信モニタ専用（Teams には送らない）。 */
export type EntryKind = "photo" | "video";

/** 展示デモで扱う利用者言語。PC 操作者の入力言語は日本語に固定する。 */
export type DemoLanguage = "ja" | "zh-CN" | "en" | "ko";

/** 受信した1件。api が保存し、web が表示する単位。 */
export type Entry = {
  /** `20260814-161512-853d` 形式。ファイル名の基底にもなる */
  id: string;
  /** 表示用の `MM/DD HH:MM:SS` */
  time: string;
  kind: EntryKind;
  /** グラスから送られたコメント（文字起こし結果）。無ければ空文字 */
  comment: string;
  /** コメントの言語。返信をこの言語へ戻すために使う。 */
  language?: DemoLanguage;
  /** PC 操作者向けの日本語訳。原文が日本語なら comment と同じ。 */
  translatedComment?: string;
  /** 別送された音声コメント WAV。`/uploads/<audioFile>` で確認できる。 */
  audioFile?: string | null;
  /** 送信元の識別名（例 `rokid-glasses`）。無ければ空文字 */
  device: string;
  /** `uploads/` 配下のファイル名。`/uploads/<file>` で取得できる */
  file: string | null;
  sizeKb: number;
  /** Teams へ投稿済みなら、その記録。未投稿なら undefined。 */
  teams?: PostedToTeams;
};

/** Teams への投稿結果。復旧動線から送ったときに Entry へ書き戻す。 */
export type PostedToTeams = {
  messageId: string;
  webUrl: string;
  /** ISO 8601 */
  at: string;
};

/** Teams 認証の状態。web のヘッダに出す。 */
export type TeamsStatus = {
  signedIn: boolean;
  /** アクセストークンの有効期限（ISO 8601）。未サインインなら null */
  expiresAt: string | null;
  /** チャネルを Teams クライアントで開く URL */
  channelUrl: string;
};

/** Teams チャネルの1メッセージ。Graph の HTML は API 側でプレーンテキスト化する。 */
export type TeamsMessage = {
  id: string;
  author: string;
  text: string;
  /** ISO 8601。Graph が返さなかった場合は null */
  createdAt: string | null;
};

/** PC から Rokid へ返すローカル返信。Teams を経由しない。 */
export type LocalReply = {
  id: string;
  /** 返信対象の受信データ。 */
  entryId: string;
  /** 宛先端末。Rokid は自分宛だけを取得する。 */
  device: string;
  /** PC で入力した日本語。 */
  originalText: string;
  /** 利用者の言語へ変換した読み上げ本文。 */
  translatedText: string;
  language: DemoLanguage;
  /** ISO 8601 */
  createdAt: string;
};

/** Device Code Flow の開始結果。グラスにも同じものを表示する。 */
export type SignInStart = {
  userCode: string;
  verificationUri: string;
  expiresIn: number;
};

/** サインインの進行状況。web はこれをポーリングする。 */
export type SignInProgress =
  | { state: "idle" }
  | { state: "pending"; userCode: string; verificationUri: string }
  | { state: "ok" }
  | { state: "error"; message: string };

/** SSE でモニタへ押し出す変化。削除も配るので、複数画面が同期する。 */
export type Change =
  | { type: "added"; entry: Entry }
  | { type: "reply-added"; reply: LocalReply }
  | { type: "deleted"; id: string }
  | { type: "cleared" };

/** 受信サーバの状態。モニタが宛先 URL を組むのに使う。 */
export type Health = { ok: boolean; ip: string; port: number };
