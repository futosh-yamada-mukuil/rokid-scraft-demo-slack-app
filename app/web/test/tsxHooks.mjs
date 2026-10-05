import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

import { transformWithOxc } from "vite";

/**
 * `.tsx` の JSX を preact/jsx-runtime の呼び出しへ変換する load フック。
 *
 * 変換器は **Vite 8 が同梱する Oxc**（`transformWithOxc`）。アプリ本体を
 * 束ねているのと同じ変換器なので、テストとビルドで JSX の解釈がずれない。
 *
 * `transformWithOxc` は Promise を返すため、同期版の `registerHooks` では使えない。
 * このフックは `tsxLoader.mjs` から `module.register()` で登録する（別スレッド・非同期可）。
 */
export async function load(url, context, nextLoad) {
  if (!url.endsWith(".tsx")) return nextLoad(url, context);

  const path = fileURLToPath(url);
  const { code } = await transformWithOxc(await readFile(path, "utf8"), path, {
    jsx: { runtime: "automatic", importSource: "preact" },
  });

  return { format: "module", source: code, shortCircuit: true };
}
