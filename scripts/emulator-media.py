#!/usr/bin/env python3
"""Configure real host camera/microphone input without wiping AVD data."""

import argparse
import re
import shlex
import subprocess
from pathlib import Path


def listed_cameras(listing: str) -> list[str]:
    """webcamN labels from `emulator -webcam-list`, in listed order.

    The format depends on the emulator version; newer ones print the device name first:
      old: Camera 'webcam0' is connected to device '...'
      new: Camera 'FaceTime HD Camera' can be specified by label as 'webcam0' or by id as '...'
    """
    cameras = []
    for line in listing.splitlines():
        match = re.search(r"by label as ['\"](webcam\d+)['\"]", line) or re.search(r"Camera ['\"](webcam\d+)['\"]", line)
        if match:
            cameras.append(match.group(1))
    return list(dict.fromkeys(cameras))


def select_camera(requested: str, listing: str) -> str:
    if requested in ("emulated", "none"):
        return requested
    cameras = listed_cameras(listing)
    if requested == "auto":
        if cameras:
            return cameras[0]
        raise ValueError("PCカメラが見つかりません。カメラを接続してください。UIだけの確認は ROKID_EMULATOR_CAMERA=emulated で起動できます。")
    if not re.fullmatch(r"webcam\d+", requested):
        raise ValueError("ROKID_EMULATOR_CAMERA は auto / webcam0 / webcam1 / emulated / none を指定してください。")
    if requested not in cameras:
        raise ValueError(f"指定したPCカメラ {requested} が見つかりません。emulator -webcam-list で確認してください。")
    return requested


def read_config(path: Path) -> dict[str, str]:
    return dict(tuple(part.strip() for part in line.split("=", 1)) for line in path.read_text().splitlines() if "=" in line)


def configure_media(path: Path, camera: str, audio: bool) -> None:
    updates = {
        "hw.camera.back": camera,
        "hw.camera.front": "none",
        "hw.audioInput": "yes" if audio else "no",
        "hw.audioOutput": "yes" if audio else "no",
    }
    lines = path.read_text().splitlines()
    result = []
    written = set()
    for line in lines:
        key = line.split("=", 1)[0].strip()
        if "=" in line and key in updates:
            if key not in written:
                result.append(f"{key}={updates[key]}")
                written.add(key)
        else:
            result.append(line)
    result.extend(f"{key}={value}" for key, value in updates.items() if key not in written)
    content = "\n".join(result) + "\n"
    if content != path.read_text():
        path.write_text(content)


def option(arguments: list[str], name: str) -> str | None:
    if name in arguments:
        index = arguments.index(name) + 1
        if index < len(arguments):
            return arguments[index]
    return None


def media_matches(config: dict[str, str], arguments: list[str], camera: str, audio: bool) -> bool:
    back = option(arguments, "-camera-back") or config.get("hw.camera.back")
    front = option(arguments, "-camera-front") or config.get("hw.camera.front")
    enabled = not any(flag in arguments for flag in ("-no-audio", "-noaudio")) and option(arguments, "-audio") != "none"
    enabled = enabled and config.get("hw.audioInput") in ("yes", "true")
    return back == camera and front == "none" and enabled == audio


def running_arguments(name: str) -> list[str] | None:
    output = subprocess.run(["ps", "-axo", "command="], capture_output=True, text=True, check=True).stdout
    for line in output.splitlines():
        if "qemu-system" not in line:
            continue
        try:
            arguments = shlex.split(line)
        except ValueError:
            continue
        if not arguments or "qemu-system" not in Path(arguments[0]).name:
            continue
        if option(arguments, "-avd") == name or f"@{name}" in arguments:
            return arguments
    return None


def launch_arguments(emulator: str, name: str, gpu: str, camera: str, audio: bool) -> list[str]:
    arguments = [emulator, "-avd", name, "-gpu", gpu, "-no-snapshot", "-no-boot-anim", "-camera-back", camera, "-camera-front", "none"]
    arguments.append("-allow-host-audio" if audio else "-no-audio")
    return arguments


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("camera", "running", "needs-restart", "configure", "launch"))
    parser.add_argument("--emulator", default="emulator")
    parser.add_argument("--requested", default="auto")
    parser.add_argument("--name", default="Rokid_Test")
    parser.add_argument("--config", type=Path)
    parser.add_argument("--camera", default="webcam0")
    parser.add_argument("--audio", choices=("0", "1"), default="1")
    parser.add_argument("--gpu", default="software")
    parser.add_argument("--log", type=Path, default=Path(".local/emulator/emulator.log"))
    args = parser.parse_args()
    audio = args.audio == "1"
    try:
        if args.command == "camera":
            listing = ""
            if args.requested not in ("emulated", "none"):
                listing = subprocess.run([args.emulator, "-webcam-list"], capture_output=True, text=True, check=True, timeout=20).stdout
            print(select_camera(args.requested, listing))
        elif args.command == "running":
            print("yes" if running_arguments(args.name) is not None else "no")
        elif args.command in ("needs-restart", "configure") and args.config is None:
            raise ValueError("--config にAVDのconfig.iniを指定してください。")
        elif args.command == "needs-restart":
            arguments = running_arguments(args.name)
            print("no" if arguments is not None and media_matches(read_config(args.config), arguments, args.camera, audio) else "yes")
        elif args.command == "configure":
            configure_media(args.config, args.camera, audio)
        else:
            args.log.parent.mkdir(parents=True, exist_ok=True)
            with args.log.open("w") as log:
                process = subprocess.Popen(launch_arguments(args.emulator, args.name, args.gpu, args.camera, audio), stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            print(process.pid)
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
