import { randomUUID } from "node:crypto";
import {
  mkdir,
  readdir,
  readFile,
  rename,
  rm,
  writeFile,
} from "node:fs/promises";
import { join, resolve } from "node:path";

import type { DemoLanguage, Entry, EntryKind } from "shared";

/**
 * 受信データの永続化。DB は持たず、バイナリはそのままファイル、
 * メタ情報は同名の .json として隣に置く。
 *
 * BLOB を DB に入れないのは、動画のシーク（Range リクエスト）をファイルから
 * 配る方が素直で、@fastify/static が Range とキャッシュヘッダを面倒見てくれるため。
 *
 * 保存先は引数で受ける（グローバルに持たない）。テストは一時ディレクトリを渡す。
 */

export type SaveArgs = {
  blob: Buffer;
  kind: EntryKind;
  ext: string;
  comment: string;
  translatedComment?: string;
  language?: DemoLanguage;
  device: string;
  audio?: Buffer;
};

export type Storage = {
  readonly dir: string;
  /** 論理削除の置き場。uploads の兄弟に置き、live の一覧に混ざらないようにする。 */
  readonly trashDir: string;
  save(args: SaveArgs): Promise<Entry>;
  /** 新しい順に全件。件数が増えたら索引を持つべきだが、展示会規模では不要。 */
  list(): Promise<Entry[]>;
  /** メタだけを差し替える（投稿済みの印を書き戻す用）。 */
  update(entry: Entry): Promise<void>;
  /**
   * **論理削除**。メタと本体を trash へ移すだけで消さない。存在しなければ null。
   * web から簡単に消せてしまうため、取り返しがつくようにしてある。
   */
  remove(id: string): Promise<Entry | null>;
  /** ゴミ箱の中身を新しい順に返す。 */
  listTrash(): Promise<Entry[]>;
  /** ゴミ箱から live へ戻す。存在しなければ null。 */
  restore(id: string): Promise<Entry | null>;
  /** ゴミ箱の中身を**完全に削除**する。消した件数を返す。 */
  purgeTrash(): Promise<number>;
  /** 全件をゴミ箱へ移す。展示会でデモの合間にリセットするため。件数を返す。 */
  clear(): Promise<number>;
};

const p2 = (n: number) => String(n).padStart(2, "0");

/** `20260814-161512-853d` 形式。ファイル名の基底にもなる。 */
function newId(now: Date): string {
  return (
    `${now.getFullYear()}${p2(now.getMonth() + 1)}${p2(now.getDate())}` +
    `-${p2(now.getHours())}${p2(now.getMinutes())}${p2(now.getSeconds())}` +
    `-${randomUUID().slice(0, 4)}`
  );
}

export function createStorage(
  dir: string,
  trashDir = resolve(dir, "..", "trash"),
): Storage {
  const metaPath = (id: string) => join(dir, `${id}.json`);

  /** 指定ディレクトリのメタを新しい順に読む。壊れたものは飛ばす。 */
  async function readAll(from: string): Promise<Entry[]> {
    const names = await readdir(from).catch(() => [] as string[]);
    const metas = names
      .filter((n) => n.endsWith(".json"))
      .sort()
      .reverse();

    const out: Entry[] = [];
    for (const n of metas) {
      try {
        out.push(JSON.parse(await readFile(join(from, n), "utf8")) as Entry);
      } catch {
        // 書き込み途中を読んだ場合など
      }
    }
    return out;
  }

  /** メタと本体を from → to へ移す。存在しなければ null。 */
  async function move(
    id: string,
    from: string,
    to: string,
  ): Promise<Entry | null> {
    let entry: Entry;
    try {
      entry = JSON.parse(
        await readFile(join(from, `${id}.json`), "utf8"),
      ) as Entry;
    } catch {
      return null;
    }
    await mkdir(to, { recursive: true });
    await rename(join(from, `${id}.json`), join(to, `${id}.json`));
    if (entry.file) {
      await rename(join(from, entry.file), join(to, entry.file)).catch(() => {
        // 本体が既に無くてもメタの移動は成立させる
      });
    }
    if (entry.audioFile) {
      await rename(
        join(from, entry.audioFile),
        join(to, entry.audioFile),
      ).catch(() => {
        // 音声だけ既に無くてもメタの移動は成立させる
      });
    }
    return entry;
  }

  return {
    dir,
    trashDir,

    async save(args) {
      await mkdir(dir, { recursive: true });
      const now = new Date();
      const id = newId(now);
      const file = `${id}${args.ext}`;
      await writeFile(join(dir, file), args.blob);
      const audioFile = args.audio?.length ? `${id}.wav` : null;
      if (audioFile && args.audio) {
        await writeFile(join(dir, audioFile), args.audio);
      }

      const entry: Entry = {
        id,
        time: `${p2(now.getMonth() + 1)}/${p2(now.getDate())} ${p2(now.getHours())}:${p2(now.getMinutes())}:${p2(now.getSeconds())}`,
        kind: args.kind,
        comment: args.comment,
        ...(args.language ? { language: args.language } : {}),
        ...(args.translatedComment
          ? { translatedComment: args.translatedComment }
          : {}),
        ...(audioFile ? { audioFile } : {}),
        device: args.device,
        file,
        sizeKb: Math.round(args.blob.length / 1024),
      };
      await writeFile(metaPath(id), JSON.stringify(entry), "utf8");
      return entry;
    },

    async list() {
      return readAll(dir);
    },

    async update(entry) {
      await writeFile(metaPath(entry.id), JSON.stringify(entry), "utf8");
    },

    async remove(id) {
      // 物理削除ではなく trash へ移す（web から簡単に消せてしまうため）。
      return move(id, dir, trashDir);
    },

    async listTrash() {
      return readAll(trashDir);
    },

    async restore(id) {
      return move(id, trashDir, dir);
    },

    async purgeTrash() {
      const names = await readdir(trashDir).catch(() => [] as string[]);
      await Promise.all(
        names.map((n) => rm(join(trashDir, n), { force: true })),
      );
      return names.filter((n) => n.endsWith(".json")).length;
    },

    async clear() {
      const entries = await readAll(dir);
      for (const e of entries) await move(e.id, dir, trashDir);
      return entries.length;
    },
  };
}
