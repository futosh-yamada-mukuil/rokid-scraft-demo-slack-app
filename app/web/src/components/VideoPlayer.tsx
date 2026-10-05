import { useCallback, useEffect, useRef, useState } from "preact/hooks";

import {
  type Bar,
  bufferedBars,
  clampVolume,
  formatPlaybackTime,
  percentFromTime,
  ratioFromPointer,
  SKIP_SECONDS,
  skipTime,
  timeFromRatio,
} from "../playerState.ts";

/**
 * 自前のコントロールを持つ動画プレイヤー。
 *
 * ブラウザ既定の `controls` は OS ごとに見た目が違い、展示会の大画面では
 * アプリから浮く。色は daisyUI のセマンティックトークン（primary / base-*）だけで
 * 塗ってあるので、ヘッダのテーマ切替にそのまま追随する。
 *
 * 位置計算は playerState.ts（純粋関数）に寄せ、ここは `<video>` の
 * 読み書きとイベント購読だけを持つ。
 */
export function VideoPlayer({ src }: { src: string }) {
  const shellRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const barRef = useRef<HTMLDivElement>(null);
  const scrubbing = useRef(false);

  const [playing, setPlaying] = useState(false);
  const [current, setCurrent] = useState(0);
  const [duration, setDuration] = useState(0);
  const [buffered, setBuffered] = useState<Bar[]>([]);
  const [volume, setVolume] = useState(1);
  const [muted, setMuted] = useState(false);
  const [fullscreen, setFullscreen] = useState(false);

  const played = percentFromTime(current, duration);

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) video.play().catch(() => {});
    else video.pause();
  }, []);

  const seekBy = useCallback((delta: number) => {
    const video = videoRef.current;
    if (!video) return;
    const next = skipTime(video.currentTime, delta, video.duration);
    video.currentTime = next;
    setCurrent(next);
  }, []);

  /** シークバー上の座標へ飛ぶ。押した瞬間から掴んだまま動かす（スクラブ）まで共通。 */
  const seekToPointer = (clientX: number) => {
    const bar = barRef.current;
    const video = videoRef.current;
    if (!bar || !video) return;
    const rect = bar.getBoundingClientRect();
    const ratio = ratioFromPointer(clientX, {
      left: rect.left,
      width: rect.width,
    });
    const next = timeFromRatio(ratio, video.duration);
    video.currentTime = next;
    setCurrent(next);
  };

  const syncBuffered = () => {
    const video = videoRef.current;
    if (!video) return;
    setBuffered(bufferedBars(video.buffered, video.duration));
  };

  // 開いた操作自体がユーザー操作なので、たいていは音付きで再生できる。
  // 自動再生が拒否された場合は停止状態のまま（再生ボタンで始められる）。
  useEffect(() => {
    videoRef.current?.play().catch(() => {});
  }, []);

  // Space / ← → 。入力部品にフォーカスがある間は横取りしない。
  // Shift 併用の ← → はビューア側の項目移動に譲る。
  // シークバー自身も同じキーを扱うが、あちらが伝播を止めるのでここには届かない
  // （届くと 1 回の入力で 2 回シークしてしまう）。
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      const tag = target?.tagName ?? "";
      if (tag === "INPUT" || tag === "SELECT" || tag === "TEXTAREA") return;

      if (event.key === " ") {
        if (tag === "BUTTON") return; // ボタン自身の押下を優先
        event.preventDefault();
        togglePlay();
      } else if (event.key === "ArrowLeft" && !event.shiftKey) {
        event.preventDefault();
        seekBy(-SKIP_SECONDS);
      } else if (event.key === "ArrowRight" && !event.shiftKey) {
        event.preventDefault();
        seekBy(SKIP_SECONDS);
      }
    };

    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [togglePlay, seekBy]);

  // 全画面は `<video>` ではなく外枠に掛ける。そうしないと自前のコントロールごと隠れる。
  useEffect(() => {
    const onChange = () =>
      setFullscreen(document.fullscreenElement === shellRef.current);
    document.addEventListener("fullscreenchange", onChange);
    return () => document.removeEventListener("fullscreenchange", onChange);
  }, []);

  const toggleFullscreen = () => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else shellRef.current?.requestFullscreen().catch(() => {});
  };

  const applyVolume = (next: number) => {
    const video = videoRef.current;
    if (!video) return;
    const level = clampVolume(next);
    video.volume = level;
    video.muted = level === 0;
  };

  const toggleMute = () => {
    const video = videoRef.current;
    if (!video) return;
    // 音量 0 のまま消音を解除しても無音なので、その場合は既定音量へ戻す。
    if (video.muted && video.volume === 0) video.volume = 0.5;
    video.muted = !video.muted;
  };

  return (
    <div ref={shellRef} className="bg-base-300 absolute inset-0 flex flex-col">
      <div className="relative min-h-0 flex-1">
        <video
          ref={videoRef}
          className="h-full w-full object-contain"
          src={src}
          playsInline
          preload="metadata"
          onPlay={() => setPlaying(true)}
          onPause={() => setPlaying(false)}
          onEnded={() => setPlaying(false)}
          onLoadedMetadata={(event) => {
            setDuration(event.currentTarget.duration);
            syncBuffered();
          }}
          onDurationChange={(event) =>
            setDuration(event.currentTarget.duration)
          }
          onTimeUpdate={(event) => {
            if (!scrubbing.current) setCurrent(event.currentTarget.currentTime);
          }}
          onProgress={syncBuffered}
          onVolumeChange={(event) => {
            setVolume(event.currentTarget.volume);
            setMuted(event.currentTarget.muted);
          }}
        />

        {/* 映像面ぜんぶが再生/一時停止の当たり判定。停止中は中央に大きな
            再生マークを出し、展示会で「押せる」ことが分かるようにする */}
        <button
          type="button"
          className="absolute inset-0 grid place-items-center"
          onClick={togglePlay}
          aria-label={playing ? "一時停止" : "再生"}
        >
          {!playing && (
            <span className="bg-base-100/80 text-base-content grid size-16 place-items-center rounded-full text-2xl shadow-lg backdrop-blur">
              ▶
            </span>
          )}
        </button>
      </div>

      <div className="bg-base-100/85 border-base-300 border-t px-3 pt-1 pb-2 backdrop-blur">
        <SeekBar
          barRef={barRef}
          played={played}
          buffered={buffered}
          current={current}
          duration={duration}
          onScrubStart={(event) => {
            scrubbing.current = true;
            (event.currentTarget as HTMLElement).setPointerCapture(
              event.pointerId,
            );
            seekToPointer(event.clientX);
          }}
          onScrubMove={(event) => {
            if (scrubbing.current) seekToPointer(event.clientX);
          }}
          onScrubEnd={() => {
            scrubbing.current = false;
          }}
          onSeekBy={seekBy}
        />

        <div className="flex items-center gap-2">
          <button
            type="button"
            className="btn btn-ghost btn-sm btn-circle"
            onClick={togglePlay}
            aria-label={playing ? "一時停止" : "再生"}
            title={playing ? "一時停止（Space）" : "再生（Space）"}
          >
            {playing ? "❚❚" : "▶"}
          </button>

          <span className="font-mono text-xs tabular-nums opacity-70">
            {formatPlaybackTime(current)} / {formatPlaybackTime(duration)}
          </span>

          <div className="ml-auto flex items-center gap-1">
            <button
              type="button"
              className="btn btn-ghost btn-sm btn-circle"
              onClick={toggleMute}
              aria-label={muted ? "消音を解除" : "消音"}
              title={muted ? "消音を解除" : "消音"}
            >
              {muted || volume === 0 ? "🔇" : "🔊"}
            </button>

            <input
              type="range"
              className="range range-xs range-primary w-20"
              min={0}
              max={1}
              step={0.05}
              value={muted ? 0 : volume}
              onInput={(event) =>
                applyVolume(Number(event.currentTarget.value))
              }
              aria-label="音量"
              title="音量"
            />

            <button
              type="button"
              className="btn btn-ghost btn-sm btn-circle"
              onClick={toggleFullscreen}
              aria-label={fullscreen ? "全画面を終了" : "全画面"}
              title={fullscreen ? "全画面を終了" : "全画面"}
            >
              {fullscreen ? "⤡" : "⤢"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

/**
 * シークバー。
 *
 * 3層を重ねるだけの単純な作り: 溝（base-content/20）→ 読み込み済み
 * （base-content/40）→ 再生済み（primary）。`<input type=range>` ではなく
 * div で組んだのは、buffered の帯を溝の中に描き込むため。
 */
function SeekBar({
  barRef,
  played,
  buffered,
  current,
  duration,
  onScrubStart,
  onScrubMove,
  onScrubEnd,
  onSeekBy,
}: {
  barRef: { current: HTMLDivElement | null };
  played: number;
  buffered: Bar[];
  current: number;
  duration: number;
  onScrubStart: (event: PointerEvent) => void;
  onScrubMove: (event: PointerEvent) => void;
  onScrubEnd: (event: PointerEvent) => void;
  onSeekBy: (delta: number) => void;
}) {
  const seconds = (value: number) =>
    Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;

  return (
    <div
      ref={barRef}
      className="group relative h-5 cursor-pointer touch-none py-2"
      role="slider"
      tabIndex={0}
      aria-label="再生位置"
      aria-valuemin={0}
      aria-valuemax={seconds(duration)}
      aria-valuenow={seconds(current)}
      aria-valuetext={`${formatPlaybackTime(current)} / ${formatPlaybackTime(duration)}`}
      onPointerDown={onScrubStart}
      onPointerMove={onScrubMove}
      onPointerUp={onScrubEnd}
      onPointerCancel={onScrubEnd}
      onKeyDown={(event) => {
        // Shift 併用はビューア側の項目移動なので、掴まずに上へ流す。
        if (event.shiftKey) return;
        if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;

        // ここで止めないと document の listener にも同じキーが届き、
        // 1 回の入力で 5 秒送りが 2 回（＝10 秒）走ってしまう。
        event.preventDefault();
        event.stopPropagation();
        onSeekBy(event.key === "ArrowLeft" ? -SKIP_SECONDS : SKIP_SECONDS);
      }}
    >
      <div className="bg-base-content/20 relative h-1 overflow-hidden rounded-full">
        {buffered.map((bar) => (
          <div
            key={`${bar.left}-${bar.width}`}
            className="bg-base-content/40 absolute inset-y-0"
            style={{ left: `${bar.left}%`, width: `${bar.width}%` }}
          />
        ))}
        <div
          className="bg-primary absolute inset-y-0 left-0"
          style={{ width: `${played}%` }}
        />
      </div>

      <span
        className="bg-primary absolute top-1/2 size-3 -translate-x-1/2 -translate-y-1/2 rounded-full shadow transition-transform group-hover:scale-125"
        style={{ left: `${played}%` }}
      />
    </div>
  );
}
