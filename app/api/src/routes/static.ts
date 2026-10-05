import { extname } from "node:path";

import fastifyStatic from "@fastify/static";
import type { FastifyInstance } from "fastify";

export type StaticDeps = { uploadDir: string; webDist: string };

/**
 * 受信した実データと、モニタのビルド成果物を配る。
 *
 * `/uploads/` は Range リクエストに応答する必要がある（<video> のシークに必須）。
 * @fastify/static がこれを処理するのが fastify を選んだ理由。
 */
export async function staticRoutes(
  app: FastifyInstance,
  { uploadDir, webDist }: StaticDeps,
): Promise<void> {
  await app.register(fastifyStatic, {
    root: uploadDir,
    prefix: "/uploads/",
    decorateReply: false,
    // @fastify/static v10 で setHeaders の第1引数が Node の res から
    // fastify の reply 相当（header() を持つ）に変わった。setHeader は存在しない。
    setHeaders: (reply, path) => {
      if (extname(path) === ".mp4") reply.header("Content-Type", "video/mp4");
    },
  });

  // 展示会は api 1プロセスで web も配る → 1コマンドで立ち上がる。
  // dev は Vite が :5514 で配り、/api と /uploads をここへプロキシする。
  try {
    await app.register(fastifyStatic, {
      root: webDist,
      prefix: "/",
      decorateReply: false,
    });
  } catch {
    console.log(
      "（app/web/dist が無いので静的配信はスキップ。dev は Vite 側から見てください）",
    );
  }
}
