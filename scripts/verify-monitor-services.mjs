import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { mkdtemp } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { tmpdir } from 'node:os';

// Real local HTTP and Google APIs, isolated storage, and no configured Microsoft client.
const project = resolve(process.argv[2] ?? '.');
const envFile = resolve(process.argv[3] ?? '.env');
const samplesDir = resolve(process.argv[4] ?? '.local/google-verification');
const env = Object.fromEntries(readFileSync(envFile, 'utf8').split(/\r?\n/)
  .filter((line) => line.includes('=') && !line.trim().startsWith('#'))
  .map((line) => { const index = line.indexOf('='); return [line.slice(0, index).trim(), line.slice(index + 1).trim().replace(/^(['"])(.*)\1$/, '$2')]; }));
const key = env.GOOGLE_STT_TTS_TRANSLATE_API_KEY;
assert.ok(key, 'Common Google key must be configured');
const { createApp } = await import(pathToFileURL(join(project, 'app/api/src/app.ts')));
const { createLanguageService } = await import(pathToFileURL(join(project, 'app/api/src/language.ts')));
const temporary = await mkdtemp(join(tmpdir(), 'rokid-monitor-verification-'));
const app = await createApp({
  uploadDir: join(temporary, 'uploads'), trashDir: join(temporary, 'trash'),
  replyDir: join(temporary, 'replies'), tokenPath: join(temporary, 'token'),
  serveStatic: false, language: createLanguageService({ apiKey: key }),
});
const base = await app.listen({ port: 0, host: '127.0.0.1' });
const token = (await (await fetch(`${base}/api/token`)).json()).token;
const samples = [
  ['ja', 'ja', '現場の写真を確認してください。'],
  ['en', 'en', 'Please check the photos from the site.'],
  ['zh-CN', 'zh-CN', '请检查现场的照片。'],
  ['ko', 'ko', '현장 사진을 확인해 주세요.'],
];
const normalize = (value) => value.replace(/[^\p{L}\p{N}]/gu, '').toLowerCase();
const report = { checkedAt: new Date().toISOString(), httpBase: base, storage: temporary, cases: [] };
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4X8AAAAASUVORK5CYII=', 'base64');
try {
  const teamsStatus = await (await fetch(`${base}/api/teams/status`)).json();
  assert.equal(teamsStatus.configured, false, 'Teams must remain unconfigured');
  for (const [fileName, language, expected] of samples) {
    const wav = readFileSync(join(samplesDir, `${fileName}.wav`));
    const form = new FormData();
    form.append('media', new Blob([png], { type: 'image/png' }), 'dummy.png');
    form.append('audio', new Blob([wav], { type: 'audio/wav' }), 'dummy.wav');
    form.append('language', language);
    const uploaded = await fetch(`${base}/upload`, { method: 'POST', headers: { 'x-auth-token': token, 'x-device': `dummy-${language}` }, body: form });
    assert.equal(uploaded.status, 200, `${language}: upload must succeed`);
    const entry = await uploaded.json();
    assert.equal(normalize(entry.comment), normalize(expected), `${language}: recognized text`);
    assert.equal(entry.language, language, `${language}: reply language must be preserved`);
    assert.ok(entry.translatedComment?.trim());
    const textReply = await fetch(`${base}/api/replies`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ entryId: entry.id, text: '現場の写真を確認してください。' }) });
    assert.equal(textReply.status, 200, `${language}: text reply`);
    const text = await textReply.json();
    assert.equal(text.language, language);
    assert.ok(text.translatedText?.trim());
    const speechForm = new FormData();
    speechForm.append('entryId', entry.id);
    speechForm.append('audio', new Blob([wav], { type: 'audio/wav' }), 'reply.wav');
    const speechReply = await fetch(`${base}/api/replies`, { method: 'POST', body: speechForm });
    assert.equal(speechReply.status, 200, `${language}: speech reply`);
    const speech = await speechReply.json();
    assert.equal(normalize(speech.originalText), normalize(expected));
    assert.equal(speech.language, language);
    assert.ok(speech.translatedText?.trim());
    const received = await (await fetch(`${base}/api/replies?device=dummy-${encodeURIComponent(language)}`)).json();
    assert.equal(received.length, 2);
    const row = { language, transcript: entry.comment, japanese: entry.translatedComment, textReply: text.translatedText, speechReply: speech.translatedText, repliesReceived: received.length, pass: true };
    report.cases.push(row);
    console.log(JSON.stringify(row));
  }
  report.pass = true;
} finally {
  await app.close();
  mkdirSync(samplesDir, { recursive: true });
  writeFileSync(join(samplesDir, 'monitor-report.json'), JSON.stringify(report, null, 2));
}
console.log('OK (local photo/audio upload -> recognition -> Japanese monitor text -> translated text/audio reply, four languages)');
