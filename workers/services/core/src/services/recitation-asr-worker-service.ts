import type { RecitationAudioStorage, RecitationAsrPort } from "../interfaces/recitation-audio.js";
import type { RecitationAttemptRow, RecitationRepository, RecitationMaterialRow } from "../interfaces/recitation-repository.js";

const MAX_POLL_ATTEMPTS = 180;
const MAX_ALIGN_CODE_POINTS = 12_000;

function normalize(value: string | null | undefined): string {
  return Array.from(value ?? "").filter((character) => /[\p{L}\p{N}]/u.test(character)).join("").toLowerCase();
}

function setMove(moves: Uint8Array, width: number, i: number, j: number, move: number): void {
  const cell = i * width + j;
  const index = Math.floor(cell / 4);
  const shift = (cell % 4) * 2;
  moves[index] = (moves[index] & ~(3 << shift)) | (move << shift);
}

function getMove(moves: Uint8Array, width: number, i: number, j: number): number {
  const cell = i * width + j;
  return (moves[Math.floor(cell / 4)] >> ((cell % 4) * 2)) & 3;
}

function align(expectedText: string, actualText: string) {
  const expected = normalize(expectedText);
  const actual = normalize(actualText);
  const left = Array.from(expected).slice(0, MAX_ALIGN_CODE_POINTS);
  const right = Array.from(actual).slice(0, MAX_ALIGN_CODE_POINTS);
  const width = right.length + 1;
  const cellCount = (left.length + 1) * width;
  const moves = new Uint8Array(Math.ceil(cellCount / 4));
  let previous = new Int32Array(width);
  let current = new Int32Array(width);
  for (let j = 0; j <= right.length; j++) {
    previous[j] = j;
    setMove(moves, width, 0, j, 2);
  }
  for (let i = 1; i <= left.length; i++) {
    current[0] = i;
    setMove(moves, width, i, 0, 1);
    for (let j = 1; j <= right.length; j++) {
      const same = left[i - 1] === right[j - 1];
      const substitution = previous[j - 1] + (same ? 0 : 1);
      const deletion = previous[j] + 1;
      const insertion = current[j - 1] + 1;
      const best = Math.min(substitution, deletion, insertion);
      current[j] = best;
      const move = same && best === previous[j - 1] ? 0 : best === deletion ? 1 : best === insertion ? 2 : 3;
      setMove(moves, width, i, j, move);
    }
    [previous, current] = [current, previous];
  }

  const raw: Array<{ type: string; expected: string; actual: string; expectedIndex: number }> = [];
  let correctCount = 0;
  let missingCount = 0;
  let extraCount = 0;
  let wrongCount = 0;
  let i = left.length;
  let j = right.length;
  while (i > 0 || j > 0) {
    const move = getMove(moves, width, i, j);
    if (move === 0) { correctCount++; i--; j--; }
    else if (move === 1 && i > 0) {
      missingCount++; raw.push({ type: "MISSING", expected: left[i - 1], actual: "", expectedIndex: i - 1 }); i--;
    } else if (move === 2 && j > 0) {
      extraCount++; raw.push({ type: "EXTRA", expected: "", actual: right[j - 1], expectedIndex: i }); j--;
    } else {
      wrongCount++; raw.push({ type: "WRONG", expected: left[i - 1] ?? "", actual: right[j - 1] ?? "", expectedIndex: i - 1 }); i--; j--;
    }
  }
  raw.reverse();
  const grouped: Array<{ type: string; expected: string; actual: string; expectedIndex: number }> = [];
  for (const diff of raw) {
    const last = grouped.at(-1);
    const expectedLength = Array.from(last?.expected ?? "").length;
    const adjacent = last && last.type === diff.type && (diff.type === "EXTRA"
      ? last.expectedIndex === diff.expectedIndex
      : last.expectedIndex + expectedLength === diff.expectedIndex);
    if (adjacent && last) {
      last.expected += diff.expected;
      last.actual += diff.actual;
    } else grouped.push({ ...diff });
  }
  const differences = grouped.map((diff) => {
    const size = Array.from(diff.expected).length;
    const end = Math.min(left.length, diff.expectedIndex + size);
    return {
      ...diff,
      contextBefore: left.slice(Math.max(0, diff.expectedIndex - 8), diff.expectedIndex).join(""),
      contextAfter: left.slice(end, Math.min(left.length, end + 8)).join(""),
      tailOmission: diff.type === "MISSING" && end === left.length,
    };
  });
  const penalty = missingCount + wrongCount + extraCount * 0.5;
  const score = left.length === 0 ? 0 : Math.floor(Math.max(0, 100 * (1 - penalty / left.length)) * 100 + 0.5) / 100;
  return { normalizedExpected: left.join(""), normalizedActual: right.join(""), correctCount,
    missingCount, wrongCount, extraCount, score, differences };
}

function mastery(score: number, now: Date) {
  const date = new Date(now);
  const integer = Math.floor(score);
  const days = integer >= 90 ? 30 : integer >= 75 ? 7 : integer >= 60 ? 3 : integer >= 30 ? 1 : 0;
  const hours = days === 0 ? 12 : 0;
  const level = integer >= 90 ? "MASTERED" : integer >= 75 ? "APPLYING" : integer >= 60 ? "RECALLING"
    : integer >= 30 ? "FAMILIAR" : "NEW";
  date.setUTCDate(date.getUTCDate() + days);
  date.setUTCHours(date.getUTCHours() + hours);
  return { level, nextReviewAt: date.toISOString() };
}

export class RecitationAsrWorkerService {
  constructor(
    private readonly repository: RecitationRepository,
    private readonly storage: RecitationAudioStorage,
    private readonly asr: RecitationAsrPort,
    private readonly enabled: boolean,
    private readonly batchSize = 10,
    private readonly staleClaimSeconds = 120,
  ) {}

  async process(): Promise<void> {
    if (!this.enabled) return;
    const cutoff = new Date(Date.now() - this.staleClaimSeconds * 1000).toISOString();
    await this.repository.recoverStaleAttemptClaims(cutoff);
    await this.submitPending();
    await this.pollProcessing();
  }

  private async submitPending(): Promise<void> {
    const rows = await this.repository.listAttemptsByStatus("PENDING", this.batchSize);
    for (const row of rows) {
      const token = crypto.randomUUID();
      if (!await this.repository.claimAttempt(row.id, "PENDING", "SUBMITTING", token, false)) continue;
      try {
        if (!row.audioUrl) throw new Error("Recitation audio key is missing");
        const audioUrl = row.audioUrl.startsWith("https://") ? row.audioUrl : await this.storage.readUrl(row.audioUrl);
        const submitted = await this.asr.submit(audioUrl);
        if (!await this.repository.markAttemptSubmitted(row.id, token, submitted.providerId, submitted.model, submitted.taskId)) {
          throw new Error("Recitation attempt claim was replaced");
        }
      } catch {
        await this.repository.failClaimedAttempt(row.id, "SUBMITTING", token, "语音识别服务暂时不可用，请稍后重新录音");
      }
    }
  }

  private async pollProcessing(): Promise<void> {
    const rows = await this.repository.listAttemptsByStatus("PROCESSING", this.batchSize);
    for (const row of rows) {
      const token = crypto.randomUUID();
      if (!await this.repository.claimAttempt(row.id, "PROCESSING", "POLLING", token, true)) continue;
      const claimed = await this.repository.findAttempt(row.userId, row.id);
      if (!claimed) continue;
      if ((claimed.pollAttempts ?? 0) > MAX_POLL_ATTEMPTS) {
        await this.repository.failClaimedAttempt(row.id, "POLLING", token, "语音识别等待超时，请重新录音");
        continue;
      }
      try {
        if (claimed.providerId === null || !claimed.providerTaskId) throw new Error("ASR task reference is missing");
        const result = await this.asr.poll(claimed.providerId, claimed.providerTaskId);
        if (result.state === "PROCESSING") {
          await this.repository.releaseAttemptForPoll(row.id, token);
        } else if (result.state === "FAILED") {
          await this.repository.failClaimedAttempt(row.id, "POLLING", token,
            result.errorMessage || "语音识别失败");
        } else {
          const material = await this.repository.findMaterial(row.userId, row.materialId);
          if (!material) {
            await this.repository.failClaimedAttempt(row.id, "POLLING", token, "背诵材料已不存在");
            continue;
          }
          await this.complete(claimed, material, token, result.transcript ?? "", result.words ?? []);
        }
      } catch {
        await this.repository.releaseAttemptForPoll(row.id, token);
      }
    }
  }

  private async complete(
    row: RecitationAttemptRow,
    material: RecitationMaterialRow,
    token: string,
    transcript: string,
    words: Array<{ text: string; beginTime: number; endTime: number }>,
  ): Promise<void> {
    try {
      const report = { ...align(material.content, transcript), words };
      const reviewedAt = new Date();
      const schedule = mastery(report.score, reviewedAt);
      const completed = await this.repository.completeAttempt({
        attemptId: row.id, userId: row.userId, materialId: row.materialId, token,
        transcript, resultJson: JSON.stringify(report), score: report.score, masteryLevel: schedule.level,
        lastReviewAt: reviewedAt.toISOString(), nextReviewAt: schedule.nextReviewAt,
      });
      if (!completed) {
        await this.repository.failClaimedAttempt(row.id, "POLLING", token, "背诵材料已不存在");
      }
    } catch {
      await this.repository.failClaimedAttempt(row.id, "POLLING", token, "背诵结果分析失败");
    }
  }
}
