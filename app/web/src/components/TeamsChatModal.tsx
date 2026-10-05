import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import type { TeamsMessage } from "shared";

import {
  fetchTeamsMessages,
  fetchTeamsSignInProgress,
  fetchTeamsStatus,
  sendTeamsMessage,
  startTeamsSignIn,
  type TeamsConnectionStatus,
} from "../api.ts";

type Props = { open: boolean; onClose(): void };

/** 開発用 Teams チャネルを表示し、同じチャネルを監視する Rokid へ通知として届ける。 */
export function TeamsChatModal({ open, onClose }: Props) {
  const [status, setStatus] = useState<TeamsConnectionStatus | null>(null);
  const [messages, setMessages] = useState<TeamsMessage[]>([]);
  const [text, setText] = useState("");
  const [error, setError] = useState("");
  const [sending, setSending] = useState(false);
  const [signingIn, setSigningIn] = useState(false);
  const [deviceCode, setDeviceCode] = useState<{
    code: string;
    uri: string;
  } | null>(null);
  const [toast, setToast] = useState<TeamsMessage | null>(null);
  const seen = useRef(new Set<string>());
  const initialized = useRef(false);

  const refresh = useCallback(async (notify = true) => {
    try {
      const next = await fetchTeamsMessages();
      if (initialized.current && notify) {
        const incoming = next.find((message) => !seen.current.has(message.id));
        if (incoming) {
          setToast(incoming);
          window.setTimeout(() => setToast(null), 8_000);
        }
      }
      next.forEach((message) => {
        seen.current.add(message.id);
      });
      initialized.current = true;
      setMessages(next);
      setError("");
    } catch (e) {
      setError((e as Error).message);
    }
  }, []);

  useEffect(() => {
    let active = true;
    void fetchTeamsStatus()
      .then((value) => {
        if (!active) return;
        setStatus(value);
        if (value.signedIn) void refresh(false);
      })
      .catch((e) => active && setError((e as Error).message));
    const interval = window.setInterval(() => {
      if (active && status?.signedIn) void refresh(true);
    }, 10_000);
    return () => {
      active = false;
      window.clearInterval(interval);
    };
  }, [refresh, status?.signedIn]);

  async function signIn() {
    setSigningIn(true);
    setError("");
    // 既存トークンにチャネル権限が無い場合も、コード表示へ切り替える。
    setStatus((current) =>
      current ? { ...current, signedIn: false } : current,
    );
    try {
      const start = await startTeamsSignIn();
      setDeviceCode({ code: start.userCode, uri: start.verificationUri });
      const timer = window.setInterval(async () => {
        const progress = await fetchTeamsSignInProgress();
        if (progress.state === "ok") {
          window.clearInterval(timer);
          setSigningIn(false);
          setDeviceCode(null);
          setStatus(await fetchTeamsStatus());
          await refresh(false);
        } else if (progress.state === "error") {
          window.clearInterval(timer);
          setSigningIn(false);
          setError(progress.message);
        }
      }, 2_000);
    } catch (e) {
      setSigningIn(false);
      setError((e as Error).message);
    }
  }

  async function send() {
    const value = text.trim();
    if (!value || sending) return;
    setSending(true);
    setError("");
    try {
      const sent = await sendTeamsMessage(value);
      seen.current.add(sent.id);
      setText("");
      await refresh(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      {toast && !open && (
        <button
          type="button"
          className="alert alert-info fixed top-20 right-5 z-30 w-[min(26rem,calc(100vw-2.5rem))] cursor-pointer text-left shadow-xl"
          onClick={() => setToast(null)}
          aria-label="Teamsの新着通知を閉じる"
        >
          <span>
            <strong>{toast.author}</strong>
            <br />
            {toast.text || "（添付メッセージ）"}
          </span>
        </button>
      )}

      {open && (
        <div className="fixed inset-0 z-40 flex items-center justify-center bg-black/70 p-4">
          <section
            className="bg-base-100 border-base-300 flex h-[min(42rem,90vh)] w-[min(42rem,96vw)] flex-col overflow-hidden rounded-2xl border shadow-2xl"
            role="dialog"
            aria-modal="true"
            aria-labelledby="teams-chat-title"
          >
            <header className="border-base-300 flex items-center gap-3 border-b p-4">
              <div>
                <h2 id="teams-chat-title" className="font-bold">
                  Teams 開発用チャネル
                </h2>
                <p className="text-xs opacity-60">
                  このチャネルの新着をRokidへ通知
                </p>
              </div>
              {status?.signedIn && (
                <button
                  type="button"
                  className="btn btn-ghost btn-xs ml-auto"
                  onClick={signIn}
                >
                  Teams再認証
                </button>
              )}
              <button
                type="button"
                className={`btn btn-ghost btn-sm ${status?.signedIn ? "" : "ml-auto"}`}
                onClick={onClose}
              >
                閉じる
              </button>
            </header>

            {!status?.signedIn ? (
              <div className="flex flex-1 flex-col items-center justify-center gap-4 p-6 text-center">
                <p>Teamsへの接続が必要です。</p>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={signingIn || status?.configured === false}
                  onClick={signIn}
                >
                  {signingIn ? "認証待ち…" : "Teamsにサインイン"}
                </button>
                {deviceCode && (
                  <div className="rounded-box bg-base-200 p-4">
                    <a
                      className="link link-primary"
                      href={deviceCode.uri}
                      target="_blank"
                      rel="noreferrer"
                    >
                      認証ページを開く
                    </a>
                    <p className="mt-2">
                      コード:{" "}
                      <strong className="font-mono text-xl">
                        {deviceCode.code}
                      </strong>
                    </p>
                  </div>
                )}
              </div>
            ) : (
              <div className="flex min-h-0 flex-1 flex-col">
                <div
                  className="flex-1 space-y-3 overflow-y-auto p-4"
                  aria-live="polite"
                >
                  {[...messages].reverse().map((message) => (
                    <article
                      key={message.id}
                      className="bg-base-200 rounded-box p-3"
                    >
                      <div className="flex gap-2 text-xs opacity-60">
                        <strong>{message.author}</strong>
                        {message.createdAt && (
                          <time>
                            {new Date(message.createdAt).toLocaleString(
                              "ja-JP",
                            )}
                          </time>
                        )}
                      </div>
                      <p className="mt-1 whitespace-pre-wrap break-words">
                        {message.text || "（添付メッセージ）"}
                      </p>
                    </article>
                  ))}
                  {messages.length === 0 && (
                    <p className="py-16 text-center opacity-50">
                      メッセージはまだありません。
                    </p>
                  )}
                </div>
                <form
                  className="border-base-300 flex gap-2 border-t p-4"
                  onSubmit={(event) => {
                    event.preventDefault();
                    void send();
                  }}
                >
                  <textarea
                    className="textarea textarea-bordered min-h-12 flex-1 resize-none"
                    rows={2}
                    maxLength={4000}
                    value={text}
                    placeholder="Rokidへ送るメッセージ"
                    onInput={(event) => setText(event.currentTarget.value)}
                    onKeyDown={(event) => {
                      if (event.key === "Enter" && !event.shiftKey) {
                        event.preventDefault();
                        void send();
                      }
                    }}
                  />
                  <button
                    type="submit"
                    className="btn btn-primary self-end"
                    disabled={!text.trim() || sending}
                  >
                    {sending ? "送信中" : "送信"}
                  </button>
                </form>
              </div>
            )}
            {error && (
              <div className="alert alert-error m-4 mt-0 text-sm">
                <span>{error}</span>
              </div>
            )}
          </section>
        </div>
      )}
    </>
  );
}
