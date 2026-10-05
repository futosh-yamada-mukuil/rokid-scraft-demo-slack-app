import { useEffect, useRef, useState } from "preact/hooks";
import type { Entry } from "shared";

import { sendLocalReply, sendLocalVoiceReply } from "../api.ts";
import { recordingToWav } from "../audioWav.ts";
import { VideoPlayer } from "./VideoPlayer.tsx";
import { ZoomableImage } from "./ZoomableImage.tsx";

/**
 * 一覧から開く全画面プレビュー（ライトボックス）。
 *
 * daisyUI の `modal` を `<dialog>` + `showModal()` で使う。ESC で閉じる・
 * 背面を操作させない・トップレイヤーに出るといった面倒はブラウザ側が持つので、
 * ここは「開く／閉じる」と「前後移動」だけを持てばよくなる。
 * 背景（modal-backdrop）のクリックでも閉じる。
 *
 * 写真は ZoomableImage、動画は VideoPlayer に委ね、外枠と説明だけを共通化する。
 */
export function MediaViewer({
  entry,
  onClose,
  onPrev,
  onNext,
}: {
  entry: Entry;
  onClose: () => void;
  /** 端では null。ボタンを消す（＝端であることを見せる）ために null で渡す。 */
  onPrev: (() => void) | null;
  onNext: (() => void) | null;
}) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  const isVideo = entry.kind === "video";
  const src = entry.file ? `/uploads/${entry.file}` : null;
  const [replyText, setReplyText] = useState("");
  const [status, setStatus] = useState("");
  const [recording, setRecording] = useState(false);
  const recorderRef = useRef<MediaRecorder | null>(null);

  useEffect(() => {
    dialogRef.current?.showModal();
  }, []);

  // 項目の前後移動。動画のときは ← → を 5 秒送り戻しに譲るので、
  // Shift 併用（または画面端のボタン）で移動する。
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
      if (isVideo && !event.shiftKey) return;

      const tag = (event.target as HTMLElement | null)?.tagName ?? "";
      if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA") return;

      event.preventDefault();
      if (event.key === "ArrowLeft") onPrev?.();
      else onNext?.();
    };

    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [isVideo, onPrev, onNext]);

  async function submitText(event: Event) {
    event.preventDefault();
    if (!replyText.trim()) return;
    setStatus("翻訳・送信中…");
    try {
      const sent = await sendLocalReply(entry.id, replyText);
      setReplyText("");
      setStatus(`端末へ送信: ${sent.translatedText}`);
    } catch (error) {
      setStatus(`送信失敗: ${(error as Error).message}`);
    }
  }

  async function toggleRecording() {
    if (recorderRef.current?.state === "recording") {
      recorderRef.current.stop();
      return;
    }
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
      const chunks: Blob[] = [];
      const recorder = new MediaRecorder(stream);
      recorderRef.current = recorder;
      recorder.ondataavailable = (event) => {
        if (event.data.size) chunks.push(event.data);
      };
      recorder.onstop = async () => {
        setRecording(false);
        stream.getTracks().forEach((track) => {
          track.stop();
        });
        setStatus("AUTO認識・翻訳中…");
        try {
          const recorded = new Blob(chunks, {
            type: recorder.mimeType || "audio/webm",
          });
          const audio = await recordingToWav(recorded);
          const sent = await sendLocalVoiceReply(entry.id, audio);
          setStatus(
            `認識: ${sent.originalText} / 端末へ: ${sent.translatedText}`,
          );
        } catch (error) {
          setStatus(`音声送信失敗: ${(error as Error).message}`);
        }
      };
      recorder.start();
      setRecording(true);
      setStatus("録音中…もう一度押すと送信");
    } catch (error) {
      setStatus(`マイクを使えません: ${(error as Error).message}`);
    }
  }

  return (
    <dialog ref={dialogRef} className="modal" onClose={onClose}>
      {/* modal-box は既定で 32rem 幅・角丸・内側余白。大画面いっぱいに使うので上書きする。
          左右上下に少し隙間を残すのは、その外側（modal-backdrop）を押して閉じられるようにするため。 */}
      <div className="modal-box flex h-[94vh] w-[97vw] max-w-none flex-col overflow-hidden p-0">
        <header className="border-base-300 flex shrink-0 items-center gap-3 border-b px-4 py-2">
          <span className="badge badge-sm badge-ghost shrink-0">
            {isVideo ? "動画" : "写真"}
          </span>

          <div className="min-w-0 flex-1">
            <p className="truncate text-sm leading-tight font-medium">
              {entry.translatedComment || entry.comment || (
                <span className="opacity-50">（コメントなし）</span>
              )}
            </p>
            <p className="truncate font-mono text-xs opacity-60">
              {entry.device || "unknown"} ·{" "}
              {entry.language?.toUpperCase() || "AUTO"} · {entry.time} ·{" "}
              {entry.sizeKb} KB
            </p>
          </div>

          <span className="hidden shrink-0 text-xs opacity-40 lg:inline">
            {isVideo
              ? "Space 再生 / ← → 5秒 / Shift+← → 前後の項目 / Esc 閉じる"
              : "ホイール 拡大 / ドラッグ 移動 / ← → 前後の項目 / Esc 閉じる"}
          </span>

          <button
            type="button"
            className="btn btn-ghost btn-sm btn-circle shrink-0"
            onClick={() => dialogRef.current?.close()}
            aria-label="閉じる"
            title="閉じる（Esc）"
          >
            ✕
          </button>
        </header>

        <div className="bg-base-300 relative min-h-0 flex-1">
          {src === null ? (
            <p className="grid h-full place-items-center text-sm opacity-50">
              ファイルがありません
            </p>
          ) : isVideo ? (
            // key を付けて id が変わったら作り直す（再生位置と音量を持ち越さない）。
            <VideoPlayer key={entry.id} src={src} />
          ) : (
            <ZoomableImage
              key={entry.id}
              src={src}
              alt={entry.comment || "受信した写真"}
            />
          )}

          {onPrev && (
            <NavButton side="left" label="前の項目" onClick={onPrev} />
          )}
          {onNext && (
            <NavButton side="right" label="次の項目" onClick={onNext} />
          )}
        </div>
        <form
          className="border-base-300 flex shrink-0 flex-wrap items-center gap-2 border-t p-3"
          onSubmit={submitText}
        >
          <span className="text-xs font-bold">PC → 端末（Teams不使用）</span>
          <input
            className="input input-bordered input-sm min-w-52 flex-1"
            value={replyText}
            onInput={(event) => setReplyText(event.currentTarget.value)}
            placeholder="日本語で返信"
          />
          <button
            className="btn btn-primary btn-sm"
            type="submit"
            disabled={!replyText.trim()}
          >
            翻訳して送信
          </button>
          <button
            className={`btn btn-sm ${recording ? "btn-error" : "btn-outline"}`}
            type="button"
            onClick={toggleRecording}
          >
            {recording ? "録音停止・送信" : "🎙 AUTO音声返信"}
          </button>
          {status && <p className="basis-full text-xs opacity-75">{status}</p>}
        </form>
      </div>

      {/* daisyUI の作法。背景を押すと dialog が閉じ、onClose が呼ばれる */}
      <form method="dialog" className="modal-backdrop">
        <button type="submit">閉じる</button>
      </form>
    </dialog>
  );
}

/** 画面端の前後ボタン。映像に重なるので base-100 の半透明で浮かせる。 */
function NavButton({
  side,
  label,
  onClick,
}: {
  side: "left" | "right";
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`btn btn-circle btn-ghost bg-base-100/70 hover:bg-base-100 absolute top-1/2 -translate-y-1/2 text-xl backdrop-blur ${
        side === "left" ? "left-3" : "right-3"
      }`}
      onClick={onClick}
      aria-label={label}
      title={label}
    >
      {side === "left" ? "‹" : "›"}
    </button>
  );
}
