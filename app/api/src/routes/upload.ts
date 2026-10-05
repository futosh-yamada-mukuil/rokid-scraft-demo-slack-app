import type { FastifyInstance } from "fastify";
import { type Auth, requireToken } from "../auth.ts";
import { KINDS, PHOTO, VIDEO } from "../config.ts";
import type { EventBus } from "../events.ts";
import { demoLanguage, type LanguageService } from "../language.ts";
import type { Storage } from "../storage.ts";

/**
 * X-Comment のデコード。不正な `%` が混ざっていると decodeURIComponent は
 * URIError を投げる（＝500 になる）。**コメントのために受信そのものを失敗させない**ので、
 * 復号できなければ生の文字列をそのまま採用する。
 */
function decodeComment(raw: string): string {
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

export type UploadDeps = {
  storage: Storage;
  bus: EventBus;
  auth: Auth;
  language: LanguageService;
};

/**
 * 受信口。グラスは `/upload`、web からは `/api/upload` を使う（同じ処理）。
 * 種別の判別は Content-Type で行う。未対応の型は黙って受けず 415 で返し、
 * 何が届いたかをログに残す（グラス側の実装ミスを早く見つけるため）。
 */
export async function uploadRoutes(
  app: FastifyInstance,
  { storage, bus, auth, language }: UploadDeps,
): Promise<void> {
  for (const path of ["/upload", "/api/upload"]) {
    app.post(path, { preHandler: requireToken(auth) }, async (req, reply) => {
      const ctype = ((req.headers["content-type"] ?? "").split(";")[0] ?? "")
        .trim()
        .toLowerCase();
      let comment = decodeComment(String(req.headers["x-comment"] ?? ""));
      const device = String(req.headers["x-device"] ?? "");
      let sourceLanguage = demoLanguage(req.headers["x-language"]);

      let blob: Buffer | null = null;
      let audio: Buffer | undefined;
      let audioMime: string | undefined;
      let picked = PHOTO;

      if (ctype === "multipart/form-data") {
        for await (const part of req.parts()) {
          if (part.type === "file") {
            const bytes = await part.toBuffer();
            if (part.fieldname === "audio") {
              audio = bytes;
              audioMime = part.mimetype;
              continue;
            }
            blob = bytes;
            // Content-Type が分かればそれを、無ければ field 名で判断する。
            picked =
              KINDS[part.mimetype] ??
              (part.fieldname === "video" ? VIDEO : PHOTO);
          } else if (part.fieldname === "comment") {
            comment = String(part.value);
          } else if (part.fieldname === "language") {
            sourceLanguage = demoLanguage(part.value);
          }
        }
      } else {
        const found = KINDS[ctype];
        if (!found) {
          console.log(`⚠️  未対応の Content-Type: ${JSON.stringify(ctype)}`);
          return reply
            .code(415)
            .send({ error: `unsupported Content-Type: ${ctype}` });
        }
        picked = found;
        blob = req.body as Buffer;
      }

      if (!blob?.length) return reply.code(400).send({ error: "empty body" });

      // 録画には音声トラックが含まれるため、別WAVが無い場合もGeminiへ動画を渡してAUTO認識する。
      // 保存時はMP4をWAVとして複製せず、解析入力だけを差し替える。
      const analysisAudio =
        audio ?? (picked.kind === "video" ? blob : undefined);
      const analysisAudioMime =
        audioMime ?? (picked.kind === "video" ? "video/mp4" : undefined);

      const analysis =
        req.headers["x-skip-analysis"] === "true"
          ? { transcript: comment, japanese: comment, language: sourceLanguage }
          : await language
              .analyzeReport({
                text: comment,
                audio: analysisAudio,
                audioMime: analysisAudioMime,
                language: sourceLanguage,
              })
              .catch((error) => {
                console.warn(
                  `⚠️  音声解析を継続できません: ${(error as Error).message}`,
                );
                return {
                  transcript: comment,
                  japanese: comment,
                  language: sourceLanguage,
                };
              });

      const entry = await storage.save({
        blob,
        kind: picked.kind,
        ext: picked.ext,
        comment: analysis.transcript,
        translatedComment: analysis.japanese,
        language: analysis.language,
        device,
        audio,
      });
      console.log(
        `✅ 受信 [${entry.kind}] ${entry.sizeKb}KB  comment=${JSON.stringify(comment)}  device=${JSON.stringify(device)}`,
      );
      bus.broadcast({ type: "added", entry });
      return reply.send({ ok: true, ...entry });
    });
  }
}
