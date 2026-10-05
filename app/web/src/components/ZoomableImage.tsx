import { useEffect, useRef, useState } from "preact/hooks";

import {
  containSize,
  DOUBLE_CLICK_SCALE,
  FIT,
  isFit,
  MAX_SCALE,
  MIN_SCALE,
  panBy,
  type Size,
  toPercent,
  toTransform,
  wheelZoomFactor,
  ZOOM_STEP,
  type ZoomState,
  zoomAt,
} from "../zoomState.ts";

/**
 * 写真の拡大縮小プレビュー。
 *
 * 表示は「画面いっぱいに object-contain で置いた画像を transform でずらす」だけ。
 * 拡大率や可動域の計算は zoomState.ts（純粋関数）に寄せてあり、ここは
 * DOM の実寸を測って渡す係に徹する。
 *
 * 展示会では来場者に大画面で見せるので、等倍＝**画面にフィット**とした
 * （原寸ピクセル等倍ではない）。960x1280 の写真はそのままだと大画面で小さい。
 */
export function ZoomableImage({ src, alt }: { src: string; alt: string }) {
  const stageRef = useRef<HTMLDivElement>(null);
  const dragFrom = useRef<{ x: number; y: number } | null>(null);

  const [natural, setNatural] = useState<Size>({ width: 0, height: 0 });
  const [viewport, setViewport] = useState<Size>({ width: 0, height: 0 });
  const [state, setState] = useState<ZoomState>(FIT);
  const [dragging, setDragging] = useState(false);

  const fitted = containSize(natural, viewport);
  const zoomed = !isFit(state);

  // 表示領域の実寸。ウィンドウのリサイズや全画面化で変わるので観測し続ける。
  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;

    const measure = () =>
      setViewport({ width: stage.clientWidth, height: stage.clientHeight });
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(stage);
    return () => observer.disconnect();
  }, []);

  // ホイールはページのスクロールに取られると拡大できないので preventDefault する。
  // JSX の onWheel だと passive 扱いになる環境があるため、明示的に登録する。
  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;

    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const rect = stage.getBoundingClientRect();
      const focal = {
        x: event.clientX - rect.left - rect.width / 2,
        y: event.clientY - rect.top - rect.height / 2,
      };
      const factor = wheelZoomFactor(event.deltaY, event.deltaMode);
      setState((prev) => zoomAt(prev, factor, focal, fitted, viewport));
    };

    stage.addEventListener("wheel", onWheel, { passive: false });
    return () => stage.removeEventListener("wheel", onWheel);
  }, [fitted, viewport]);

  /** ボタン操作は画面中央を注視点にする。 */
  const zoomFromCentre = (factor: number) =>
    setState((prev) => zoomAt(prev, factor, { x: 0, y: 0 }, fitted, viewport));

  const startDrag = (event: PointerEvent) => {
    if (!zoomed) return; // フィット中は動かす先が無い
    (event.currentTarget as HTMLElement).setPointerCapture(event.pointerId);
    dragFrom.current = { x: event.clientX, y: event.clientY };
    setDragging(true);
  };

  const moveDrag = (event: PointerEvent) => {
    const from = dragFrom.current;
    if (!from) return;
    const dx = event.clientX - from.x;
    const dy = event.clientY - from.y;
    dragFrom.current = { x: event.clientX, y: event.clientY };
    setState((prev) => panBy(prev, dx, dy, fitted, viewport));
  };

  const endDrag = () => {
    dragFrom.current = null;
    setDragging(false);
  };

  /** ダブルクリックで「フィット ⇄ 拡大」。細部を見たいときの近道。 */
  const toggleZoom = (event: MouseEvent) => {
    const stage = stageRef.current;
    if (!stage) return;
    if (zoomed) {
      setState(FIT);
      return;
    }
    const rect = stage.getBoundingClientRect();
    const focal = {
      x: event.clientX - rect.left - rect.width / 2,
      y: event.clientY - rect.top - rect.height / 2,
    };
    setState((prev) =>
      zoomAt(prev, DOUBLE_CLICK_SCALE / prev.scale, focal, fitted, viewport),
    );
  };

  return (
    <div className="absolute inset-0 flex flex-col">
      {/* biome-ignore lint/a11y/noStaticElementInteractions: 画像を掴んで動かす面。操作は下のツールバーからキーボードでも行える */}
      <div
        ref={stageRef}
        className={`relative min-h-0 flex-1 touch-none overflow-hidden ${
          zoomed ? (dragging ? "cursor-grabbing" : "cursor-grab") : ""
        }`}
        onPointerDown={startDrag}
        onPointerMove={moveDrag}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
        onDblClick={toggleZoom}
      >
        <img
          className="h-full w-full origin-center object-contain select-none"
          style={{
            transform: toTransform(state),
            // ドラッグ中に補間が入ると指に付いてこないので、その間だけ切る。
            transition: dragging ? "none" : "transform 120ms ease-out",
          }}
          src={src}
          alt={alt}
          draggable={false}
          onLoad={(event) => {
            const image = event.currentTarget;
            setNatural({
              width: image.naturalWidth,
              height: image.naturalHeight,
            });
          }}
        />
      </div>

      <Toolbar
        percent={toPercent(state)}
        canZoomIn={state.scale < MAX_SCALE}
        canZoomOut={state.scale > MIN_SCALE}
        canReset={zoomed || state.x !== 0 || state.y !== 0}
        onZoomIn={() => zoomFromCentre(ZOOM_STEP)}
        onZoomOut={() => zoomFromCentre(1 / ZOOM_STEP)}
        onReset={() => setState(FIT)}
      />
    </div>
  );
}

/**
 * 拡大縮小の操作列。動画のコントロールバーと同じ見た目に揃えてある
 * （base-100 の半透明＋境界線）ので、写真と動画で画面下部の印象が変わらない。
 */
function Toolbar({
  percent,
  canZoomIn,
  canZoomOut,
  canReset,
  onZoomIn,
  onZoomOut,
  onReset,
}: {
  percent: number;
  canZoomIn: boolean;
  canZoomOut: boolean;
  canReset: boolean;
  onZoomIn: () => void;
  onZoomOut: () => void;
  onReset: () => void;
}) {
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 flex justify-center p-3">
      <div className="bg-base-100/85 border-base-300 pointer-events-auto flex items-center gap-1 rounded-full border px-2 py-1 shadow-lg backdrop-blur">
        <button
          type="button"
          className="btn btn-ghost btn-sm btn-circle text-lg"
          onClick={onZoomOut}
          disabled={!canZoomOut}
          aria-label="縮小"
          title="縮小（ホイールでも可）"
        >
          −
        </button>

        <span className="w-14 text-center font-mono text-xs tabular-nums opacity-70">
          {percent}%
        </span>

        <button
          type="button"
          className="btn btn-ghost btn-sm btn-circle text-lg"
          onClick={onZoomIn}
          disabled={!canZoomIn}
          aria-label="拡大"
          title="拡大（ホイールでも可）"
        >
          ＋
        </button>

        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={onReset}
          disabled={!canReset}
          title="画面に合わせる（ダブルクリックでも可）"
        >
          等倍
        </button>
      </div>
    </div>
  );
}
