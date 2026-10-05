import { networkInterfaces } from "node:os";

/**
 * この PC の LAN IP を推定する（外部への送信はしない）。
 * グラスは localhost では届かない（グラスの localhost はグラス自身）ので、
 * 起動時にこの値を宛先として案内する。
 */
export function lanIp(): string {
  for (const addrs of Object.values(networkInterfaces())) {
    for (const a of addrs ?? []) {
      if (a.family === "IPv4" && !a.internal) return a.address;
    }
  }
  return "127.0.0.1";
}
