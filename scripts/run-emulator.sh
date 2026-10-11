#!/usr/bin/env bash
# AVDの作成 → カメラ・マイク設定 → 起動 → APKビルド → インストール → 表示。
set -euo pipefail
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
cd "$project_dir"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
avd_name="${ROKID_AVD_NAME:-Rokid_Test}"
route="${1:-home}"
mode="${2:-preview}"
case "$mode" in
    preview|slack) ;;
    *) echo "起動モード: preview / slack" >&2; exit 1 ;;
esac
if [[ "$mode" == "slack" ]]; then
    audio="${ROKID_EMULATOR_AUDIO:-1}"
    requested_camera="${ROKID_EMULATOR_CAMERA:-auto}"
else
    audio="${ROKID_EMULATOR_AUDIO:-0}"
    requested_camera="${ROKID_EMULATOR_CAMERA:-emulated}"
fi
case "$audio" in
    0|1) ;;
    *) echo "ROKID_EMULATOR_AUDIO: 0（音声なし） / 1（音声あり）" >&2; exit 1 ;;
esac
case "$route" in
    home|auth|capture-photo|feed|settings|readback) ;;
    *) echo "画面名: home / auth / capture-photo / feed / settings / readback" >&2; exit 1 ;;
esac
adb="$ANDROID_HOME/platform-tools/adb"
emulator="$ANDROID_HOME/emulator/emulator"
[[ -x "$adb" && -x "$emulator" ]] || { echo "Android StudioのSDK ManagerでAndroid EmulatorとPlatform-Toolsをインストールしてください。" >&2; exit 1; }
# Apple SiliconのMacにIntel版のEmulatorが入っていると、起動時に「failed to initialize HVF」で落ちる。
if [[ "$(uname -s)" == "Darwin" && "$(sysctl -n hw.optional.arm64 2>/dev/null)" == "1" ]] && ! file "$emulator" | grep -q "arm64"; then
    echo "このMacはApple Siliconですが、Android EmulatorがIntel版です。Android StudioのSDK ManagerでAndroid Emulatorを入れ直し、arm64-v8aのシステムイメージ（Android 12 / API 32）を追加してください。" >&2
    exit 1
fi
command -v python3 >/dev/null || { echo "Python 3が必要です。" >&2; exit 1; }
camera="$(python3 scripts/emulator-media.py camera --emulator "$emulator" --requested "$requested_camera")"
python3 scripts/create-rokid-avd.py --sdk "$ANDROID_HOME" --name "$avd_name"
media_config="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$avd_name.avd/config.ini"

serial_for_avd() {
    local candidate name
    for candidate in $("$adb" devices | awk '$2 ~ /^(device|offline)$/ && $1 ~ /^emulator-/ { print $1 }'); do
        name="$("$adb" -s "$candidate" emu avd name 2>/dev/null | tr -d '\r' | head -1)"
        if [[ "$name" == "$avd_name" ]]; then
            echo "$candidate"
            return
        fi
    done
}

serial="$(serial_for_avd)"
mkdir -p .local/emulator
if [[ -n "$serial" && "$(python3 scripts/emulator-media.py needs-restart --name "$avd_name" --config "$media_config" --camera "$camera" --audio "$audio")" == "yes" ]]; then
    echo "▶ $avd_name を再起動してPCカメラ・マイク設定を反映（端末データは保持）"
    "$adb" -s "$serial" emu kill
    shutdown_deadline=$((SECONDS + 30))
    while "$adb" -s "$serial" get-state >/dev/null 2>&1 || [[ "$(python3 scripts/emulator-media.py running --name "$avd_name")" == "yes" ]]; do
        if (( SECONDS >= shutdown_deadline )); then
            echo "AVDを終了できませんでした。$avd_name のウィンドウを閉じて再実行してください。" >&2
            exit 1
        fi
        sleep 1
    done
    serial=""
fi
python3 scripts/emulator-media.py configure --config "$media_config" --camera "$camera" --audio "$audio"
if [[ -z "$serial" ]]; then
    echo "▶ $avd_name を起動（480×640 / 240dpi / カメラ: $camera / 音声: ${audio}）"
    # ウィンドウを残し、シェル終了後もAVDを利用できるよう独立プロセスで起動する。
    emulator_pid="$(python3 scripts/emulator-media.py launch --emulator "$emulator" --name "$avd_name" --gpu "${ROKID_EMULATOR_GPU:-software}" --camera "$camera" --audio "$audio")"
fi

deadline=$((SECONDS + 600))
next_progress=$((SECONDS + 30))
while [[ -z "$serial" || "$("$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
    if (( SECONDS >= deadline )); then
        echo "AVD起動がタイムアウトしました。.local/emulator/emulator.logを確認してください。" >&2
        exit 1
    fi
    if [[ -n "${emulator_pid:-}" ]] && ! kill -0 "$emulator_pid" 2>/dev/null; then
        echo "AVDが終了しました。.local/emulator/emulator.logを確認してください。" >&2
        exit 1
    fi
    if (( SECONDS >= next_progress )); then
        echo "▶ Androidの初期化を待機中（初回起動は数分かかる場合があります）"
        next_progress=$((SECONDS + 30))
    fi
    sleep 2
    serial="$(serial_for_avd)"
done

if [[ "$audio" == "1" ]]; then
    "$adb" -s "$serial" emu avd hostmicon
else
    "$adb" -s "$serial" emu avd hostmicoff
fi

# 同名の手動作成AVDでも、表示サイズ・密度を端末の実測値へ合わせる。
"$adb" -s "$serial" shell wm size 480x640
"$adb" -s "$serial" shell wm density 240
"$adb" -s "$serial" shell settings put system font_scale 1.0
"$adb" -s "$serial" shell settings put system accelerometer_rotation 0
"$adb" -s "$serial" shell settings put system user_rotation 0

if [[ ! -f app/rokid/libs/sherpa-onnx-1.13.3.aar || ! -f app/rokid/src/main/assets/offline-stt/sensevoice-model.int8.onnx || ! -f app/rokid/src/main/assets/offline-stt/sensevoice-tokens.txt ]]; then
    bash scripts/fetch-offline-stt.sh
fi
if [[ "$mode" == "slack" ]]; then
    variant="slackEmulator"
    task="assembleSlackEmulator"
    package="com.scraft.glassreport.slack"
    echo "▶ Slack接続用APKをビルド（実際のSlackへ写真を送信できます）"
    "$adb" -s "$serial" reverse tcp:8787 tcp:8787
else
    variant="emulator"
    task="assembleEmulator"
    package="com.scraft.glassreport.emulator"
    echo "▶ UI専用APKをビルド（認証・APIキー不要）"
fi
# 完全なJDKを明示し、VS Code等のjlinkを持たないJREが自動選択されるのを避ける。
./gradlew --no-daemon -Dorg.gradle.java.installations.auto-detect=false \
    "-Dorg.gradle.java.installations.paths=$JAVA_HOME" ":rokid:$task" --console=plain
"$adb" -s "$serial" install -r "app/rokid/build/outputs/apk/$variant/rokid-$variant.apk"
"$adb" -s "$serial" shell pm grant "$package" android.permission.CAMERA
"$adb" -s "$serial" shell pm grant "$package" android.permission.RECORD_AUDIO
"$adb" -s "$serial" shell am force-stop "$package"
"$adb" -s "$serial" shell am start -W -n "$package/com.scraft.glassreport.MainActivity" --es start "$route"
if [[ "$mode" == "slack" ]]; then
    echo "完了: $avd_name / $serial / ${route}（Slack実接続。PC中継サーバとBot設定が必要）"
else
    echo "完了: $avd_name / $serial / ${route}（UIサンプル・外部送信なし）"
fi
