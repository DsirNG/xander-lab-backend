import type { RecitationAttemptRow } from "./recitation-repository.js";

export interface RecitationAudioStorage {
  write(objectKey: string, bytes: Uint8Array, contentType: string): Promise<void>;
  delete(objectKey: string): Promise<void>;
  readUrl(objectKey: string): Promise<string>;
}

export interface RecitationAsrEndpoint {
  providerId: number;
  baseUrl: string;
  apiKey: string;
  model: string;
}

export interface RecitationAsrGateway {
  candidates(): Promise<RecitationAsrEndpoint[]>;
}

export interface RecitationAsrPort {
  submit(audioUrl: string): Promise<{ providerId: number; model: string; taskId: string }>;
  poll(providerId: number, taskId: string): Promise<{
    state: "PROCESSING" | "SUCCEEDED" | "FAILED";
    transcript?: string;
    words?: Array<{ text: string; beginTime: number; endTime: number }>;
    errorMessage?: string;
  }>;
}

export interface RecitationUploadResult {
  attempt: RecitationAttemptRow;
  replayed: boolean;
}
