import { useState } from "preact/hooks";
import type { Entry } from "shared";

import { formatDuration } from "../formatDuration.ts";

/**
 * 一覧の1枚。YouTube のサムネイル形式に寄せてある。
 *
 * サムネは 16:9 の枠に object-contain で収める。グラスの出力は縦長（960x1280 /
 * 720x1280）なので、object-cover にすると中央の帯しか見えなくなるため。
 * YouTube も縦動画は同じように letterbox する。
 *
 * 押すと全画面のビューア（MediaViewer）が開く。ここで動画を再生したり
 * 写真を拡大したりはしない（一覧はあくまで一覧）。
 */
export function Thumb({
  entry,
  onOpen,
  onRemove,
}: {
  entry: Entry;
  onOpen: (id: string) => void;
  onRemove: (id: string) => void;
}) {
  const [duration, setDuration] = useState<number | null>(null);

  const src = entry.file ? `/uploads/${entry.file}` : null;
  const isVideo = entry.kind === "video";
  if (!src) return null;

  return (
    <article className="group flex flex-col gap-2">
      <div className="bg-base-300 relative aspect-video overflow-hidden rounded-xl">
        {isVideo ? (
          // サムネ表示。controls は付けない（controls={false} だと環境によって
          // ネイティブUIが出てしまうので、属性ごと書かない）。
          // #t=0.1 は先頭フレームを描かせるための指定で、poster 画像を作らずに済む。
          <video
            className="pointer-events-none h-full w-full object-contain"
            src={`${src}#t=0.1`}
            preload="metadata"
            muted
            playsInline
            onLoadedMetadata={(e) =>
              setDuration((e.target as HTMLVideoElement).duration)
            }
          />
        ) : (
          <img
            className="h-full w-full object-contain"
            src={src}
            alt=""
            loading="lazy"
          />
        )}

        {/* 枠いっぱいの当たり判定。写真も動画も、押せばビューアが開く */}
        <button
          type="button"
          className="hover:bg-base-content/10 absolute inset-0 grid place-items-center transition"
          onClick={() => onOpen(entry.id)}
          aria-label={isVideo ? "再生" : "拡大"}
          title={isVideo ? "再生" : "拡大"}
        >
          {isVideo && (
            <span className="bg-neutral/80 text-neutral-content grid size-12 place-items-center rounded-full text-xl">
              ▶
            </span>
          )}
        </button>

        {/* 右下のバッジ。動画は長さ、写真は種別を出す（YouTube の再生時間の位置） */}
        <span className="bg-neutral/80 text-neutral-content pointer-events-none absolute right-1.5 bottom-1.5 rounded px-1.5 py-0.5 font-mono text-xs">
          {isVideo
            ? duration !== null
              ? formatDuration(duration)
              : "動画"
            : "写真"}
        </span>
      </div>

      <div className="flex items-start gap-2">
        <div className="min-w-0 flex-1">
          <p className="line-clamp-2 text-sm leading-snug font-medium">
            {entry.translatedComment || entry.comment || (
              <span className="opacity-50">（コメントなし）</span>
            )}
          </p>
          {entry.translatedComment &&
            entry.translatedComment !== entry.comment && (
              <p className="line-clamp-1 text-xs opacity-55">
                原文: {entry.comment}
              </p>
            )}
          <p className="mt-0.5 text-xs opacity-60">
            {entry.device || "unknown"} ·{" "}
            {entry.language?.toUpperCase() || "AUTO"} · {entry.time} ·{" "}
            {entry.sizeKb} KB
          </p>
        </div>

        {/* ホバーで出る操作。YouTube の ⋮ の位置 */}
        <button
          type="button"
          className="btn btn-ghost btn-xs opacity-0 transition group-hover:opacity-100"
          onClick={() => onRemove(entry.id)}
          title="削除"
          aria-label="削除"
        >
          ✕
        </button>
      </div>
    </article>
  );
}
