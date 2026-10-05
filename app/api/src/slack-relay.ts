import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  writeFileSync,
} from "node:fs";
import { dirname } from "node:path";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import {
  type SlackClient,
  SlackError,
  type SlackMessage,
  slackText,
} from "./slack.ts";

type Device = { id: string; label: string; hash: string; channel: string };
type Upload = {
  device: string;
  requestId: string;
  fileId: string;
  threadTs?: string;
  created: number;
};
type State = { devices: Device[]; uploads: Upload[] };
type Pairing = {
  code: string;
  secret: string;
  label: string;
  expires: number;
  token?: string;
};
export type SlackPost = {
  id: string;
  author: string;
  text: string;
  createdAtMs: number;
  attachmentCount: number;
  imageCount: number;
  isOwn: boolean;
};

const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);
const MAX_PHOTO = 16 * 1024 * 1024;
const hash = (value: string) =>
  createHash("sha256").update(value).digest("hex");
const escapeHtml = (s: string) =>
  s
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");

/** Per-device credentials, upload ownership, and pairing; no Slack token ever appears in responses. */
export class SlackRelay {
  readonly slack: SlackClient;
  private readonly state: State;
  private readonly pairings = new Map<string, Pairing>();
  private readonly requests = new Map<
    string,
    { since: number; count: number }
  >();
  private readonly inFlight = new Map<string, Promise<Upload>>();
  private readonly csrf = randomBytes(24).toString("base64url");
  private cache?: {
    until: number;
    messages: SlackMessage[];
    replies: Map<string, SlackMessage[]>;
  };
  private refreshing?: Promise<NonNullable<SlackRelay["cache"]>>;

  constructor(slack: SlackClient) {
    this.slack = slack;
    const path = slack.config.statePath;
    if (existsSync(path)) {
      const saved = JSON.parse(readFileSync(path, "utf8")) as State;
      if (!Array.isArray(saved.devices) || !Array.isArray(saved.uploads))
        throw new Error("Slack端末設定の形式が不正です");
      this.state = saved;
    } else {
      this.state = { devices: [], uploads: [] };
    }
  }

  private get channelKey(): string {
    return `${this.slack.config.workspaceId}/${this.slack.config.channelId}`;
  }

  private save(): void {
    const path = this.slack.config.statePath;
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    const temp = `${path}.tmp`;
    writeFileSync(temp, JSON.stringify(this.state), { mode: 0o600 });
    renameSync(temp, path);
  }

  private admin(
    req: FastifyRequest,
    reply: FastifyReply,
    mutation = false,
  ): boolean {
    const host = String(req.headers.host ?? "");
    let hostname = "";
    try {
      hostname = new URL(`http://${host}`).hostname;
    } catch {
      /* Reject malformed Host. */
    }
    if (
      !LOOPBACK.has(req.ip) ||
      !["localhost", "127.0.0.1", "[::1]"].includes(hostname)
    ) {
      reply.code(403).send({ error: "PCのlocalhostから開いてください" });
      return false;
    }
    if (mutation) {
      const origin = req.headers.origin;
      if (
        origin &&
        origin !== `http://${host}` &&
        origin !== `https://${host}`
      ) {
        reply.code(403).send({ error: "入力元を確認してください" });
        return false;
      }
      const form = new URLSearchParams(
        typeof req.body === "string" ? req.body : "",
      );
      if (form.get("csrf") !== this.csrf) {
        reply.code(403).send({ error: "ページを開き直してください" });
        return false;
      }
    }
    return true;
  }

  private device(req: FastifyRequest, reply: FastifyReply): Device | undefined {
    const token = String(req.headers.authorization ?? "").replace(
      /^Bearer /,
      "",
    );
    const digest = Buffer.from(hash(token), "hex");
    const found = this.state.devices.find(
      (d) =>
        d.channel === this.channelKey &&
        d.hash.length === 64 &&
        timingSafeEqual(Buffer.from(d.hash, "hex"), digest),
    );
    if (!found)
      reply.code(401).send({ error: "Slack端末をPCで登録してください" });
    return found;
  }

  private async error(reply: FastifyReply, error: unknown): Promise<void> {
    if (error instanceof SlackError && error.retryAfter)
      reply.header("retry-after", error.retryAfter);
    reply.code(503).send({
      error: error instanceof Error ? error.message : "Slackに接続できません",
    });
  }

  private page(body: string): string {
    return `<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>SCRAFT Slack接続</title><style>body{font:16px system-ui;max-width:760px;margin:48px auto;padding:0 24px;color:#18212f;background:#f4f6fa}section{background:white;border-radius:12px;padding:24px;margin:20px 0}input,button{font:inherit;padding:12px;border-radius:6px;border:1px solid #bbb}button{background:#533365;color:white;cursor:pointer}code{overflow-wrap:anywhere}a{color:#533365}</style><h1>SCRAFT Slack接続</h1>${body}</html>`;
  }

  private form(): string {
    return `<section><h2>グラス・AVDの端末登録</h2><p>グラスの「Slack 接続」で表示された8文字のコードを入力します。登録後は写真・コメントの送信と返信の表示ができます。</p><form method="post" action="/slack/pair"><input type="hidden" name="csrf" value="${this.csrf}"><label>端末コード <input name="code" required maxlength="8" pattern="[A-Za-z0-9]{8}" autocomplete="off"></label> <button>この端末を接続する</button></form></section>`;
  }

  async register(app: FastifyInstance): Promise<void> {
    app.addContentTypeParser(
      "application/x-www-form-urlencoded",
      { parseAs: "string" },
      (_req, body, done) => done(null, body),
    );

    app.get("/slack", async (req, reply) => {
      if (!this.admin(req, reply)) return;
      let status: string;
      try {
        const ready = await this.slack.ready();
        status = `接続先: #${escapeHtml(ready.channelName)}（プライベート）<br>ワークスペース: <code>${escapeHtml(this.slack.config.workspaceId)}</code><br><a href="https://app.slack.com/client/${encodeURIComponent(this.slack.config.workspaceId)}/${encodeURIComponent(this.slack.config.channelId)}">Slackで開発用チャンネルを開く</a>`;
      } catch (error) {
        status = escapeHtml(
          error instanceof Error ? error.message : "接続できません",
        );
      }
      const devices = this.state.devices
        .filter((d) => d.channel === this.channelKey)
        .map(
          (d) =>
            `<li>${escapeHtml(d.label)} <form style="display:inline" method="post" action="/slack/revoke"><input type="hidden" name="csrf" value="${this.csrf}"><input type="hidden" name="device" value="${d.id}"><button>接続を解除</button></form></li>`,
        )
        .join("");
      reply
        .type("text/html")
        .send(
          this.page(
            `<section><h2>Slackの接続状態</h2><p>${status}</p><p>未設定の場合は <a href="https://api.slack.com/apps">Slackアプリ設定</a>で <code>specs/slack-app-manifest.json</code> を読み込み、MUKUiLにインストールしてBotをプライベートチャンネルへ追加します。BotトークンをPCの <code>.env</code> に保存し、このサーバを再起動してください。</p></section>${this.form()}<section><h2>接続済み端末</h2><ul>${devices || "<li>まだありません</li>"}</ul></section>`,
          ),
        );
    });

    app.get("/slack/pair", async (req, reply) => {
      if (this.admin(req, reply))
        reply.type("text/html").send(this.page(this.form()));
    });

    app.post("/slack/pair", async (req, reply) => {
      if (!this.admin(req, reply, true)) return;
      const code = new URLSearchParams(req.body as string)
        .get("code")
        ?.trim()
        .toUpperCase();
      const pairing = [...this.pairings.values()].find(
        (p) => p.code === code && p.expires > Date.now() && !p.token,
      );
      if (!pairing)
        return reply
          .code(400)
          .type("text/html")
          .send(
            this.page(
              "<p>コードが見つからないか期限切れです。端末の接続画面を開き直してください。</p>" +
                this.form(),
            ),
          );
      try {
        await this.slack.ready();
        if (this.state.devices.length >= 100)
          throw new Error(
            "端末登録の上限です。不要な端末の接続を解除してください",
          );
        const token = randomBytes(32).toString("base64url");
        this.state.devices.push({
          id: randomBytes(12).toString("hex"),
          label: pairing.label,
          hash: hash(token),
          channel: this.channelKey,
        });
        this.save();
        pairing.token = token;
        reply
          .type("text/html")
          .send(
            this.page(
              `<section><h2>端末を接続しました</h2><p>${escapeHtml(pairing.label)} の画面に戻り、写真を送信してください。</p><a href="/slack">接続状態に戻る</a></section>`,
            ),
          );
      } catch (error) {
        await this.error(reply, error);
      }
    });

    app.post("/slack/revoke", async (req, reply) => {
      if (!this.admin(req, reply, true)) return;
      const id = new URLSearchParams(req.body as string).get("device");
      this.state.devices = this.state.devices.filter((d) => d.id !== id);
      this.save();
      reply.redirect("/slack");
    });

    app.post("/api/slack/device-code", async (req, reply) => {
      const now = Date.now();
      for (const [key, p] of this.pairings)
        if (p.expires <= now) this.pairings.delete(key);
      for (const [key, r] of this.requests)
        if (now - r.since > 60_000) this.requests.delete(key);
      const attempts = this.requests.get(req.ip) ?? { since: now, count: 0 };
      attempts.count++;
      this.requests.set(req.ip, attempts);
      if (
        attempts.count > 5 ||
        this.pairings.size >= 100 ||
        this.requests.size > 1000
      )
        return reply
          .code(429)
          .send({ error: "端末コードの取得を少し待ってください" });
      try {
        await this.slack.ready();
        const body = req.body as { label?: unknown } | undefined;
        const label =
          typeof body?.label === "string"
            ? body.label.trim().slice(0, 40)
            : "Rokid";
        const secret = randomBytes(32).toString("base64url");
        let code: string;
        do {
          code = randomBytes(5).toString("hex").slice(0, 8).toUpperCase();
        } while ([...this.pairings.values()].some((p) => p.code === code));
        this.pairings.set(secret, {
          secret,
          code,
          label: label || "Rokid",
          expires: now + 600_000,
        });
        reply.send({
          user_code: code,
          device_code: secret,
          verification_uri:
            this.slack.config.pairingUrl || "http://localhost:8787/slack/pair",
          interval: 2,
          expires_in: 600,
        });
      } catch (error) {
        await this.error(reply, error);
      }
    });

    app.post("/api/slack/device-token", async (req, reply) => {
      const body = req.body as { device_code?: unknown } | undefined;
      const p = this.pairings.get(
        typeof body?.device_code === "string" ? body.device_code : "",
      );
      if (!p || p.expires <= Date.now())
        return reply.code(400).send({ error: "端末コードが期限切れです" });
      reply.header("cache-control", "no-store");
      if (!p.token) return reply.send({ status: "pending" });
      this.pairings.delete(p.secret);
      return reply.send({ status: "connected", access_token: p.token });
    });

    app.get("/api/slack/session", async (req, reply) => {
      const device = this.device(req, reply);
      if (device)
        reply.send({ connected: true, channel: this.slack.config.channelId });
    });

    app.post(
      "/api/slack/photos",
      { bodyLimit: MAX_PHOTO + 16_384 },
      async (req, reply) => {
        const device = this.device(req, reply);
        if (!device) return;
        if (!req.isMultipart())
          return reply
            .code(415)
            .send({ error: "写真はmultipart形式で送信してください" });
        let photo: Buffer | undefined;
        let comment = "";
        let requestId = "";
        for await (const part of req.parts({
          limits: { fileSize: MAX_PHOTO, files: 1, fields: 2, fieldSize: 4096 },
        })) {
          if (part.type === "file") {
            if (part.fieldname !== "photo" || part.mimetype !== "image/jpeg")
              return reply
                .code(415)
                .send({ error: "JPEG写真だけ送信できます" });
            photo = await part.toBuffer();
          } else if (part.fieldname === "comment") comment = String(part.value);
          else if (part.fieldname === "requestId")
            requestId = String(part.value);
        }
        if (
          !photo ||
          photo.length < 3 ||
          photo[0] !== 0xff ||
          photo[1] !== 0xd8 ||
          photo[2] !== 0xff ||
          comment.length > 1000 ||
          !/^[a-zA-Z0-9-]{8,80}$/.test(requestId)
        )
          return reply
            .code(400)
            .send({ error: "写真またはコメントの形式が不正です" });
        const existing = this.state.uploads.find(
          (u) => u.device === device.id && u.requestId === requestId,
        );
        if (existing)
          return reply.send({
            ok: true,
            fileId: existing.fileId,
            threadTs: existing.threadTs,
          });
        const key = `${device.id}/${requestId}`;
        try {
          let sending = this.inFlight.get(key);
          if (!sending) {
            const bytes = photo;
            sending = (async () => {
              const posted = await this.slack.upload(
                bytes,
                `【${device.label}】\n${comment.trim() || "(コメントなし)"}`,
              );
              const record = {
                device: device.id,
                requestId,
                ...posted,
                created: Date.now(),
              };
              this.state.uploads.push(record);
              this.save();
              this.cache = undefined;
              return record;
            })();
            this.inFlight.set(key, sending);
          }
          const posted = await sending;
          reply.send({
            ok: true,
            fileId: posted.fileId,
            threadTs: posted.threadTs,
          });
        } catch (error) {
          await this.error(reply, error);
        } finally {
          this.inFlight.delete(key);
        }
      },
    );

    app.get("/api/slack/feed", async (req, reply) => {
      const device = this.device(req, reply);
      if (!device) return;
      try {
        reply
          .header("cache-control", "no-store")
          .send({ posts: await this.feed(device) });
      } catch (error) {
        await this.error(reply, error);
      }
    });
  }

  private async refresh(): Promise<NonNullable<SlackRelay["cache"]>> {
    if (this.cache && this.cache.until > Date.now()) return this.cache;
    if (this.refreshing) return this.refreshing;
    this.refreshing = (async () => {
      const messages = await this.slack.history();
      let dirty = false;
      // Resolve delayed file-share metadata from history without treating a successful send as a failure.
      for (const record of this.state.uploads.filter((u) => !u.threadTs)) {
        const parent = messages.find((m) =>
          m.files?.some((f) => f.id === record.fileId),
        );
        record.threadTs = parent?.thread_ts ?? parent?.ts;
        if (record.threadTs) dirty = true;
      }
      if (dirty) this.save();
      const replies = new Map<string, SlackMessage[]>();
      // Bound Slack API usage. Watch the 20 most recent uploaded threads, including old parents outside history.
      const watched = this.state.uploads
        .filter(
          (u) =>
            u.threadTs &&
            this.state.devices.some(
              (d) => d.id === u.device && d.channel === this.channelKey,
            ),
        )
        .slice(-20);
      for (const record of watched) {
        const ts = record.threadTs;
        if (!ts || replies.has(ts)) continue;
        try {
          replies.set(ts, await this.slack.replies(ts));
        } catch (error) {
          if (error instanceof SlackError && error.code === "thread_not_found")
            continue;
          throw error;
        }
      }
      const fresh = { until: Date.now() + 30_000, messages, replies };
      this.cache = fresh;
      return fresh;
    })();
    try {
      return await this.refreshing;
    } finally {
      this.refreshing = undefined;
    }
  }

  private async feed(device: Device): Promise<SlackPost[]> {
    const { messages, replies } = await this.refresh();
    const identity = await this.slack.ready();
    const visible = new Map<string, SlackMessage>();
    for (const message of messages)
      if (!message.thread_ts || message.thread_ts === message.ts)
        visible.set(message.ts, message);
    for (const record of this.state.uploads.filter(
      (u) => u.device === device.id,
    )) {
      for (const message of replies.get(record.threadTs ?? "") ?? [])
        visible.set(message.ts, message);
    }
    const ordered = [...visible.values()]
      .filter(
        (m) =>
          m.ts &&
          (!m.subtype ||
            m.subtype === "file_share" ||
            m.subtype === "bot_message" ||
            m.subtype === "thread_broadcast") &&
          (m.text || m.files?.length),
      )
      .sort((a, b) => Number(b.ts) - Number(a.ts))
      .slice(0, 40);
    return Promise.all(
      ordered.map(async (message) => ({
        id: `${this.slack.config.channelId}/${message.ts}`,
        author: await this.slack.author(message).catch(() => "Slackユーザー"),
        text:
          message.files?.some((f) => f.mimetype?.startsWith("image/")) &&
          (message.user === identity.botUser ||
            message.bot_id === identity.botId) &&
          (message.text ?? "").trim().endsWith("\n(コメントなし)")
            ? "(コメントなし)"
            : slackText(message.text ?? ""),
        createdAtMs: Math.floor(Number(message.ts) * 1000),
        attachmentCount: message.files?.length ?? 0,
        imageCount:
          message.files?.filter((f) => f.mimetype?.startsWith("image/"))
            .length ?? 0,
        isOwn:
          message.user === identity.botUser ||
          message.bot_id === identity.botId,
      })),
    );
  }
}
