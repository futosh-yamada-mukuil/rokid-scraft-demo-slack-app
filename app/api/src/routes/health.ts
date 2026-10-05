import type { FastifyInstance } from "fastify";

import { lanIp } from "../lan-ip.ts";

export type HealthDeps = { port: number };

/**
 * 死活確認と、モニタが「グラスに設定すべき宛先 URL」を組むための情報。
 * localhost ではなく LAN IP を返すのが要点（グラスの localhost はグラス自身）。
 */
export async function healthRoutes(
  app: FastifyInstance,
  { port }: HealthDeps,
): Promise<void> {
  app.get("/api/health", async () => ({ ok: true, ip: lanIp(), port }));
}
