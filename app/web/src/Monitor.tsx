import { useState } from "preact/hooks";

import { MediaViewer } from "./components/MediaViewer.tsx";
import { ThemePicker } from "./components/ThemePicker.tsx";
import { Thumb } from "./components/Thumb.tsx";
import { TrashPanel } from "./components/TrashPanel.tsx";
import { neighborId } from "./entryState.ts";
import { useEntries } from "./useEntries.ts";
import { useHealth } from "./useHealth.ts";

/**
 * 受信モニタ。画面の組み立てだけを持ち、取得・購読は useEntries に、
 * 宛先 URL の解決は useHealth に置いている。
 */
export function Monitor() {
  const { items, live, loadError, remove, clear } = useEntries();
  const health = useHealth();
  const [showTrash, setShowTrash] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);

  const uploadUrl = health ? `http://${health.ip}:${health.port}/upload` : "…";

  // ビューアは id で開く。一覧は SSE で伸び縮みするので位置を覚えない。
  // 表示中の1件が消えたら（削除・全消し）見つからなくなり、ビューアも消える。
  const viewing = openId ? (items.find((i) => i.id === openId) ?? null) : null;
  const prevId = viewing ? neighborId(items, viewing.id, -1) : null;
  const nextId = viewing ? neighborId(items, viewing.id, 1) : null;

  return (
    <>
      <header className="bg-base-100/95 border-base-300 sticky top-0 z-10 flex flex-wrap items-center gap-3 border-b px-5 py-3 backdrop-blur">
        <h1 className="text-base font-bold">SCRAFT 現場レポート</h1>

        <span
          className={`size-2 rounded-full ${live ? "bg-success" : "bg-error"}`}
          title={live ? "接続中（SSE）" : "未接続（ポーリングで代替中）"}
        />
        <code className="font-mono text-xs opacity-70">POST {uploadUrl}</code>

        <div className="ml-auto flex items-center gap-2">
          <span className="text-xs opacity-50">{items.length} 件</span>
          <ThemePicker />
          <button
            type="button"
            className={`btn btn-xs ${showTrash ? "btn-active" : "btn-ghost"}`}
            onClick={() => setShowTrash((v) => !v)}
          >
            ゴミ箱
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-xs"
            // 論理削除なので確認は挟まない。取り返しはゴミ箱から効く。
            onClick={clear}
            disabled={items.length === 0}
          >
            全部ゴミ箱へ
          </button>
        </div>
      </header>

      {showTrash && <TrashPanel onClose={() => setShowTrash(false)} />}

      {loadError && (
        <div className="alert alert-error mx-5 mt-5" role="alert">
          <span>
            受信サーバ（localhost:8787）に接続できません。保存済みデータを読み込めていません。
          </span>
        </div>
      )}

      <main className="grid gap-x-4 gap-y-7 p-5 [grid-template-columns:repeat(auto-fill,minmax(17rem,1fr))]">
        {items.length === 0 && !loadError ? (
          <p className="col-span-full py-20 text-center opacity-50">
            まだ受信していません。
            <br />
            開発用APIに送信したデータがここに出ます。
          </p>
        ) : (
          items.map((i) => (
            <Thumb key={i.id} entry={i} onOpen={setOpenId} onRemove={remove} />
          ))
        )}
      </main>

      {viewing && (
        <MediaViewer
          entry={viewing}
          onClose={() => setOpenId(null)}
          onPrev={prevId ? () => setOpenId(prevId) : null}
          onNext={nextId ? () => setOpenId(nextId) : null}
        />
      )}
    </>
  );
}
