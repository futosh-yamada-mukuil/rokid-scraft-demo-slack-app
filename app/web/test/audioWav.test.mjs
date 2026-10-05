import assert from "node:assert/strict";
import { test } from "node:test";
import { pcm16Wav, recordingToWav } from "../src/audioWav.ts";

test("browser stereo samples become mono PCM16 WAV with clamping and the actual rate", () => {
  const audio = Buffer.from(
    pcm16Wav(
      [
        new Float32Array([1, -1, 2, Number.NaN]),
        new Float32Array([1, 0, 2, 0]),
      ],
      16000,
    ),
  );
  assert.equal(audio.toString("ascii", 0, 4), "RIFF");
  assert.equal(audio.readUInt32LE(4), audio.length - 8);
  assert.equal(audio.readUInt16LE(22), 1);
  assert.equal(audio.readUInt32LE(24), 16000);
  assert.equal(audio.readUInt16LE(34), 16);
  assert.deepEqual(
    [0, 1, 2, 3].map((index) => audio.readInt16LE(44 + index * 2)),
    [32767, -16384, 32767, 0],
  );
});

test("empty, inconsistent or overlong recordings are rejected locally", () => {
  assert.throws(() => pcm16Wav([], 16000), /空/);
  assert.throws(
    () => pcm16Wav([new Float32Array(2), new Float32Array(1)], 16000),
    /不正/,
  );
  assert.throws(() => pcm16Wav([new Float32Array(1)], 0), /サンプルレート/);
  assert.throws(
    () => pcm16Wav([new Float32Array(16000 * 60 + 1)], 16000),
    /60秒/,
  );
});

test("browser decoder is asked to resample to 16k before WAV export", async () => {
  const previous = globalThis.OfflineAudioContext;
  globalThis.OfflineAudioContext = class {
    constructor(channels, frames, rate) {
      assert.deepEqual([channels, frames, rate], [1, 1, 16000]);
    }
    async decodeAudioData(bytes) {
      assert.equal(Buffer.from(bytes).toString(), "encoded-audio");
      return {
        numberOfChannels: 1,
        sampleRate: 16000,
        getChannelData: () => new Float32Array([0.25]),
      };
    }
  };
  try {
    const wav = await recordingToWav(
      new Blob(["encoded-audio"], { type: "audio/mp4" }),
    );
    assert.equal(wav.type, "audio/wav");
    assert.equal(Buffer.from(await wav.arrayBuffer()).readUInt32LE(24), 16000);
  } finally {
    globalThis.OfflineAudioContext = previous;
  }
});
