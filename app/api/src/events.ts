import type { Change } from "shared";

/**
 * SSE の購読者を束ねるバス。新着・削除を即座にモニタへ押し出す。
 * アプリ1つに1インスタンス（createApp が作る）。グローバル状態を持たないので
 * テストで複数のアプリを並行して立てても干渉しない。
 */
export type EventBus = {
  /** 購読を登録し、解除する関数を返す。 */
  subscribe(push: (c: Change) => void): () => void;
  /** 全購読者へ変化を配る。複数の画面を開いていても同期する。 */
  broadcast(change: Change): void;
  /** 現在の購読数（監視・テスト用）。 */
  readonly size: number;
};

export function createEventBus(): EventBus {
  const subscribers = new Set<(c: Change) => void>();
  return {
    subscribe(push) {
      subscribers.add(push);
      return () => subscribers.delete(push);
    },
    broadcast(change) {
      for (const push of subscribers) push(change);
    },
    get size() {
      return subscribers.size;
    },
  };
}
