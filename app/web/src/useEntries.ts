import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import type { Change, Entry } from "shared";

import { clearEntries, fetchList, removeEntry } from "./api.ts";
import { applyChange } from "./entryState.ts";

/** SSE が死んでいる間の保険。プロキシ越しで詰まる環境があるため入れてある。 */
const POLL_MS = 3_000;

/**
 * 一覧の取得と購読。
 *
 * 主経路は SSE（撮った瞬間に届く）。それに加えて一定間隔のポーリングを回し、
 * SSE が張れない／切れた場合でも必ず更新されるようにしている（二段構え）。
 */
export function useEntries() {
  const [items, setItems] = useState<Entry[]>([]);
  const [live, setLive] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const liveRef = useRef(false);

  const reload = useCallback(async () => {
    try {
      setItems(await fetchList());
      setLoadError(false);
    } catch {
      // サーバ再起動中など。画面で区別しつつ、次の tick で回復を試す。
      setLoadError(true);
    }
  }, []);

  const apply = useCallback((c: Change) => {
    setItems((prev) => applyChange(prev, c));
  }, []);

  useEffect(() => {
    reload();

    const es = new EventSource("/api/events");
    es.onopen = () => {
      liveRef.current = true;
      setLive(true);
    };
    es.onerror = () => {
      liveRef.current = false;
      setLive(false);
    };
    es.onmessage = (ev) => apply(JSON.parse(ev.data) as Change);

    // SSE が生きていれば軽い同期のため、死んでいれば主経路として回す。
    const timer = setInterval(reload, POLL_MS);

    return () => {
      es.close();
      clearInterval(timer);
    };
  }, [reload, apply]);

  const remove = useCallback(
    async (id: string) => {
      setItems((prev) => prev.filter((p) => p.id !== id)); // 楽観的に消す
      try {
        await removeEntry(id);
      } catch {
        reload();
      }
    },
    [reload],
  );

  const clear = useCallback(async () => {
    setItems([]);
    try {
      await clearEntries();
    } catch {
      reload();
    }
  }, [reload]);

  return { items, live, loadError, remove, clear, reload };
}
