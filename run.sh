#!/usr/bin/env bash
#
# ビルド → インストール → 起動 を1コマンドで。
#
#   ./run.sh          実機グラスへ（デフォルト）
#   ./run.sh emu      Rokid_Test AVDを作成・起動し、UIプレビューを表示
#   ./run.sh emu feed 記録一覧を直接表示
#   ./run.sh glass    実機グラスへ（明示）
#   ./run.sh <serial> 任意のデバイスへ（adb devices のシリアル）
#
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
# local.properties が無いクローン直後でもビルドできるよう SDK の場所を補う。
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
APK="app/rokid/build/outputs/apk/debug/rokid-debug.apk"
PKG="com.scraft.glassreport"
ACT="${PKG}/.MainActivity"

target="${1:-glass}"

case "$target" in
  e|emu|emulator)
    exec bash scripts/run-emulator.sh "${2:-home}"
    ;;
  slack)
    exec bash scripts/run-emulator.sh "${2:-home}" slack
    ;;
esac

# 対象デバイスのシリアルを決定（接続状況から自動判別）
case "$target" in
  g|glass|glasses)
    # emulator 以外で device 状態のものを実機とみなす
    SERIAL="$("$ADB" devices | awk '$2=="device" && $1 !~ /^emulator-/{print $1; exit}')"
    [ -n "${SERIAL:-}" ] || { echo "✗ 実機グラスが見つかりません。USB接続とHi RokidのADBデバッグONを確認してください。"; exit 1; }
    ;;
  *)
    SERIAL="$target"   # シリアル直指定
    ;;
esac

echo "▶ ビルド中..."
./gradlew :rokid:assembleDebug -q

echo "▶ インストール → $SERIAL"
"$ADB" -s "$SERIAL" install -r "$APK"

echo "▶ 起動"
"$ADB" -s "$SERIAL" shell am start -n "$ACT" >/dev/null
echo "✅ 完了（${SERIAL}）"
