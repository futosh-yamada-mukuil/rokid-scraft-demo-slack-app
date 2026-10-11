/**
 * In-memory stand-in for the Slack Web API methods the relay uses.
 *
 * Verification must never post to a real workspace, so the end-to-end check runs the real relay
 * against this. It mirrors the parts of Slack's behaviour the relay depends on: a file upload
 * becomes a channel message, replies form a thread, and channel history reports each thread's
 * `reply_count` / `latest_reply` on its parent. `latencyMs` stands in for the network round trip
 * so that the cost of each Slack call shows up in measurements.
 */
import type { SlackMessage } from "../../src/slack.ts";

export const FAKE_SLACK = {
  botToken: "xoxb-e2e-not-a-real-token",
  workspaceId: "T_E2E",
  channelId: "C_E2E",
  botUser: "U_BOT",
  botId: "B_BOT",
};

export class FakeSlack {
  /** Slack method → number of calls, for checking how much the relay asks of Slack. */
  readonly calls: Record<string, number> = {};
  private readonly latencyMs: number;
  private readonly roots: SlackMessage[] = [];
  private readonly threads = new Map<string, SlackMessage[]>();
  private readonly pendingFiles = new Set<string>();
  private readonly fileMessage = new Map<string, string>();
  private sequence = 0;

  constructor(latencyMs = 0) {
    this.latencyMs = latencyMs;
  }

  private ts(): string {
    return `${Math.floor(Date.now() / 1000)}.${String(++this.sequence).padStart(6, "0")}`;
  }

  /** A person answers in the thread of a photo, as they would from the Slack app on a PC. */
  addReply(threadTs: string, text: string, user = "U_HUMAN"): string {
    if (!this.roots.some((m) => m.ts === threadTs))
      throw new Error(`unknown thread ${threadTs}`);
    const ts = this.ts();
    const replies = this.threads.get(threadTs) ?? [];
    replies.push({ ts, text, user, thread_ts: threadTs });
    this.threads.set(threadTs, replies);
    return ts;
  }

  /** Thread parents posted so far, newest first. */
  photoThreads(): string[] {
    return this.roots.filter((m) => m.files?.length).map((m) => m.ts);
  }

  private parent(message: SlackMessage): SlackMessage {
    const replies = this.threads.get(message.ts);
    return replies?.length
      ? {
          ...message,
          thread_ts: message.ts,
          reply_count: replies.length,
          latest_reply: replies.at(-1)?.ts,
        }
      : message;
  }

  readonly fetch: typeof fetch = async (input, init) => {
    const url = String(input);
    if (this.latencyMs)
      await new Promise((done) => setTimeout(done, this.latencyMs));
    if (url.startsWith("https://files.slack.com/")) {
      this.calls["files.upload"] = (this.calls["files.upload"] ?? 0) + 1;
      return new Response("OK");
    }
    const method = url.split("/").at(-1) ?? "";
    this.calls[method] = (this.calls[method] ?? 0) + 1;
    const headers = init?.headers as Record<string, string> | undefined;
    if (headers?.Authorization !== `Bearer ${FAKE_SLACK.botToken}`)
      return Response.json({ ok: false, error: "invalid_auth" });
    const fields = new URLSearchParams(String(init?.body));
    switch (method) {
      case "auth.test":
        return Response.json({
          ok: true,
          team_id: FAKE_SLACK.workspaceId,
          user_id: FAKE_SLACK.botUser,
          bot_id: FAKE_SLACK.botId,
        });
      case "conversations.info":
        return Response.json({
          ok: true,
          channel: {
            id: FAKE_SLACK.channelId,
            name: "e2e",
            is_private: true,
            is_member: true,
            is_archived: false,
          },
        });
      case "files.getUploadURLExternal": {
        const id = `F${String(++this.sequence).padStart(6, "0")}`;
        this.pendingFiles.add(id);
        return Response.json({
          ok: true,
          file_id: id,
          upload_url: `https://files.slack.com/upload/v1/${id}`,
        });
      }
      case "files.completeUploadExternal": {
        const [{ id }] = JSON.parse(fields.get("files") ?? "[]") as {
          id: string;
        }[];
        if (!this.pendingFiles.delete(id))
          return Response.json({ ok: false, error: "file_not_found" });
        const ts = this.ts();
        this.roots.unshift({
          ts,
          text: fields.get("initial_comment") ?? "",
          user: FAKE_SLACK.botUser,
          bot_id: FAKE_SLACK.botId,
          files: [{ id, mimetype: "image/jpeg" }],
        });
        this.fileMessage.set(id, ts);
        return Response.json({ ok: true });
      }
      case "files.info": {
        const ts = this.fileMessage.get(fields.get("file") ?? "");
        return ts
          ? Response.json({
              ok: true,
              file: {
                shares: { private: { [FAKE_SLACK.channelId]: [{ ts }] } },
              },
            })
          : Response.json({ ok: false, error: "file_not_found" });
      }
      case "conversations.history":
        return Response.json({
          ok: true,
          messages: this.roots
            .slice(0, Number(fields.get("limit") ?? 100))
            .map((m) => this.parent(m)),
        });
      case "conversations.replies": {
        const root = this.roots.find((m) => m.ts === fields.get("ts"));
        return root
          ? Response.json({
              ok: true,
              messages: [
                this.parent(root),
                ...(this.threads.get(root.ts) ?? []),
              ],
              has_more: false,
            })
          : Response.json({ ok: false, error: "thread_not_found" });
      }
      case "users.info":
        return Response.json({
          ok: true,
          user: {
            name: "e2e",
            profile: {
              display_name:
                fields.get("user") === FAKE_SLACK.botUser
                  ? "SCRAFT Rokid"
                  : "PC担当",
            },
          },
        });
      default:
        return Response.json({ ok: false, error: `unknown_method_${method}` });
    }
  };
}
