import { useEffect, useState } from "preact/hooks";
import type { Health } from "shared";

import { fetchHealth } from "./api.ts";

/** 開発用受信サーバの LAN IP とポート。テスト投稿先の表示に使う。 */
export function useHealth(): Health | null {
  const [health, setHealth] = useState<Health | null>(null);
  useEffect(() => {
    fetchHealth()
      .then(setHealth)
      .catch(() => {});
  }, []);
  return health;
}
