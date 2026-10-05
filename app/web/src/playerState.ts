import { formatDuration } from "./formatDuration.ts";

/**
 * 自前の動画コントロールの計算。
 *
 * `<video>` に触れない純粋関数だけを置く（シーク位置・バッファ帯・時刻表示）。
 * コンポーネント側は「座標 → 秒」「秒 → %」をここに任せ、DOM 操作だけを持つ。
 */

/** `video.buffered` が満たす最小限の形。テストから偽物を渡せるようにしてある。 */
export type TimeRangeList = {
  readonly length: number;
  start(index: number): number;
  end(index: number): number;
};

/** シークバーに重ねる帯（% 指定）。 */
export type Bar = { left: number; width: number };

/** ← → の送り戻し幅。 */
export const SKIP_SECONDS = 5;

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, value));
}

/** duration は読み込み前 NaN、ライブ配信では Infinity になり得る。 */
function usableDuration(duration: number): number {
  return Number.isFinite(duration) && duration > 0 ? duration : 0;
}

/**
 * 再生時計の表示。`formatDuration` は四捨五入する（サムネのバッジ用）が、
 * 経過時間は切り捨てないと終了前に全体長と同じ値が出てしまう。
 */
export function formatPlaybackTime(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return formatDuration(0);
  return formatDuration(Math.floor(seconds));
}

/** シークバー上のポインタ座標 → 0..1。 */
export function ratioFromPointer(
  clientX: number,
  bar: { left: number; width: number },
): number {
  if (!(bar.width > 0)) return 0;
  return clamp((clientX - bar.left) / bar.width, 0, 1);
}

/** 0..1 → 秒。 */
export function timeFromRatio(ratio: number, duration: number): number {
  return clamp(ratio, 0, 1) * usableDuration(duration);
}

/** 秒 → 0..100（%）。シークバーの塗り幅とつまみの位置に使う。 */
export function percentFromTime(time: number, duration: number): number {
  const total = usableDuration(duration);
  if (total === 0) return 0;
  return clamp(time / total, 0, 1) * 100;
}

/** ← → の送り戻し。端で止める（巻き戻しすぎ／終端越えを防ぐ）。 */
export function skipTime(
  current: number,
  delta: number,
  duration: number,
): number {
  const total = usableDuration(duration);
  const base = Number.isFinite(current) ? current : 0;
  return clamp(base + delta, 0, total || base);
}

/**
 * `video.buffered` → シークバーに重ねる帯。
 * どこまで読めているかが見えると、シークが空振りしているのか
 * 回線が遅いだけなのかを展示中でも判断できる。
 */
export function bufferedBars(
  ranges: TimeRangeList | null | undefined,
  duration: number,
): Bar[] {
  const total = usableDuration(duration);
  if (!ranges || total === 0) return [];

  const bars: Bar[] = [];
  for (let index = 0; index < ranges.length; index += 1) {
    const left = clamp((ranges.start(index) / total) * 100, 0, 100);
    const right = clamp((ranges.end(index) / total) * 100, 0, 100);
    if (right > left) bars.push({ left, width: right - left });
  }
  return bars;
}

/** 音量スライダの値。NaN は 0 扱い。 */
export function clampVolume(volume: number): number {
  return clamp(volume, 0, 1);
}
