#!/usr/bin/env bash
set -euo pipefail

# Downloads are pinned, checked, and deliberately excluded from Git.
project_dir="$(cd "$(dirname "$0")/.." && pwd)"
asset_dir="$project_dir/app/rokid/src/main/assets/offline-stt"
lib_dir="$project_dir/app/rokid/libs"
mkdir -p "$asset_dir" "$lib_dir"

fetch() {
  local url="$1" target="$2" expected="$3"
  if [[ -f "$target" ]] && [[ "$(shasum -a 256 "$target" | cut -d ' ' -f 1)" == "$expected" ]]; then
    return
  fi
  curl --fail --location --retry 3 "$url" --output "$target.download"
  local actual
  actual="$(shasum -a 256 "$target.download" | cut -d ' ' -f 1)"
  if [[ "$actual" != "$expected" ]]; then
    echo "Checksum mismatch: $target" >&2
    exit 1
  fi
  mv "$target.download" "$target"
}

model_archive="$lib_dir/sensevoice-int8-2024-07-17.tar.bz2"
model_name="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$model_name.tar.bz2" "$model_archive" 7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e
extract_model() {
  local member="$1" target="$2" expected="$3"
  if [[ -f "$target" ]] && [[ "$(shasum -a 256 "$target" | cut -d ' ' -f 1)" == "$expected" ]]; then
    return
  fi
  tar -xjOf "$model_archive" "$model_name/$member" > "$target.download"
  if [[ "$(shasum -a 256 "$target.download" | cut -d ' ' -f 1)" != "$expected" ]]; then
    echo "Checksum mismatch: $target" >&2
    exit 1
  fi
  mv "$target.download" "$target"
}
extract_model model.int8.onnx "$asset_dir/sensevoice-model.int8.onnx" c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51
extract_model tokens.txt "$asset_dir/sensevoice-tokens.txt" f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc
fetch "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-1.13.3.aar" "$lib_dir/sherpa-onnx-1.13.3.aar" 243ad797a3b6e75ebbeaf7a2ab4aec0777e7d71b730685abb762a120940b07b6
echo "Offline STT model and runtime verified."
