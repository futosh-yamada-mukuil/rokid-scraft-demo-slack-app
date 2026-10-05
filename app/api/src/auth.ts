import { randomBytes } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import type { FastifyReply, FastifyRequest } from "fastify";

/**
 * 同一 LAN の第三者による投稿・削除を防ぐための共有トークン。
 *
 * **IP アドレスを隠す方式は採らない。** QR に載るのはプライベート IP で、同じ網にいれば
 * ポートスキャンで数秒で見つかる。base64 等で難読化しても復元は自明で、解除鍵を APK に
 * 置けば APK から取り出せる。守れるのは「住所を隠すこと」ではなく「資格情報を要求すること」。
 *
 * トークンはこのサーバが持ち主で、設定QR に載せてグラスへ渡す。
 * 有効期限は設けない（PC とグラスの時計ずれで会場のデモが止まるため）。
 * 漏れたと思ったら**再生成**する運用にする。
 */
export const TOKEN_HEADER = "x-auth-token";

/** ローカルホストと見なすアドレス。`trustProxy` は既定 OFF なので偽装できない。 */
const LOOPBACK = new Set(["127.0.0.1", "::1", "::ffff:127.0.0.1"]);

export type Auth = {
  /** 現在のトークン。 */
  token(): string;
  /** 新しいトークンを作って保存し、それを返す。 */
  rotate(): string;
  /** リクエストがトークンを持っているか。 */
  ok(req: FastifyRequest): boolean;
};

function generate(): string {
  // 128bit。QR の型番を無駄に太らせない長さに収める。
  return randomBytes(16).toString("base64url");
}

/**
 * トークンを [path] に置く（無ければ作る）。ファイルは gitignore 済みの想定。
 * 読めない・壊れている場合は作り直す（起動を止めない）。
 */
export function createAuth(path: string): Auth {
  let current = "";
  try {
    current = readFileSync(path, "utf8").trim();
  } catch {
    current = "";
  }
  if (!current) {
    current = generate();
    try {
      writeFileSync(path, current, { mode: 0o600 });
    } catch {
      // 書けなくてもプロセスが生きている間は使える
    }
  }

  return {
    token: () => current,
    rotate() {
      current = generate();
      try {
        writeFileSync(path, current, { mode: 0o600 });
      } catch {
        // 同上
      }
      return current;
    },
    ok(req) {
      const got = req.headers[TOKEN_HEADER];
      return typeof got === "string" && got.length > 0 && got === current;
    },
  };
}

/** 書き込み系ルートの前段。トークンが無ければ 401 で止める。 */
export function requireToken(auth: Auth) {
  return async (req: FastifyRequest, reply: FastifyReply) => {
    if (!auth.ok(req)) {
      await reply.code(401).send({
        error: "Unauthorized",
        message: "設定QR のトークンが必要です",
      });
    }
  };
}

/**
 * 破壊的な操作（削除・全消し・Teams 投稿）の前段。
 * **トークンを持っているか、PC 本体からか**のどちらかを要求する。
 *
 * モニタ画面は LAN の他端末からも開けるが、そこからの削除は通さない
 * （見るのは誰でも、消すのは持ち主だけ）。
 */
export function requireTokenOrLocal(auth: Auth) {
  return async (req: FastifyRequest, reply: FastifyReply) => {
    if (auth.ok(req) || LOOPBACK.has(req.ip)) return;
    await reply.code(403).send({
      error: "Forbidden",
      message: "この操作は PC 本体、または設定QR のトークンが必要です",
    });
  };
}

/** 操作者本人（PC のブラウザ）だけに許すルートの前段。 */
export async function requireLocalhost(
  req: FastifyRequest,
  reply: FastifyReply,
): Promise<void> {
  if (!LOOPBACK.has(req.ip)) {
    await reply.code(403).send({
      error: "Forbidden",
      message: "この操作は PC 本体からのみ行えます",
    });
  }
}
