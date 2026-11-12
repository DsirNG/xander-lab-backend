/** Pushes provisional text immediately; checkpoints remain ordered and replayable. */
export class AgentAnswerStream {
  private pending = "";
  private offset = 0;
  private writing: Promise<void> | null = null;
  private timer: ReturnType<typeof setTimeout> | undefined;
  private failure: unknown;

  constructor(
    private readonly preview: (offset: number, text: string) => void,
    private readonly checkpoint: (text: string) => Promise<void>,
    private readonly onFailure: () => void,
  ) {}

  push(text: string): void {
    if (this.failure) throw this.failure;
    if (!text) return;
    this.preview(this.offset, text);
    this.offset += text.length;
    this.pending += text;
    if (!this.writing) {
      if (this.offset === text.length || this.pending.length >= 512) this.startWrite();
      else if (!this.timer) this.timer = setTimeout(() => { this.timer = undefined; this.startWrite(); }, 120);
    }
  }

  private startWrite(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
    if (this.writing || !this.pending || this.failure) return;
    this.writing = (async () => {
      // Only one write is in flight; subsequent tokens coalesce instead of queuing writes.
      while (this.pending) {
        const text = this.pending;
        this.pending = "";
        await this.checkpoint(text);
      }
    })().catch((error: unknown) => {
      this.failure = error;
      this.onFailure();
    }).finally(() => { this.writing = null; });
  }

  /** Must settle before any tool or terminal event advances the durable cursor. */
  async flush(): Promise<void> {
    this.startWrite();
    while (this.writing) await this.writing;
    if (this.failure) throw this.failure;
  }
}
