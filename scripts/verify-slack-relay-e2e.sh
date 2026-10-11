#!/usr/bin/env bash
# 実物の中継サーバと端末側コード（通信・翻訳キャッシュ・通知）の通し確認。
#
#   bash scripts/verify-slack-relay-e2e.sh            現在の構成
#   bash scripts/verify-slack-relay-e2e.sh legacy     改善前の構成（比較用。API_SRC で旧版の app/api/src を指す）
#
# Slack は模擬サーバ（app/api/test/support/fake-slack.ts）。実際のワークスペースへは接続しない。
# 端末の JNI・UI・実機の無線は含まない（JVM 上で端末側のロジックを動かす）。
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
mode="${1:-current}"
case "$mode" in
    current)
        export SLACK_RELAY_E2E_CHANGE_WAIT="${SLACK_RELAY_E2E_CHANGE_WAIT:-25}"
        export SLACK_RELAY_E2E_LIMIT_MS="${SLACK_RELAY_E2E_LIMIT_MS:-8000}"
        ;;
    legacy)
        # 旧版は 30 秒キャッシュ＋10 秒ごとの定期取得。上限は「仕様書の 30〜40 秒」に余裕を足した値。
        export SLACK_RELAY_E2E_CHANGE_WAIT=0
        export SLACK_RELAY_E2E_LIMIT_MS="${SLACK_RELAY_E2E_LIMIT_MS:-60000}"
        # 返信の時刻を旧版の 30 秒キャッシュに対して一様にばらつかせる（直前の通知に位相が揃うのを避ける）。
        export SLACK_RELAY_E2E_MAX_GAP_MS="${SLACK_RELAY_E2E_MAX_GAP_MS:-40000}"
        [[ -n "${API_SRC:-}" ]] && export SLACK_E2E_API_SRC="$API_SRC"
        ;;
    *) echo "使い方: bash scripts/verify-slack-relay-e2e.sh [current|legacy]" >&2; exit 1 ;;
esac

ready="$(mktemp)"
node --experimental-strip-types --no-warnings scripts/slack-relay-e2e-server.ts > "$ready" &
server=$!
trap 'kill "$server" 2>/dev/null || true; rm -f "$ready"' EXIT
for _ in $(seq 1 50); do
    [[ -s "$ready" ]] && break
    kill -0 "$server" 2>/dev/null || { echo "中継サーバを起動できませんでした" >&2; exit 1; }
    sleep 0.2
done
[[ -s "$ready" ]] || { echo "中継サーバの起動がタイムアウトしました" >&2; exit 1; }
SLACK_RELAY_E2E_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["relay"])' "$ready")"
SLACK_RELAY_E2E_CONTROL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["control"])' "$ready")"
export SLACK_RELAY_E2E_URL SLACK_RELAY_E2E_CONTROL
echo "▶ 中継サーバ $SLACK_RELAY_E2E_URL（Slackは模擬・$mode）"

report="app/rokid/build/test-results/testDebugUnitTest/TEST-com.scraft.glassreport.net.slack.SlackRelayEndToEndTest.xml"
rm -f "$report"
./gradlew :rokid:testDebugUnitTest --tests '*SlackRelayEndToEndTest*' --rerun -q
python3 - "$report" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
if int(root.get("skipped", "0")):
    sys.exit("通し確認が実行されませんでした（宛先がテストへ渡っていません）")
for line in (root.findtext("system-out") or "").splitlines():
    if line.startswith("E2E_"):
        key, _, value = line.partition("=")
        if key.endswith("_MS"):
            values = [int(v) for v in value.split(",") if v]
            print(f"{key}: {values}  平均 {sum(values) / len(values):.0f}ms  最大 {max(values)}ms")
        else:
            print(f"{key}: {value}")
PY
