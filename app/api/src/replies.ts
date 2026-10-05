import { randomUUID } from "node:crypto";
import { mkdir, readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

import type { LocalReply } from "shared";

export type ReplyStore = {
  add(input: Omit<LocalReply, "id" | "createdAt">): Promise<LocalReply>;
  list(device?: string): Promise<LocalReply[]>;
};

export function createReplyStore(dir: string): ReplyStore {
  return {
    async add(input) {
      await mkdir(dir, { recursive: true });
      const createdAt = new Date().toISOString();
      const id = `${Date.now()}-${randomUUID().slice(0, 8)}`;
      const reply: LocalReply = { id, createdAt, ...input };
      await writeFile(join(dir, `${id}.json`), JSON.stringify(reply), "utf8");
      return reply;
    },

    async list(device) {
      const names = await readdir(dir).catch(() => [] as string[]);
      const replies: LocalReply[] = [];
      for (const name of names.filter((value) => value.endsWith(".json"))) {
        try {
          const reply = JSON.parse(
            await readFile(join(dir, name), "utf8"),
          ) as LocalReply;
          if (!device || reply.device === device) replies.push(reply);
        } catch {
          // 書き込み途中・壊れたファイルは次のポーリングまで無視する
        }
      }
      return replies.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    },
  };
}
