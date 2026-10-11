/** Slack credentials stay in this PC process. Android only receives a relay credential. */
export type SlackConfig = {
  botToken: string;
  workspaceId: string;
  channelId: string;
  statePath: string;
  pairingUrl?: string;
};

export type SlackMessage = {
  ts: string;
  text?: string;
  user?: string;
  bot_id?: string;
  subtype?: string;
  thread_ts?: string;
  /** Present on thread parents. Lets one history call reveal which threads gained replies. */
  reply_count?: number;
  latest_reply?: string;
  files?: { id: string; mimetype?: string }[];
};

type SlackResponse = {
  ok: boolean;
  error?: string;
  team_id?: string;
  user_id?: string;
  bot_id?: string;
  channel?: {
    id: string;
    name: string;
    is_private: boolean;
    is_member: boolean;
    is_archived: boolean;
  };
  user?: {
    name: string;
    real_name?: string;
    profile?: { display_name?: string };
  };
  upload_url?: string;
  file_id?: string;
  messages?: SlackMessage[];
  response_metadata?: { next_cursor?: string };
  has_more?: boolean;
  file?: {
    shares?: {
      private?: Record<string, { ts: string; thread_ts?: string }[]>;
      public?: Record<string, { ts: string; thread_ts?: string }[]>;
    };
  };
};

const reasons: Record<string, string> = {
  invalid_auth: "SlackのBotトークンを確認してください",
  token_revoked: "SlackのBotトークンが失効しています",
  not_authed: "SlackのBotトークンがありません",
  missing_scope:
    "Slackアプリの権限が不足しています。設定手順を確認してください",
  channel_not_found:
    "Slackの接続先が見つかりません。Botを開発用チャンネルへ追加してください",
  not_in_channel: "Slackの開発用チャンネルにBotを追加してください",
  is_archived: "Slackの接続先チャンネルはアーカイブされています",
};

export class SlackError extends Error {
  readonly code: string;
  readonly retryAfter: number;
  constructor(code: string, retryAfter = 0) {
    super(
      reasons[code] ??
        (code === "ratelimited"
          ? "Slackの利用制限です。少し待って再試行してください"
          : `Slack通信エラー (${code})`),
    );
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

export class SlackClient {
  readonly config: SlackConfig;
  private readonly fetcher: typeof fetch;
  private readonly apiBase: string;
  private identity?: { botUser: string; botId: string; channelName: string };
  private identityUntil = 0;
  private readonly names = new Map<string, string>();
  private blockedUntil = 0;

  constructor(
    config: SlackConfig,
    fetcher: typeof fetch = fetch,
    apiBase = "https://slack.com/api",
  ) {
    this.config = config;
    this.fetcher = fetcher;
    this.apiBase = apiBase;
  }

  get configured(): boolean {
    return Boolean(
      this.config.botToken && this.config.workspaceId && this.config.channelId,
    );
  }

  private async call(
    method: string,
    fields: Record<string, string> = {},
  ): Promise<SlackResponse> {
    if (Date.now() < this.blockedUntil)
      throw new SlackError(
        "ratelimited",
        Math.ceil((this.blockedUntil - Date.now()) / 1000),
      );
    const response = await this.fetcher(`${this.apiBase}/${method}`, {
      method: "POST",
      headers: { Authorization: `Bearer ${this.config.botToken}` },
      body: new URLSearchParams(fields),
      signal: AbortSignal.timeout(20_000),
      redirect: "error",
    });
    if (response.status === 429) {
      const seconds = Math.max(
        1,
        Number(response.headers.get("retry-after")) || 60,
      );
      this.blockedUntil = Date.now() + seconds * 1000;
      throw new SlackError("ratelimited", seconds);
    }
    if (!response.ok) throw new SlackError(`http_${response.status}`);
    const result = (await response.json()) as SlackResponse;
    if (!result.ok) throw new SlackError(result.error ?? "invalid_response");
    return result;
  }

  /** Fail closed if a token belongs to another workspace, a public channel, or a non-member. */
  async ready(force = false): Promise<{
    botUser: string;
    botId: string;
    channelName: string;
  }> {
    if (!force && this.identity && this.identityUntil > Date.now())
      return this.identity;
    if (!this.configured)
      throw new Error("Slack未設定：PCの.envを設定してください");
    const identity = await this.call("auth.test");
    if (identity.team_id !== this.config.workspaceId)
      throw new Error("Slackトークンのワークスペースが設定と一致しません");
    if (!identity.bot_id || !identity.user_id)
      throw new Error("SlackにはBotトークンを設定してください");
    const { channel } = await this.call("conversations.info", {
      channel: this.config.channelId,
    });
    if (!channel || channel.id !== this.config.channelId || !channel.is_private)
      throw new Error(
        "Slackの接続先にはプライベートチャンネルを指定してください",
      );
    if (channel.is_archived) throw new SlackError("is_archived");
    if (!channel.is_member) throw new SlackError("not_in_channel");
    this.identity = {
      botUser: identity.user_id,
      botId: identity.bot_id,
      channelName: channel.name,
    };
    this.identityUntil = Date.now() + 60_000;
    return this.identity;
  }

  async upload(
    photo: Buffer,
    caption: string,
  ): Promise<{ fileId: string; threadTs?: string }> {
    // Requesting the upload slot sends no photo data, so it overlaps the destination check.
    // The bytes still leave this PC only after the workspace, privacy and membership are confirmed.
    const [, ticket] = await Promise.all([
      this.ready(true),
      this.call("files.getUploadURLExternal", {
        filename: `rokid-${Date.now()}.jpg`,
        length: String(photo.length),
      }),
    ]);
    if (!ticket.file_id || !ticket.upload_url)
      throw new SlackError("invalid_upload_response");
    const target = new URL(ticket.upload_url);
    if (
      target.protocol !== "https:" ||
      target.hostname !== "files.slack.com" ||
      target.username ||
      target.password ||
      target.port
    )
      throw new SlackError("invalid_upload_url");
    const uploaded = await this.fetcher(ticket.upload_url, {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream" },
      body: new Uint8Array(photo),
      signal: AbortSignal.timeout(90_000),
      redirect: "error",
    });
    if (!uploaded.ok) throw new SlackError(`upload_http_${uploaded.status}`);
    await this.call("files.completeUploadExternal", {
      files: JSON.stringify([{ id: ticket.file_id, title: "Rokid 現場写真" }]),
      channel_id: this.config.channelId,
      initial_comment: escapeSlack(caption),
    });
    // The upload is already committed. A failed shares lookup must not turn it into a failed send.
    const threadTs = await this.fileThread(ticket.file_id).catch(
      () => undefined,
    );
    return { fileId: ticket.file_id, threadTs };
  }

  async fileThread(fileId: string): Promise<string | undefined> {
    const { file } = await this.call("files.info", { file: fileId });
    const shares = file?.shares?.private?.[this.config.channelId];
    return shares?.[0]?.thread_ts ?? shares?.[0]?.ts;
  }

  async history(): Promise<SlackMessage[]> {
    await this.ready();
    return (
      (
        await this.call("conversations.history", {
          channel: this.config.channelId,
          limit: "100",
        })
      ).messages ?? []
    );
  }

  /** Read every page in a watched thread; don't silently drop replies after page one. */
  async replies(ts: string): Promise<SlackMessage[]> {
    const messages: SlackMessage[] = [];
    let cursor = "";
    do {
      const page = await this.call("conversations.replies", {
        channel: this.config.channelId,
        ts,
        limit: "200",
        ...(cursor ? { cursor } : {}),
      });
      messages.push(...(page.messages ?? []));
      cursor = page.response_metadata?.next_cursor ?? "";
      if (messages.length > 4000)
        throw new Error(
          "Slackスレッドが長すぎます。新しい写真の投稿へ返信してください",
        );
      if (page.has_more && !cursor)
        throw new SlackError("missing_reply_cursor");
    } while (cursor);
    return messages;
  }

  async author(message: SlackMessage): Promise<string> {
    if (!message.user) return message.bot_id ? "Slackアプリ" : "Slack";
    const known = this.names.get(message.user);
    if (known) return known;
    const { user } = await this.call("users.info", { user: message.user });
    const name =
      user?.profile?.display_name ||
      user?.real_name ||
      user?.name ||
      "Slackユーザー";
    if (this.names.size >= 200) this.names.clear();
    this.names.set(message.user, name);
    return name;
  }
}

export function escapeSlack(text: string): string {
  return text
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

/** Slack text is plain mrkdwn, not HTML. Keep link labels and suppress markup on the small HUD. */
export function slackText(text: string): string {
  return text
    .replace(/<[^>|]+\|([^>]+)>/g, "$1")
    .replace(/<@([A-Z0-9]+)>/g, "@$1")
    .replace(/<#([A-Z0-9]+)>/g, "#$1")
    .replace(/<(https?:\/\/[^>]+)>/g, "$1")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">")
    .replaceAll("&amp;", "&");
}
