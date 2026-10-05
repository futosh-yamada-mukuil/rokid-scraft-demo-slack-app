import type { DemoLanguage } from "shared";

export type ReportAnalysis = {
  transcript: string;
  japanese: string;
  language: DemoLanguage;
};

export type LanguageService = {
  readonly configured: boolean;
  analyzeReport(input: {
    text: string;
    audio?: Buffer;
    audioMime?: string;
    language: DemoLanguage;
  }): Promise<ReportAnalysis>;
  translateJapanese(text: string, target: DemoLanguage): Promise<string>;
  translateSpeech(input: {
    audio: Buffer;
    audioMime: string;
    target: DemoLanguage;
  }): Promise<{ transcript: string; translated: string }>;
};

export function demoLanguage(value: unknown): DemoLanguage {
  const code = String(value ?? "")
    .trim()
    .toLowerCase()
    .replaceAll("_", "-");
  if (
    code === "zh" ||
    code === "zh-cn" ||
    code === "zh-hans" ||
    code === "cmn-hans-cn"
  )
    return "zh-CN";
  const base = code.split("-")[0];
  return base === "en" || base === "ko" ? base : "ja";
}

const MISSING_KEY = "GOOGLE_STT_TTS_TRANSLATE_API_KEY が未設定です";

/** Google REST uses one shared key; no customer content or raw API errors are logged. */
export function createLanguageService({
  apiKey,
  sttEndpoint = "https://speech.googleapis.com/v1/speech:recognize",
  translationEndpoint = "https://translation.googleapis.com/language/translate/v2",
  fetcher = fetch,
}: {
  apiKey: string;
  sttEndpoint?: string;
  translationEndpoint?: string;
  fetcher?: typeof fetch;
}): LanguageService {
  async function request(
    endpoint: string,
    body: unknown,
  ): Promise<Record<string, unknown>> {
    if (!apiKey) throw new Error(MISSING_KEY);
    const response = await fetcher(endpoint, {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": apiKey },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(25_000),
    });
    if (!response.ok)
      throw new Error(
        `Google API HTTP ${response.status}（API有効化・権限・課金設定を確認してください）`,
      );
    return (await response.json()) as Record<string, unknown>;
  }

  async function translate(
    text: string,
    target: DemoLanguage,
  ): Promise<string> {
    const source = text.trim();
    if (!source) throw new Error("翻訳本文が空です");
    const payload = await request(translationEndpoint, {
      q: source,
      target,
      format: "text",
    });
    const data = payload.data as
      | { translations?: Array<{ translatedText?: unknown }> }
      | undefined;
    const value = data?.translations?.[0]?.translatedText;
    if (typeof value !== "string" || !value.trim())
      throw new Error("翻訳結果が空です");
    // format=text can still encode entities; preserve literal tags and line breaks.
    return value
      .replace(
        /&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos);/gi,
        (entity, name: string) => {
          const named: Record<string, string> = {
            amp: "&",
            lt: "<",
            gt: ">",
            quot: '"',
            apos: "'",
          };
          if (!name.startsWith("#")) return named[name.toLowerCase()] ?? entity;
          const code = name.toLowerCase().startsWith("#x")
            ? Number.parseInt(name.slice(2), 16)
            : Number(name.slice(1));
          return code > 0 &&
            code <= 0x10ffff &&
            !(code >= 0xd800 && code <= 0xdfff)
            ? String.fromCodePoint(code)
            : entity;
        },
      )
      .trim();
  }

  async function recognize(audio: Buffer, mime: string): Promise<string> {
    if (!audio.length) throw new Error("認識する音声が空です");
    const config = {
      ...recognitionFormat(audio, mime),
      languageCode: "ja-JP",
      alternativeLanguageCodes: ["en-US", "cmn-Hans-CN", "ko-KR"],
      model: "command_and_search",
      enableAutomaticPunctuation: true,
    };
    const payload = await request(sttEndpoint, {
      config,
      audio: { content: audio.toString("base64") },
    });
    const results = payload.results as
      | Array<{ alternatives?: Array<{ transcript?: unknown }> }>
      | undefined;
    const transcript = (results ?? [])
      .map((result) => result.alternatives?.[0]?.transcript)
      .filter((value): value is string => typeof value === "string")
      .join(" ")
      .trim();
    if (!transcript) throw new Error("音声の認識結果が空です");
    return transcript;
  }

  return {
    configured: Boolean(apiKey),
    async analyzeReport({ text, audio, audioMime, language }) {
      const transcript = audio?.length
        ? await recognize(audio, audioMime || "audio/wav")
        : text.trim();
      return {
        transcript,
        japanese: transcript ? await translate(transcript, "ja") : "",
        language,
      };
    },
    // Source is deliberately omitted: PC text also supports automatic input-language detection.
    translateJapanese: translate,
    async translateSpeech({ audio, audioMime, target }) {
      const transcript = await recognize(audio, audioMime);
      return { transcript, translated: await translate(transcript, target) };
    },
  };
}

/** Browser MediaRecorder uses Opus; Rokid records PCM16 WAV with the actual rate in its header. */
export function recognitionFormat(
  audio: Buffer,
  mime: string,
): Record<string, string | number> {
  const type = mime.toLowerCase().split(";")[0];
  if (type === "audio/webm" || type === "video/webm")
    return { encoding: "WEBM_OPUS", sampleRateHertz: 48000 };
  if (type === "audio/ogg")
    return { encoding: "OGG_OPUS", sampleRateHertz: 48000 };
  if (type === "audio/flac") return { encoding: "FLAC" };
  if (type !== "audio/wav" && type !== "audio/x-wav" && type !== "audio/wave")
    throw new Error(
      "音声形式が非対応です。WAVまたはOpus音声を使用してください",
    );
  if (
    audio.length < 44 ||
    audio.toString("ascii", 0, 4) !== "RIFF" ||
    audio.toString("ascii", 8, 12) !== "WAVE"
  )
    throw new Error("WAVヘッダが不正です");
  let rate = 0;
  let hasData = false;
  for (let offset = 12; offset + 8 <= audio.length; ) {
    const id = audio.toString("ascii", offset, offset + 4);
    const size = audio.readUInt32LE(offset + 4);
    const start = offset + 8;
    if (start + size > audio.length)
      throw new Error("WAVデータが途中で切れています");
    if (id === "fmt ") {
      if (
        size < 16 ||
        audio.readUInt16LE(start) !== 1 ||
        audio.readUInt16LE(start + 2) !== 1 ||
        audio.readUInt16LE(start + 14) !== 16
      )
        throw new Error("WAVはPCM16・モノラルにしてください");
      rate = audio.readUInt32LE(start + 4);
    }
    if (id === "data" && size > 0) hasData = true;
    offset = start + size + (size % 2);
  }
  if (!hasData || rate < 8000 || rate > 48000)
    throw new Error("WAVの音声データ・サンプルレートが不正です");
  return { encoding: "LINEAR16", sampleRateHertz: rate };
}

export const noLanguageService: LanguageService = {
  configured: false,
  async analyzeReport({ text, language }) {
    const source = text.trim();
    return { transcript: source, japanese: source, language };
  },
  async translateJapanese(text, target) {
    if (target === "ja") return text.trim();
    throw new Error(MISSING_KEY);
  },
  async translateSpeech() {
    throw new Error(MISSING_KEY);
  },
};
