import type {
  Entry,
  Health,
  LocalReply,
  SignInProgress,
  SignInStart,
  TeamsMessage,
  TeamsStatus,
} from "shared";

/** 受信サーバとのやりとり。UI から fetch の詳細を隠す。 */

export async function fetchList(): Promise<Entry[]> {
  const r = await fetch("/api/list", { cache: "no-store" });
  if (!r.ok) throw new Error(`list failed: ${r.status}`);
  return r.json();
}

export async function fetchHealth(): Promise<Health> {
  const r = await fetch("/api/health", { cache: "no-store" });
  if (!r.ok) throw new Error(`health failed: ${r.status}`);
  return r.json();
}

/**
 * 削除は**論理削除**。サーバ側で trash へ移るだけで消えない。
 * web から簡単に消せてしまうため、取り返しがつくようにしてある。
 */
export async function removeEntry(id: string): Promise<void> {
  const r = await fetch(`/api/entries/${id}`, { method: "DELETE" });
  if (!r.ok) throw new Error(`delete failed: ${r.status}`);
}

/** 全件をゴミ箱へ。これも論理削除。 */
export async function clearEntries(): Promise<void> {
  const r = await fetch("/api/entries", { method: "DELETE" });
  if (!r.ok) throw new Error(`clear failed: ${r.status}`);
}

export async function fetchTrash(): Promise<Entry[]> {
  const r = await fetch("/api/trash", { cache: "no-store" });
  if (!r.ok) throw new Error(`trash failed: ${r.status}`);
  return r.json();
}

/** ゴミ箱から live へ戻す。誤削除の救済。 */
export async function restoreEntry(id: string): Promise<void> {
  const r = await fetch(`/api/trash/${id}/restore`, { method: "POST" });
  if (!r.ok) throw new Error(`restore failed: ${r.status}`);
}

/** ゴミ箱を空にする。**ここだけが本当に消える操作**。 */
export async function purgeTrash(): Promise<void> {
  const r = await fetch("/api/trash", { method: "DELETE" });
  if (!r.ok) throw new Error(`purge failed: ${r.status}`);
}

/** 開発用操作はPC本体だけ。旧Rokid設定QRの保存値は参照しない。 */
async function localHeaders(json = false): Promise<HeadersInit> {
  const r = await fetch("/api/token", { cache: "no-store" });
  if (!r.ok) {
    if (r.status === 403)
      throw new Error("この操作はPC本体のブラウザから実行してください");
    throw new Error(`token API failed: ${r.status}`);
  }
  const { token } = (await r.json()) as { token?: unknown };
  if (typeof token !== "string" || !token)
    throw new Error("開発用APIのトークンを取得できません");
  return {
    "x-auth-token": token,
    ...(json ? { "content-type": "application/json" } : {}),
  };
}

async function localError(r: Response): Promise<Error> {
  const body = (await r.json().catch(() => ({}))) as { error?: string };
  return new Error(body.error || `local API failed: ${r.status}`);
}

/** Teamsを経由せず、受信元端末のローカル返信キューへ日本語を送る。 */
export async function sendLocalReply(
  entryId: string,
  text: string,
): Promise<LocalReply> {
  const r = await fetch("/api/replies", {
    method: "POST",
    headers: await localHeaders(true),
    body: JSON.stringify({ entryId, text }),
  });
  if (!r.ok) throw await localError(r);
  return r.json();
}

/** PCのマイク音声をAUTO認識し、受信元端末の言語へ翻訳してキューへ送る。 */
export async function sendLocalVoiceReply(
  entryId: string,
  audio: Blob,
): Promise<LocalReply> {
  const body = new FormData();
  body.set("entryId", entryId);
  body.set("audio", audio, "reply.webm");
  const r = await fetch("/api/replies", {
    method: "POST",
    headers: await localHeaders(),
    body,
  });
  if (!r.ok) throw await localError(r);
  return r.json();
}

export type TeamsConnectionStatus = TeamsStatus & { configured: boolean };

function teamsHeaders(json = false): Promise<HeadersInit> {
  return localHeaders(json);
}

async function teamsError(r: Response): Promise<Error> {
  try {
    const body = (await r.json()) as { error?: string };
    return new Error(body.error || `Teams API failed: ${r.status}`);
  } catch {
    return new Error(`Teams API failed: ${r.status}`);
  }
}

export async function fetchTeamsStatus(): Promise<TeamsConnectionStatus> {
  const r = await fetch("/api/teams/status", { cache: "no-store" });
  if (!r.ok) throw await teamsError(r);
  return r.json();
}

export async function fetchTeamsMessages(): Promise<TeamsMessage[]> {
  const r = await fetch("/api/teams/channel/messages", {
    cache: "no-store",
    headers: await teamsHeaders(),
  });
  if (!r.ok) throw await teamsError(r);
  return r.json();
}

export async function sendTeamsMessage(text: string): Promise<TeamsMessage> {
  const r = await fetch("/api/teams/channel/messages", {
    method: "POST",
    headers: await teamsHeaders(true),
    body: JSON.stringify({ text }),
  });
  if (!r.ok) throw await teamsError(r);
  return r.json();
}

export async function startTeamsSignIn(): Promise<SignInStart> {
  const r = await fetch("/api/teams/signin", {
    method: "POST",
    headers: await teamsHeaders(),
  });
  if (!r.ok) throw await teamsError(r);
  return r.json();
}

export async function fetchTeamsSignInProgress(): Promise<SignInProgress> {
  const r = await fetch("/api/teams/signin", { cache: "no-store" });
  if (!r.ok) throw await teamsError(r);
  return r.json();
}
