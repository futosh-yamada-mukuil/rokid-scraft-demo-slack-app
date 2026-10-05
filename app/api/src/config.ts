import type { EntryKind } from "shared";

/**
 * 実行環境に依存しない定数だけを置く。
 * パスやポートは実行時のオプション（createApp の引数）で渡す — テストが
 * 一時ディレクトリを指せるようにするため、ここにグローバルな状態は持たない。
 */

/** 写真は約200KB、動画は720p 3秒で 4〜5MB。余裕を見て 64MB まで受ける。 */
export const MAX_BODY = 64 * 1024 * 1024;

export type Kind = { kind: EntryKind; ext: string };

export const PHOTO: Kind = { kind: "photo", ext: ".jpg" };
export const VIDEO: Kind = { kind: "video", ext: ".mp4" };

/**
 * 受け付ける Content-Type → 種別と拡張子。これ以外は 415 で拒否する。
 * グラス側はこのヘッダで写真と動画を打ち分けている。
 */
export const KINDS: Record<string, Kind> = {
  "image/jpeg": PHOTO,
  "image/jpg": PHOTO,
  "video/mp4": VIDEO,
};

/** id は自分で組んだ形式だけを許す。パス遡上を防ぐための検証。 */
export const ID_RE = /^\d{8}-\d{6}-[0-9a-f]{4}$/;
