import preact from "@preact/preset-vite";
import tailwindcss from "@tailwindcss/vite";
import { defineConfig } from "vite";

const WEB_PORT = 5514;

// dev では Vite が :5514 で画面を配り、受信サーバ(:8787)へプロキシする。
// 本番（展示会）は `pnpm build` した dist を api 側が同一プロセスで配るので、1コマンドで立ち上がる。
export default defineConfig({
  plugins: [preact(), tailwindcss()],
  server: {
    host: true, // LAN の他端末（タブレット等）からもモニタを開けるように
    port: WEB_PORT,
    // 使用中なら別ポートへ黙ってずらさず、起動失敗として気付けるようにする。
    strictPort: true,
    proxy: {
      "/api": "http://localhost:8787",
      "/uploads": "http://localhost:8787",
    },
  },
  preview: {
    host: true,
    port: WEB_PORT,
    strictPort: true,
  },
  build: { outDir: "dist", emptyOutDir: true },
});
