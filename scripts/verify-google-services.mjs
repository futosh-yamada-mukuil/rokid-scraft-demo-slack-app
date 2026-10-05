import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

// No production messages are sent. This exercises the same REST payloads as the app.
const envPath = resolve(process.argv[2] ?? '.env');
const outDir = resolve(process.argv[3] ?? '.local/google-verification');
const env = Object.fromEntries(readFileSync(envPath, 'utf8').split(/\r?\n/)
  .filter((line) => line.trim() && !line.trim().startsWith('#') && line.includes('='))
  .map((line) => {
    const separator = line.indexOf('=');
    return [line.slice(0, separator).trim(), line.slice(separator + 1).trim().replace(/^(['"])(.*)\1$/, '$2')];
  }));
const key = env.GOOGLE_STT_TTS_TRANSLATE_API_KEY;
if (!key) throw new Error('GOOGLE_STT_TTS_TRANSLATE_API_KEY is not configured');
mkdirSync(outDir, { recursive: true });
const samples = [
  { code: 'ja', stt: 'ja-JP', voice: 'Kyoko', tts: ['ja-JP', 'ja-JP-Neural2-B'], text: '現場の写真を確認してください。' },
  { code: 'en', stt: 'en-US', voice: 'Samantha', tts: ['en-US', 'en-US-Neural2-F'], text: 'Please check the photos from the site.' },
  { code: 'zh-CN', stt: 'cmn-Hans-CN', voice: 'Tingting', tts: ['cmn-CN', 'cmn-CN-Standard-A'], text: '请检查现场的照片。' },
  { code: 'ko', stt: 'ko-KR', voice: 'Yuna', tts: ['ko-KR', 'ko-KR-Standard-A'], text: '현장 사진을 확인해 주세요.' },
];
const report = { checkedAt: new Date().toISOString(), stt: [], translation: [], tts: [] };
async function post(service, url, body) {
  const started = Date.now();
  try {
    const response = await fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json', 'x-goog-api-key': key },
      body: JSON.stringify(body), signal: AbortSignal.timeout(30000),
    });
    const data = await response.json();
    return { http: response.status, elapsedMs: Date.now() - started, data,
      error: data.error ? { status: data.error.status, reasons: data.error.details?.map((detail) => detail.reason).filter(Boolean) } : undefined };
  } catch (error) {
    return { http: 0, elapsedMs: Date.now() - started, error: { status: error.name } };
  }
}
function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.status !== 0) throw new Error(`${command} failed (${result.status})`);
}
for (const sample of samples) {
  const aiff = resolve(outDir, `${sample.code}.aiff`);
  const wav = resolve(outDir, `${sample.code}.wav`);
  run('/usr/bin/say', ['-v', sample.voice, '-o', aiff, sample.text]);
  run('/usr/bin/afconvert', ['-f', 'WAVE', '-d', 'LEI16@16000', '-c', '1', aiff, wav]);
  const result = await post('STT', 'https://speech.googleapis.com/v1/speech:recognize', {
    config: { encoding: 'LINEAR16', sampleRateHertz: 16000, languageCode: 'ja-JP',
      alternativeLanguageCodes: ['en-US', 'cmn-Hans-CN', 'ko-KR'], model: 'command_and_search', enableAutomaticPunctuation: true },
    audio: { content: readFileSync(wav).toString('base64') },
  });
  const transcript = result.data?.results?.map((item) => item.alternatives?.[0]?.transcript ?? '').join(' ');
  const row = { language: sample.code, expected: sample.text, transcript, http: result.http, elapsedMs: result.elapsedMs, error: result.error,
    pass: result.http === 200 && transcript?.replace(/[^\p{L}\p{N}]/gu, '').toLowerCase()
      === sample.text.replace(/[^\p{L}\p{N}]/gu, '').toLowerCase() };
  report.stt.push(row);
  console.log(JSON.stringify({ service: 'STT', ...row }));
}
for (const source of samples) {
  for (const target of samples) {
    const result = await post('Translate', 'https://translation.googleapis.com/language/translate/v2',
      { q: source.text, target: target.code, format: 'text' });
    const translation = result.data?.data?.translations?.[0];
    const row = { source: source.code, target: target.code, http: result.http, elapsedMs: result.elapsedMs,
      text: translation?.translatedText, detected: translation?.detectedSourceLanguage, error: result.error,
      pass: result.http === 200 && Boolean(translation?.translatedText?.trim()) };
    report.translation.push(row);
    console.log(JSON.stringify({ service: 'Translate', ...row }));
    // One permission failure is enough; avoid repeating an unavailable service.
    if (result.http === 401 || result.http === 403) break;
  }
  if (report.translation.at(-1)?.http === 401 || report.translation.at(-1)?.http === 403) break;
}
for (const sample of samples) {
  const result = await post('TTS', 'https://texttospeech.googleapis.com/v1/text:synthesize', {
    input: { text: sample.text }, voice: { languageCode: sample.tts[0], name: sample.tts[1] },
    audioConfig: { audioEncoding: 'MP3', speakingRate: 1.2 },
  });
  const mp3 = Buffer.from(result.data?.audioContent ?? '', 'base64');
  if (mp3.length) writeFileSync(resolve(outDir, `${sample.code}.mp3`), mp3);
  const row = { language: sample.code, http: result.http, elapsedMs: result.elapsedMs, audioBytes: mp3.length,
    error: result.error, pass: result.http === 200 && mp3.length > 0 };
  report.tts.push(row);
  console.log(JSON.stringify({ service: 'TTS', ...row }));
}
report.pass = report.stt.every((row) => row.pass) && report.translation.length === 16
  && report.translation.every((row) => row.pass) && report.tts.every((row) => row.pass);
writeFileSync(resolve(outDir, 'report.json'), JSON.stringify(report, null, 2));
console.log(JSON.stringify({ allPassed: report.pass, report: resolve(outDir, 'report.json') }));
process.exitCode = report.pass ? 0 : 1;
