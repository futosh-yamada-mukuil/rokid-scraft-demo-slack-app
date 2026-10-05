import assert from "node:assert/strict";
import { test } from "node:test";
import type { DemoLanguage } from "shared";
import {
  createLanguageService,
  demoLanguage,
  recognitionFormat,
} from "../src/language.ts";

function wav(rate = 16000): Buffer {
  const result = Buffer.alloc(48);
  result.write("RIFF");
  result.writeUInt32LE(40, 4);
  result.write("WAVEfmt ", 8);
  result.writeUInt32LE(16, 16);
  result.writeUInt16LE(1, 20);
  result.writeUInt16LE(1, 22);
  result.writeUInt32LE(rate, 24);
  result.writeUInt32LE(rate * 2, 28);
  result.writeUInt16LE(2, 32);
  result.writeUInt16LE(16, 34);
  result.write("data", 36);
  result.writeUInt32LE(4, 40);
  result.writeInt16LE(1, 44);
  result.writeInt16LE(2, 46);
  return result;
}

test("four language settings retain Chinese and Korean and migrate retired Dutch", () => {
  for (const [input, expected] of [
    ["ja-JP", "ja"],
    ["en-US", "en"],
    ["zh-CN", "zh-CN"],
    ["zh_hans", "zh-CN"],
    ["ko-KR", "ko"],
    ["nl", "ja"],
    ["zh-TW", "ja"],
    [undefined, "ja"],
  ]) {
    assert.equal(demoLanguage(input), expected);
  }
});

test("PC text auto-translates to every target with the shared key and preserves literal tags", async () => {
  const requests: Record<string, unknown>[] = [];
  const service = createLanguageService({
    apiKey: "test-shared-key",
    fetcher: async (url, init) => {
      assert.equal(
        String(url),
        "https://translation.googleapis.com/language/translate/v2",
      );
      assert.equal(
        new Headers(init?.headers).get("x-goog-api-key"),
        "test-shared-key",
      );
      assert.ok(init?.signal);
      requests.push(JSON.parse(String(init?.body)));
      return Response.json({
        data: {
          translations: [
            { translatedText: "line\n&lt;b&gt; &amp; &#39;quote&#39;" },
          ],
        },
      });
    },
  });
  for (const target of ["ja", "zh-CN", "en", "ko"] as DemoLanguage[]) {
    assert.equal(
      await service.translateJapanese("请检查照片", target),
      "line\n<b> & 'quote'",
    );
    assert.deepEqual(requests.at(-1), {
      q: "请检查照片",
      target,
      format: "text",
    });
  }
});

test("WAV and browser Opus speech use AUTO STT then translate to the selected language", async () => {
  const requests: Array<{ url: string; body: Record<string, unknown> }> = [];
  const service = createLanguageService({
    apiKey: "dummy",
    fetcher: async (url, init) => {
      const address = String(url);
      requests.push({ url: address, body: JSON.parse(String(init?.body)) });
      return address.includes("speech:recognize")
        ? Response.json({
            results: [
              { alternatives: [{ transcript: "現場写真を" }] },
              { alternatives: [{ transcript: "確認してください" }] },
            ],
          })
        : Response.json({
            data: { translations: [{ translatedText: "Please check." }] },
          });
    },
  });
  for (const [audio, mime, encoding, rate] of [
    [wav(16000), "audio/wav", "LINEAR16", 16000],
    [Buffer.from("browser-opus"), "audio/webm;codecs=opus", "WEBM_OPUS", 48000],
  ] as const) {
    assert.deepEqual(
      await service.translateSpeech({ audio, audioMime: mime, target: "en" }),
      {
        transcript: "現場写真を 確認してください",
        translated: "Please check.",
      },
    );
    const stt = requests.at(-2)?.body as {
      config: Record<string, unknown>;
      audio: { content: string };
    };
    assert.deepEqual(stt.config, {
      encoding,
      sampleRateHertz: rate,
      languageCode: "ja-JP",
      alternativeLanguageCodes: ["en-US", "cmn-Hans-CN", "ko-KR"],
      model: "command_and_search",
      enableAutomaticPunctuation: true,
    });
    assert.equal(Buffer.from(stt.audio.content, "base64").equals(audio), true);
    assert.deepEqual(requests.at(-1)?.body, {
      q: "現場写真を 確認してください",
      target: "en",
      format: "text",
    });
  }
  const report = await service.analyzeReport({
    text: "",
    audio: wav(),
    audioMime: "audio/wav",
    language: "ko",
  });
  assert.equal(report.language, "ko");
  assert.equal(requests.at(-1)?.body.target, "ja");
});

test("bad WAV and unsupported containers fail locally without API calls", async () => {
  let calls = 0;
  const service = createLanguageService({
    apiKey: "dummy",
    fetcher: async () => {
      calls++;
      return Response.json({});
    },
  });
  for (const [audio, mime] of [
    [Buffer.from("broken"), "audio/wav"],
    [wav().subarray(0, 47), "audio/wav"],
    [wav(), "video/mp4"],
  ] as const) {
    await assert.rejects(
      service.translateSpeech({ audio, audioMime: mime, target: "en" }),
    );
  }
  const stereo = wav();
  stereo.writeUInt16LE(2, 22);
  assert.throws(() => recognitionFormat(stereo, "audio/wav"), /モノラル/);
  assert.equal(calls, 0);
});

test("empty, malformed and HTTP errors fail without leaking credentials or server details", async () => {
  for (const response of [
    Response.json({ data: { translations: [] } }),
    Response.json({ results: [] }),
    new Response("SECRET_SERVER_DETAIL", { status: 403 }),
  ]) {
    const service = createLanguageService({
      apiKey: "SECRET_KEY",
      fetcher: async () => response,
    });
    await assert.rejects(service.translateJapanese("hello", "ko"), (error) => {
      assert.doesNotMatch(String(error), /SECRET/);
      return true;
    });
  }
  await assert.rejects(
    createLanguageService({ apiKey: "" }).translateJapanese("hello", "ko"),
    /GOOGLE_STT_TTS_TRANSLATE_API_KEY/,
  );
});
