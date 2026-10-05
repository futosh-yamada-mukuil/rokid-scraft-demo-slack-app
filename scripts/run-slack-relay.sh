#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# AVD reaches this loopback listener through adb reverse. Don't expose PC administration to the LAN.
export HOST=127.0.0.1
export PORT=8787
exec pnpm --filter api start
