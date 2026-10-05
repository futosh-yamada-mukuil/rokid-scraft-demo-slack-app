import type { Change, Entry } from "shared";

/** SSE で届いた差分を一覧へ反映する。UI から分離して状態遷移を検証可能にする。 */
export function applyChange(items: readonly Entry[], change: Change): Entry[] {
  switch (change.type) {
    case "reply-added":
      return [...items];
    case "added":
      return [
        change.entry,
        ...items.filter((item) => item.id !== change.entry.id),
      ];
    case "deleted":
      return items.filter((item) => item.id !== change.id);
    case "cleared":
      return [];
  }
}

/**
 * ビューアの前後移動。`step` は -1（前）/ +1（次）。
 *
 * 端と、表示中の1件が消えた場合（SSE の deleted / cleared）は null を返す。
 * 一覧は新着で先頭が伸びるので、位置ではなく id を持ち回して都度引き直す。
 */
export function neighborId(
  items: readonly Entry[],
  id: string,
  step: number,
): string | null {
  const index = items.findIndex((item) => item.id === id);
  if (index < 0) return null;
  const next = index + step;
  return next >= 0 && next < items.length ? items[next].id : null;
}
