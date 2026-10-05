/**
 * ライトボックスの拡大縮小・移動（パン）の計算。
 *
 * DOM に触れない純粋関数だけを置く。表示は
 * 「中央に object-contain で置いた画像を `translate(x,y) scale(s)` する」という
 * 1つのモデルに固定してあり、ここはその座標計算だけを持つ。
 */

export type Size = { width: number; height: number };

/** 中央を原点とした表示状態。x/y は CSS ピクセルのずらし量。 */
export type ZoomState = { scale: number; x: number; y: number };

/** 画面にちょうど収まった状態（等倍＝リセット位置）。 */
export const FIT: ZoomState = { scale: 1, x: 0, y: 0 };

/** 縮小はフィットまで。それ以上小さくしても余白が増えるだけで意味がない。 */
export const MIN_SCALE = 1;
/** 960x1280 の写真を 4K に映しても粗が出ない範囲。 */
export const MAX_SCALE = 8;
/** ＋／− ボタン1回あたりの倍率。 */
export const ZOOM_STEP = 1.4;
/** ダブルクリックで飛ぶ倍率（細部を見るとき用）。 */
export const DOUBLE_CLICK_SCALE = 2.5;

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) return min;
  return Math.min(max, Math.max(min, value));
}

export function clampScale(scale: number): number {
  return clamp(scale, MIN_SCALE, MAX_SCALE);
}

/**
 * object-contain したときの実表示サイズ。
 * グラスの出力は縦長（960x1280 等）なので、横長の画面では上下いっぱいに伸び、
 * 左右に余白が出る。パンの可動域はこの実サイズから決まる。
 */
export function containSize(content: Size, viewport: Size): Size {
  if (
    content.width <= 0 ||
    content.height <= 0 ||
    viewport.width <= 0 ||
    viewport.height <= 0
  ) {
    return { width: 0, height: 0 };
  }
  const ratio = Math.min(
    viewport.width / content.width,
    viewport.height / content.height,
  );
  return { width: content.width * ratio, height: content.height * ratio };
}

/**
 * 画面外へ飛ばさないよう、ずらし量を可動域へ丸める。
 * 拡大後の実サイズが画面に収まる軸は可動域 0＝中央に固定される。
 */
export function clampPan(
  state: ZoomState,
  fitted: Size,
  viewport: Size,
): ZoomState {
  const maxX = Math.max(0, (fitted.width * state.scale - viewport.width) / 2);
  const maxY = Math.max(0, (fitted.height * state.scale - viewport.height) / 2);
  return {
    scale: state.scale,
    x: clamp(state.x, -maxX, maxX),
    y: clamp(state.y, -maxY, maxY),
  };
}

/**
 * focal（中央を原点とした注視点）を動かさずに倍率を変える。
 * ホイールならカーソル位置、ボタンなら `{x:0,y:0}`（＝中央）を渡す。
 */
export function zoomAt(
  state: ZoomState,
  factor: number,
  focal: { x: number; y: number },
  fitted: Size,
  viewport: Size,
): ZoomState {
  const scale = clampScale(state.scale * factor);
  const ratio = scale / state.scale;
  return clampPan(
    {
      scale,
      x: focal.x - (focal.x - state.x) * ratio,
      y: focal.y - (focal.y - state.y) * ratio,
    },
    fitted,
    viewport,
  );
}

/** ドラッグの移動量を反映する。 */
export function panBy(
  state: ZoomState,
  dx: number,
  dy: number,
  fitted: Size,
  viewport: Size,
): ZoomState {
  return clampPan(
    { ...state, x: state.x + dx, y: state.y + dy },
    fitted,
    viewport,
  );
}

/**
 * ホイールの回転量 → 倍率。
 * deltaMode（0=px / 1=行 / 2=ページ）を吸収し、トラックパッドの
 * 大きな delta で一気に飛ばないよう上限を掛ける。
 */
export function wheelZoomFactor(deltaY: number, deltaMode = 0): number {
  if (!Number.isFinite(deltaY)) return 1;
  const pixels =
    deltaMode === 1 ? deltaY * 16 : deltaMode === 2 ? deltaY * 400 : deltaY;
  return Math.exp(-clamp(pixels, -160, 160) * 0.002);
}

/** ダブルクリックの拡大/リセットのトグル。 */
export function isFit(state: ZoomState): boolean {
  return state.scale <= MIN_SCALE;
}

/** style へ渡す transform。原点は中央（transform-origin: center）。 */
export function toTransform(state: ZoomState): string {
  const round = (value: number) => Math.round(value * 1000) / 1000;
  return `translate(${round(state.x)}px, ${round(state.y)}px) scale(${round(state.scale)})`;
}

/** ツールバーに出す表示倍率（フィット＝100%）。 */
export function toPercent(state: ZoomState): number {
  return Math.round(state.scale * 100);
}
