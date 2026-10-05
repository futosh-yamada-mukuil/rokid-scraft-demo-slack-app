import type { FastifyInstance } from "fastify";

import { type Auth, requireLocalhost } from "../auth.ts";

export type TokenDeps = { auth: Auth };

/**
 * 設定QR に載せるトークンの取得と再生成。
 *
 * **どちらも PC 本体（localhost）からのみ。** モニタ画面は LAN の他端末からも開けるが、
 * ここが誰にでも見えるとトークンを配って回るのと同じで、認証の意味が無くなる。
 */
export async function tokenRoutes(
  app: FastifyInstance,
  { auth }: TokenDeps,
): Promise<void> {
  app.get("/api/token", { preHandler: requireLocalhost }, async () => ({
    token: auth.token(),
  }));

  app.post("/api/token/rotate", { preHandler: requireLocalhost }, async () => ({
    token: auth.rotate(),
  }));
}
