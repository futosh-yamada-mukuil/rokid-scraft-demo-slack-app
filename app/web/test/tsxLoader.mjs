import { register } from "node:module";

/**
 * `.tsx` を node:test から import できるようにする。
 *
 * Node 標準の型剥がし（--experimental-strip-types）は `.ts` しか読めず、JSX を落とせない。
 * 変換は `tsxHooks.mjs` が Vite 同梱の Oxc で行う（型検査はしない。それは
 * `pnpm typecheck` の担当）。
 *
 * 同期版の `registerHooks` ではなく `register`（別スレッドで動くので非同期フックを置ける）を
 * 使うのは、Oxc の変換 API が Promise を返すため。
 * **TypeScript の `transpileModule` は使えない** — TypeScript 7 はプログラム API を
 * 7.1 まで提供せず、`ts.ScriptTarget` すら存在しない。
 *
 * 副作用のみのモジュール。コンポーネントを読む前に import すること。
 * 静的 import は本文より先に評価されるので、対象の `.tsx` は
 * `await import(...)` で動的に読む必要がある。
 */
register("./tsxHooks.mjs", import.meta.url);
