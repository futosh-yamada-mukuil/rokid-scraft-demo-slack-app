import type { FastifyInstance } from "fastify";

import type { EventBus } from "../events.ts";

export type StreamDeps = { bus: EventBus };

/**
 * SSE。モニタは接続したままで、新着・削除が来た瞬間に受け取る。
 * web 側はこれに加えてポーリングのフォールバックを持つ（プロキシ越しで詰まる場合の保険）。
 */
export async function streamRoutes(
  app: FastifyInstance,
  { bus }: StreamDeps,
): Promise<void> {
  app.get("/api/events", (req, reply) => {
    reply.raw.writeHead(200, {
      "Content-Type": "text/event-stream",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
      // リバースプロキシを挟んだ場合にバッファされないように
      "X-Accel-Buffering": "no",
    });
    reply.raw.write(": connected\n\n");

    const unsubscribe = bus.subscribe((c) =>
      reply.raw.write(`data: ${JSON.stringify(c)}\n\n`),
    );
    // 経路上のタイムアウトで切られないよう定期的にコメント行を送る
    const beat = setInterval(() => reply.raw.write(": ping\n\n"), 20_000);

    req.raw.on("close", () => {
      clearInterval(beat);
      unsubscribe();
    });
  });
}
