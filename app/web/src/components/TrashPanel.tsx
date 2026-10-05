import { useTrash } from "../useTrash.ts";

/**
 * ゴミ箱。
 *
 * 一覧の削除は**論理削除**で、消えたように見えてここに溜まる。
 * web からワンクリックで消せてしまうため、取り返しがつくようにしてある。
 * 本当に消えるのは「完全に削除」を押したときだけ。
 */
export function TrashPanel({ onClose }: { onClose: () => void }) {
  const { items, restore, purge } = useTrash();

  return (
    <section className="border-base-300 bg-base-200/40 border-b px-5 py-4">
      <div className="mb-3 flex items-center gap-3">
        <h2 className="text-sm font-bold">ゴミ箱</h2>
        <span className="text-xs opacity-50">{items.length} 件</span>
        <span className="text-xs opacity-50">
          削除は論理削除です。ここから戻せます
        </span>

        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            className="btn btn-error btn-outline btn-xs"
            disabled={items.length === 0}
            onClick={() => {
              if (confirm(`${items.length} 件を完全に削除します。戻せません。`))
                purge();
            }}
          >
            完全に削除
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-xs"
            onClick={onClose}
          >
            閉じる
          </button>
        </div>
      </div>

      {items.length === 0 ? (
        <p className="py-4 text-center text-sm opacity-50">ゴミ箱は空です</p>
      ) : (
        <ul className="grid gap-2 [grid-template-columns:repeat(auto-fill,minmax(15rem,1fr))]">
          {items.map((entry) => (
            <li
              key={entry.id}
              className="border-base-300 flex items-center gap-2 rounded-lg border px-2 py-1.5"
            >
              <span className="badge badge-ghost badge-sm shrink-0">
                {entry.kind === "video" ? "動画" : "写真"}
              </span>
              <span className="min-w-0 flex-1 truncate text-sm">
                {entry.comment || (
                  <span className="opacity-50">（コメントなし）</span>
                )}
              </span>
              <span className="shrink-0 font-mono text-xs opacity-50">
                {entry.time}
              </span>
              <button
                type="button"
                className="btn btn-ghost btn-xs shrink-0"
                onClick={() => restore(entry.id)}
                title="元に戻す"
              >
                戻す
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
