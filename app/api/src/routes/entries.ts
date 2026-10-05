import type { FastifyInstance } from "fastify";
import { type Auth, requireTokenOrLocal } from "../auth.ts";
import { ID_RE } from "../config.ts";
import type { EventBus } from "../events.ts";
import type { Storage } from "../storage.ts";

export type EntryDeps = { storage: Storage; bus: EventBus; auth: Auth };

/** 一覧と削除。削除は SSE で配るので、開いている全ての画面から消える。 */
export async function entryRoutes(
  app: FastifyInstance,
  { storage, bus, auth }: EntryDeps,
): Promise<void> {
  app.get("/api/list", async () => storage.list());

  app.delete<{ Params: { id: string } }>(
    "/api/entries/:id",
    { preHandler: requireTokenOrLocal(auth) },
    async (req, reply) => {
      const { id } = req.params;
      if (!ID_RE.test(id)) return reply.code(400).send({ error: "bad id" });

      const removed = await storage.remove(id);
      if (!removed) return reply.code(404).send({ error: "not found" });

      console.log(`🗑  削除 ${id}`);
      bus.broadcast({ type: "deleted", id });
      return { ok: true, id };
    },
  );

  // 全件をゴミ箱へ。展示会でデモの合間にリセットするため。
  app.delete(
    "/api/entries",
    { preHandler: requireTokenOrLocal(auth) },
    async () => {
      const removed = await storage.clear();
      console.log(`🗑  全件をゴミ箱へ ${removed} 件`);
      bus.broadcast({ type: "cleared" });
      return { ok: true, removed };
    },
  );

  // ---- ゴミ箱 ----
  app.get("/api/trash", async () => storage.listTrash());

  /** ゴミ箱から live へ戻す。誤削除の救済。 */
  app.post<{ Params: { id: string } }>(
    "/api/trash/:id/restore",
    { preHandler: requireTokenOrLocal(auth) },
    async (req, reply) => {
      const { id } = req.params;
      if (!ID_RE.test(id)) return reply.code(400).send({ error: "bad id" });

      const restored = await storage.restore(id);
      if (!restored) return reply.code(404).send({ error: "not found" });

      console.log(`♻️  復元 ${id}`);
      bus.broadcast({ type: "added", entry: restored });
      return { ok: true, entry: restored };
    },
  );

  /** ゴミ箱を空にする。**ここだけが本当に消える操作**。 */
  app.delete(
    "/api/trash",
    { preHandler: requireTokenOrLocal(auth) },
    async () => {
      const purged = await storage.purgeTrash();
      console.log(`🔥 ゴミ箱を空にした ${purged} 件`);
      return { ok: true, purged };
    },
  );
}
