/** Normalize browser recordings (including Safari AAC) to the same mono PCM16 WAV as Rokid. */
export async function recordingToWav(recording: Blob): Promise<Blob> {
  const decoder = new OfflineAudioContext(1, 1, 16000);
  const audio = await decoder.decodeAudioData(await recording.arrayBuffer());
  const channels = Array.from({ length: audio.numberOfChannels }, (_, index) =>
    audio.getChannelData(index),
  );
  return new Blob([pcm16Wav(channels, audio.sampleRate)], {
    type: "audio/wav",
  });
}

export function pcm16Wav(
  channels: readonly Float32Array[],
  sampleRate: number,
): ArrayBuffer {
  const length = channels[0]?.length ?? 0;
  if (!length || channels.some((channel) => channel.length !== length))
    throw new Error("録音データが空または不正です");
  if (!Number.isInteger(sampleRate) || sampleRate < 8000 || sampleRate > 48000)
    throw new Error("録音サンプルレートが不正です");
  if (length > sampleRate * 60)
    throw new Error("音声返信は60秒以内にしてください");
  const buffer = new ArrayBuffer(44 + length * 2);
  const view = new DataView(buffer);
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i++)
      view.setUint8(offset + i, text.charCodeAt(i));
  };
  ascii(0, "RIFF");
  view.setUint32(4, 36 + length * 2, true);
  ascii(8, "WAVEfmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  ascii(36, "data");
  view.setUint32(40, length * 2, true);
  for (let i = 0; i < length; i++) {
    const mean =
      channels.reduce(
        (sum, channel) => sum + (Number.isFinite(channel[i]) ? channel[i] : 0),
        0,
      ) / channels.length;
    const sample = Math.max(-1, Math.min(1, mean));
    view.setInt16(
      44 + i * 2,
      Math.round(sample * (sample < 0 ? 32768 : 32767)),
      true,
    );
  }
  return buffer;
}
