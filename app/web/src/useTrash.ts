import { useCallback, useEffect, useState } from "preact/hooks";
import type { Entry } from "shared";

import { fetchTrash, purgeTrash, restoreEntry } from "./api.ts";

/** ゴミ箱を開いている間だけ取り直す間隔。閉じていれば何もしない。 */
const POLL_MS = 2_000;

/**
 * ゴミ箱の中身。
 *
 * live の一覧（useEntries）は SSE で差分が届くが、ゴミ箱は**開いている時だけ**見れば十分なので
 * 単純に取り直す。開いたまま裏で削除しても追随できるよう、マウント中は軽くポーリングする。
 */
export function useTrash() {
  const [items, setItems] = useState<Entry[]>([]);

  const reload = useCallback(async () => {
    try {
      setItems(await fetchTrash());
    } catch {
      // サーバ再起動中など。次の tick で回復する
    }
  }, []);

  useEffect(() => {
    reload();
    const timer = setInterval(reload, POLL_MS);
    return () => clearInterval(timer);
  }, [reload]);

  const restore = useCallback(
    async (id: string) => {
      setItems((prev) => prev.filter((p) => p.id !== id)); // 楽観的に消す
      try {
        await restoreEntry(id);
      } finally {
        reload();
      }
    },
    [reload],
  );

  const purge = useCallback(async () => {
    setItems([]);
    try {
      await purgeTrash();
    } finally {
      reload();
    }
  }, [reload]);

  return { items, restore, purge, reload };
}
