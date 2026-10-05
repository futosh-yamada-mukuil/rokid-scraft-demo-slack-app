#!/usr/bin/env python3
"""Create a small AVD using an installed system image; preserve existing AVDs."""

import argparse
import os
import platform
import re
from pathlib import Path


def create_avd(sdk: Path, avd_home: Path, name: str) -> Path:
    if not re.fullmatch(r"[A-Za-z0-9_-]+", name):
        raise ValueError("AVD名には英数字、_、- だけを使用してください。")
    directory = avd_home / f"{name}.avd"
    index = avd_home / f"{name}.ini"
    if directory.exists() or index.exists():
        config_file = directory / "config.ini"
        if not config_file.is_file() or not index.is_file():
            raise ValueError(f"既存AVDの設定が不完全です: {directory}。別名を指定してください。")
        # Emulatorは起動時に「key = value」へ書き直すため、両形式を受け付ける。
        config = dict(tuple(part.strip() for part in line.split("=", 1)) for line in config_file.read_text().splitlines() if "=" in line)
        if any(config.get(key) != value for key, value in (
            ("hw.lcd.width", "480"), ("hw.lcd.height", "640"), ("hw.lcd.density", "240"),
        )):
            raise ValueError(f"既存の{name}は480×640 / 240dpiではありません。別名を指定してください。")
        return directory

    arch = "arm64-v8a" if platform.machine().lower() in ("arm64", "aarch64") else "x86_64"
    candidates = []
    for image in (sdk / "system-images").glob(f"android-*/*/{arch}"):
        api = image.parent.parent.name.removeprefix("android-")
        if not re.fullmatch(r"\d+(?:\.\d+)?", api) or float(api) < 28:
            continue
        if image.parent.name not in ("default", "google_apis", "google_apis_playstore"):
            continue
        if (image / "system.img").is_file() and (image / "package.xml").is_file():
            candidates.append((api != "32", float(api), image))
    if not candidates:
        raise ValueError(
            f"{arch}のAndroidイメージがありません。Android StudioのSDK Managerで"
            "Android 12L（API 32）のGoogle APIs System Imageをインストールしてください。"
        )
    image = min(candidates)[2]
    tag = image.parent.name
    target = image.parent.parent.name
    config = {
        "AvdId": name,
        "avd.ini.displayname": name,
        "avd.ini.encoding": "UTF-8",
        "abi.type": arch,
        "hw.cpu.arch": arch,
        "hw.cpu.ncore": "4",
        "hw.ramSize": "2048",
        "vm.heapSize": "256",
        "hw.lcd.width": "480",
        "hw.lcd.height": "640",
        "hw.lcd.density": "240",
        "hw.initialOrientation": "portrait",
        "hw.keyboard": "yes",
        "hw.dPad": "yes",
        "hw.mainKeys": "yes",
        "hw.camera.back": "emulated",
        "hw.camera.front": "emulated",
        "hw.audioInput": "no",
        "hw.gpu.enabled": "yes",
        "hw.gpu.mode": "software",
        "hw.battery": "yes",
        "hw.sdCard": "no",
        "hw.sensors.proximity": "no",
        "disk.dataPartition.size": "2G",
        "image.sysdir.1": image.relative_to(sdk).as_posix() + "/",
        "tag.id": tag,
        "tag.display": "Google APIs" if tag == "google_apis" else tag,
        "target": target,
        "PlayStore.enabled": "false",
        "showDeviceFrame": "no",
        "skin.dynamic": "yes",
        "skin.name": "480x640",
        "skin.path": "480x640",
        "fastboot.forceColdBoot": "yes",
        "fastboot.forceFastBoot": "no",
    }
    directory.mkdir(parents=True)
    (directory / "config.ini").write_text("".join(f"{key}={value}\n" for key, value in config.items()))
    index.write_text(f"avd.ini.encoding=UTF-8\npath={directory}\ntarget={target}\n")
    return directory


def main() -> None:
    parser = argparse.ArgumentParser(description="Rokid UI確認用AVD（480×640 / 240dpi）を作成")
    parser.add_argument("--name", default="Rokid_Test")
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", str(Path.home() / "Library/Android/sdk")))))
    parser.add_argument("--avd-home", type=Path, default=Path(os.environ.get("ANDROID_AVD_HOME", str(Path.home() / ".android/avd"))))
    args = parser.parse_args()
    try:
        result = create_avd(args.sdk.expanduser().resolve(), args.avd_home.expanduser().resolve(), args.name)
    except ValueError as error:
        parser.exit(1, f"{error}\n")
    print(f"AVD準備完了: {result}（480×640 / 240dpi）")


if __name__ == "__main__":
    main()
