import type {
  RecitationAsrEndpoint,
  RecitationAsrGateway,
  RecitationAsrPort,
} from "../interfaces/recitation-audio.js";

const MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

function record(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

async function readJson(response: Response): Promise<Record<string, unknown>> {
  const length = response.headers.get("content-length");
  if (length && Number(length) > MAX_RESPONSE_BYTES) throw new Error("ASR response is too large");
  if (!response.body) throw new Error("ASR response is empty");
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new Error("ASR response is too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  const value: unknown = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes));
  return record(value);
}

export class DashScopeRecitationAsrMapper implements RecitationAsrPort {
  constructor(private readonly gateway: RecitationAsrGateway) {}

  async submit(audioUrl: string): Promise<{ providerId: number; model: string; taskId: string }> {
    let lastError: unknown;
    for (const endpoint of await this.gateway.candidates()) {
      try {
        const response = await fetch(this.apiUrl(endpoint, "/services/audio/asr/transcription"), {
          method: "POST",
          headers: {
            authorization: `Bearer ${endpoint.apiKey}`,
            "content-type": "application/json",
            "x-dashscope-async": "enable",
          },
          body: JSON.stringify({
            model: endpoint.model,
            input: { file_urls: [audioUrl] },
            parameters: { channel_id: [0], language_hints: ["zh"] },
          }),
          redirect: "manual",
          signal: AbortSignal.timeout(30_000),
        });
        if (!response.ok) throw new Error(`ASR submit HTTP ${response.status}`);
        const body = await readJson(response);
        const output = record(body.output);
        if (typeof output.task_id !== "string" || !output.task_id.trim()) throw new Error("ASR task id is missing");
        return { providerId: endpoint.providerId, model: endpoint.model, taskId: output.task_id };
      } catch (error) {
        lastError = error;
      }
    }
    throw lastError instanceof Error ? lastError : new Error("Recitation ASR model is not configured");
  }

  async poll(providerId: number, taskId: string) {
    const endpoint = (await this.gateway.candidates()).find((item) => item.providerId === providerId);
    if (!endpoint) throw new Error("The original ASR provider is no longer enabled");
    const response = await fetch(this.apiUrl(endpoint, `/tasks/${encodeURIComponent(taskId)}`), {
      headers: { authorization: `Bearer ${endpoint.apiKey}`, accept: "application/json" },
      redirect: "manual",
      signal: AbortSignal.timeout(30_000),
    });
    if (!response.ok) throw new Error(`ASR poll HTTP ${response.status}`);
    const body = await readJson(response);
    const output = record(body.output);
    const status = output.task_status;
    if (status === "PENDING" || status === "RUNNING") return { state: "PROCESSING" as const };
    if (status !== "SUCCEEDED") return { state: "FAILED" as const, errorMessage: "语音识别任务执行失败" };
    const results = Array.isArray(output.results) ? output.results : [];
    const successful = results.map(record).find((item) => item.subtask_status === "SUCCEEDED");
    if (!successful || typeof successful.transcription_url !== "string") {
      return { state: "FAILED" as const, errorMessage: "语音识别结果不可用" };
    }
    const transcriptUrl = new URL(successful.transcription_url);
    if (transcriptUrl.protocol !== "https:" || !transcriptUrl.hostname.endsWith(".aliyuncs.com")) {
      throw new Error("ASR transcript URL is not trusted");
    }
    const transcriptResponse = await fetch(transcriptUrl, {
      redirect: "manual", signal: AbortSignal.timeout(30_000),
    });
    if (!transcriptResponse.ok) throw new Error("ASR transcript download failed");
    const transcriptData = record(await readJson(transcriptResponse));
    const transcripts = Array.isArray(transcriptData.transcripts) ? transcriptData.transcripts : [];
    let transcript = "";
    const words: Array<{ text: string; beginTime: number; endTime: number }> = [];
    for (const rawChannel of transcripts) {
      const channel = record(rawChannel);
      if (typeof channel.text === "string") transcript += channel.text;
      const sentences = Array.isArray(channel.sentences) ? channel.sentences : [];
      for (const rawSentence of sentences) {
        const sentence = record(rawSentence);
        const sentenceWords = Array.isArray(sentence.words) ? sentence.words : [];
        for (const rawWord of sentenceWords) {
          const word = record(rawWord);
          if (typeof word.text === "string") words.push({
            text: word.text,
            beginTime: Number.isSafeInteger(word.begin_time) ? word.begin_time as number : 0,
            endTime: Number.isSafeInteger(word.end_time) ? word.end_time as number : 0,
          });
        }
      }
    }
    return transcript.trim()
      ? { state: "SUCCEEDED" as const, transcript, words }
      : { state: "FAILED" as const, errorMessage: "录音中未识别到可用中文内容" };
  }

  private apiUrl(endpoint: RecitationAsrEndpoint, path: string): URL {
    const base = new URL(endpoint.baseUrl);
    if (base.protocol !== "https:" && base.hostname !== "127.0.0.1" && base.hostname !== "localhost") {
      throw new Error("Recitation ASR provider must use HTTPS");
    }
    return new URL(endpoint.baseUrl.replace(/\/+$/, "") + path);
  }
}
