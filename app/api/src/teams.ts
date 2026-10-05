import { readFile, writeFile } from "node:fs/promises";

import type {
  PostedToTeams,
  SignInProgress,
  SignInStart,
  TeamsMessage,
  TeamsStatus,
} from "shared";

/**
 * Microsoft Graph（Teams）への投稿と、その認証。
 *
 * 【位置づけ】本来の経路はグラスが直接 Graph を叩くもので、これは**復旧動線**。
 * グラスからの送信が失敗したときに、受信モニタから人手で送り直せるようにするためのもの。
 *
 * 認証はパブリッククライアントの Device Code Flow。**client secret は存在しない**ので、
 * このサーバが抱える秘密は「取得したトークン」だけ（トークンファイルは gitignore 済み）。
 * グラスにキーボードが無いためこの方式で、人が別端末のブラウザでコードを入力する。
 */

const LOGIN = "https://login.microsoftonline.com";
const GRAPH = "https://graph.microsoft.com/v1.0";
const SCOPES = "offline_access ChannelMessage.Read.All ChannelMessage.Send";

export type TeamsConfig = {
  tenantId: string;
  clientId: string;
  teamId: string;
  channelId: string;
  /** トークンの保存先。リポジトリ外か gitignore 済みの場所を渡すこと。 */
  tokenPath: string;
};

type Token = {
  access_token: string;
  refresh_token?: string;
  /** 保存時に計算した絶対期限（epoch ms）。 */
  expires_at: number;
};

export type Teams = {
  configured: boolean;
  status(): Promise<TeamsStatus>;
  startSignIn(): Promise<SignInStart>;
  /** 進行中のサインインの状態。web はこれをポーリングする。 */
  signInProgress(): SignInProgress;
  /** 写真（任意）とコメントをチャネルへ投稿する。 */
  post(args: {
    comment: string;
    blob?: Buffer;
    mime?: string;
  }): Promise<PostedToTeams>;
  listChannelMessages(): Promise<TeamsMessage[]>;
  sendChannelMessage(text: string): Promise<TeamsMessage>;
};

/** HTML 本文へ差し込む前に必ず通す。`<` や `&` がそのまま入ると本文が壊れる。 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function plainText(html: string): string {
  return html
    .replace(/<img\b[^>]*>/gi, "[画像]")
    .replace(/<br\s*\/?\s*>/gi, "\n")
    .replace(/<[^>]*>/g, "")
    .replace(/&nbsp;/gi, " ")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&amp;/gi, "&")
    .trim();
}

function toMessage(value: Record<string, unknown>): TeamsMessage {
  const from = (value.from ?? {}) as Record<string, unknown>;
  const user = (from.user ?? from.application ?? {}) as Record<string, unknown>;
  const body = (value.body ?? {}) as Record<string, unknown>;
  return {
    id: String(value.id ?? ""),
    author: String(user.displayName ?? "Teams"),
    text: plainText(String(body.content ?? "")),
    createdAt: value.createdDateTime ? String(value.createdDateTime) : null,
  };
}

async function form(
  url: string,
  data: Record<string, string>,
): Promise<Record<string, unknown>> {
  const r = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(data),
  });
  return (await r.json()) as Record<string, unknown>;
}

export function createTeams(cfg: TeamsConfig): Teams {
  const configured = Boolean(
    cfg.tenantId && cfg.clientId && cfg.teamId && cfg.channelId,
  );
  // Teams クライアントでチャネルを直接開く URL。
  const channelUrl =
    cfg.teamId && cfg.channelId
      ? `https://teams.microsoft.com/l/channel/${encodeURIComponent(cfg.channelId)}` +
        `/channel?groupId=${cfg.teamId}&tenantId=${cfg.tenantId}`
      : "";

  let progress: SignInProgress = { state: "idle" };
  let polling: ReturnType<typeof setTimeout> | null = null;

  async function load(): Promise<Token | null> {
    try {
      return JSON.parse(await readFile(cfg.tokenPath, "utf8")) as Token;
    } catch {
      return null;
    }
  }

  async function save(raw: Record<string, unknown>): Promise<Token> {
    const token: Token = {
      access_token: String(raw.access_token),
      refresh_token: raw.refresh_token ? String(raw.refresh_token) : undefined,
      expires_at: Date.now() + Number(raw.expires_in ?? 3600) * 1000,
    };
    await writeFile(cfg.tokenPath, JSON.stringify(token), "utf8");
    return token;
  }

  /** 期限が近ければ refresh_token で更新する。無ければ null（再サインインが必要）。 */
  async function accessToken(): Promise<string | null> {
    const token = await load();
    if (!token) return null;
    // 60秒の余裕を見て更新する。
    if (token.expires_at - 60_000 > Date.now()) return token.access_token;
    if (!token.refresh_token) return null;

    const r = await form(`${LOGIN}/${cfg.tenantId}/oauth2/v2.0/token`, {
      grant_type: "refresh_token",
      client_id: cfg.clientId,
      refresh_token: token.refresh_token,
      scope: SCOPES,
    });
    if (!r.access_token) return null;
    return (await save(r)).access_token;
  }

  return {
    configured,

    async status() {
      // accessToken() は期限が近ければ更新して保存する。**更新後**の値を読むこと。
      // 先に load() した値を返すと、更新したのに古い期限を報告してしまう。
      const fresh = await accessToken();
      const token = await load();
      return {
        signedIn: Boolean(fresh),
        expiresAt: token ? new Date(token.expires_at).toISOString() : null,
        channelUrl,
      } satisfies TeamsStatus;
    },

    async startSignIn() {
      const r = await form(`${LOGIN}/${cfg.tenantId}/oauth2/v2.0/devicecode`, {
        client_id: cfg.clientId,
        scope: SCOPES,
      });
      if (!r.user_code) {
        const msg = String(
          r.error_description ?? r.error ?? "デバイスコードの取得に失敗",
        );
        progress = { state: "error", message: msg };
        throw new Error(msg);
      }

      const userCode = String(r.user_code);
      const verificationUri = String(
        r.verification_uri ?? "https://login.microsoft.com/device",
      );
      const deviceCode = String(r.device_code);
      const interval = Number(r.interval ?? 5) * 1000;
      const deadline = Date.now() + Number(r.expires_in ?? 900) * 1000;

      progress = { state: "pending", userCode, verificationUri };
      if (polling) clearTimeout(polling);

      // ブラウザでの入力を待つ。完了したらトークンを保存して状態を ok にする。
      const tick = async () => {
        if (Date.now() > deadline) {
          progress = {
            state: "error",
            message: "コードの有効期限が切れました",
          };
          return;
        }
        const t = await form(`${LOGIN}/${cfg.tenantId}/oauth2/v2.0/token`, {
          grant_type: "urn:ietf:params:oauth:grant-type:device_code",
          client_id: cfg.clientId,
          device_code: deviceCode,
        });
        if (t.access_token) {
          await save(t);
          progress = { state: "ok" };
          console.log("✅ Teams サインイン完了");
          return;
        }
        const err = String(t.error ?? "");
        if (err === "authorization_pending" || err === "slow_down") {
          polling = setTimeout(
            tick,
            interval + (err === "slow_down" ? 5_000 : 0),
          );
          return;
        }
        progress = {
          state: "error",
          message: String(t.error_description ?? err),
        };
      };
      polling = setTimeout(tick, interval);

      return {
        userCode,
        verificationUri,
        expiresIn: Number(r.expires_in ?? 900),
      };
    },

    signInProgress() {
      return progress;
    },

    async post({ comment, blob, mime }) {
      if (!cfg.teamId || !cfg.channelId) {
        throw new Error("Teams の投稿先チャネルが設定されていません");
      }
      const text = comment.trim();
      const hasImage = Boolean(blob?.length);
      if (!text && !hasImage) {
        throw new Error("送信する本文または画像がありません");
      }
      const token = await accessToken();
      if (!token) throw new Error("Teams にサインインしていません");

      // 画像は hostedContents に base64 で埋め、本文からは相対 URL で参照する。
      // 投稿後は Graph 側が絶対 URL に書き換える（実データで確認済み）。
      // 本文は contentType: html なので、コメントは**必ずエスケープする**。
      // `<` や `&` がそのまま入ると Teams の本文が壊れる（Kotlin 側の TeamsClient も同様に扱う）。
      const body =
        (text ? `<div>${escapeHtml(text)}</div>` : "") +
        (hasImage
          ? `<div><img src="../hostedContents/1/$value" style="max-width:600px" alt="添付画像"></img></div>`
          : "");

      const payload: Record<string, unknown> = {
        body: { contentType: "html", content: body },
      };
      if (hasImage && blob) {
        payload.hostedContents = [
          {
            "@microsoft.graph.temporaryId": "1",
            contentBytes: blob.toString("base64"),
            contentType: mime ?? "image/jpeg",
          },
        ];
      }

      const r = await fetch(
        `${GRAPH}/teams/${encodeURIComponent(cfg.teamId)}/channels/${encodeURIComponent(cfg.channelId)}/messages`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(payload),
        },
      );
      const json = (await r.json()) as Record<string, unknown>;
      if (!r.ok) {
        const e = (json.error ?? {}) as Record<string, unknown>;
        throw new Error(
          `Teams 投稿に失敗 (HTTP ${r.status}): ${String(e.message ?? "")}`,
        );
      }

      return {
        messageId: String(json.id),
        webUrl: String(json.webUrl ?? channelUrl),
        at: new Date().toISOString(),
      } satisfies PostedToTeams;
    },

    async listChannelMessages() {
      if (!cfg.teamId || !cfg.channelId) {
        throw new Error("Teams の投稿先チャネルが設定されていません");
      }
      const token = await accessToken();
      if (!token) throw new Error("Teams にサインインしていません");

      const channelMessagesUrl =
        `${GRAPH}/teams/${encodeURIComponent(cfg.teamId)}` +
        `/channels/${encodeURIComponent(cfg.channelId)}/messages`;
      const getPage = async (url: string): Promise<Record<string, unknown>> => {
        const r = await fetch(url, {
          headers: { Authorization: `Bearer ${token}` },
          signal: AbortSignal.timeout(30_000),
        });
        if (!r.ok)
          throw new Error(
            `Teams チャネルの取得に失敗 (HTTP ${r.status})。必要なら再サインインしてください`,
          );
        return (await r.json()) as Record<string, unknown>;
      };
      const json = await getPage(
        `${channelMessagesUrl}?$top=5&$expand=replies`,
      );
      const values = Array.isArray(json.value)
        ? (json.value as Record<string, unknown>[])
        : [];
      const visible = (value: Record<string, unknown>) =>
        Boolean(value.id) &&
        !value.deletedDateTime &&
        (!value.messageType || value.messageType === "message");
      const roots = values.filter(visible).slice(0, 5).map(toMessage);
      const replies = new Map<string, TeamsMessage>();
      for (const parent of values.slice(0, 5)) {
        const parentId = String(parent.id ?? "");
        if (!parentId) continue;
        const addReplies = (items: unknown) => {
          if (!Array.isArray(items)) return;
          for (const value of items as Record<string, unknown>[]) {
            if (!visible(value)) continue;
            const message = toMessage(value);
            message.id = `reply:${parentId}:${message.id}`;
            replies.set(message.id, message);
          }
        };
        addReplies(parent.replies);
        let next = parent["replies@odata.nextLink"];
        const seenPages = new Set<string>();
        const expected = new URL(
          `${channelMessagesUrl}/${encodeURIComponent(parentId)}/replies`,
        );
        while (typeof next === "string" && next) {
          const url = new URL(next);
          // A bearer token must never follow an arbitrary nextLink, even elsewhere on Graph.
          if (
            url.origin !== expected.origin ||
            url.pathname !== expected.pathname ||
            url.hash ||
            url.username ||
            url.password
          )
            throw new Error("Teams返信ページのURLが不正です");
          if (seenPages.has(url.href) || seenPages.size >= 10)
            throw new Error("Teams返信ページの取得上限を超えました");
          seenPages.add(url.href);
          const page = await getPage(url.href);
          addReplies(page.value);
          next = page["@odata.nextLink"];
        }
      }
      const newestFirst = (a: TeamsMessage, b: TeamsMessage) =>
        (Date.parse(b.createdAt ?? "") || 0) -
        (Date.parse(a.createdAt ?? "") || 0);
      return [
        ...roots,
        ...[...replies.values()].sort(newestFirst).slice(0, 5),
      ].sort(newestFirst);
    },

    async sendChannelMessage(text) {
      if (!cfg.teamId || !cfg.channelId) {
        throw new Error("Teams の投稿先チャネルが設定されていません");
      }
      const token = await accessToken();
      if (!token) throw new Error("Teams にサインインしていません");

      const messagesUrl =
        `${GRAPH}/teams/${encodeURIComponent(cfg.teamId)}` +
        `/channels/${encodeURIComponent(cfg.channelId)}/messages`;
      const r = await fetch(messagesUrl, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          body: {
            contentType: "html",
            content: `<div>${escapeHtml(text)}</div>`,
          },
        }),
      });
      const json = (await r.json()) as Record<string, unknown>;
      if (!r.ok) {
        const e = (json.error ?? {}) as Record<string, unknown>;
        throw new Error(
          `Teams チャネルへの送信に失敗 (HTTP ${r.status}): ${String(e.message ?? "")}。必要なら再サインインしてください`,
        );
      }
      return toMessage(json);
    },
  };
}
