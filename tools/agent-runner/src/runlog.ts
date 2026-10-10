// One JSONL file per run: /data/<bot>/runs/<UTC stamp>.jsonl (ARCHITECTURE §20.5). Every line is redacted.

import { appendFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';
import type { Logger } from './log.js';
import { ensureDir } from './store.js';
import { fileStamp } from './time.js';

export class RunLog {
  readonly path: string;

  private constructor(
    path: string,
    private readonly logger: Logger,
    private readonly now: () => Date,
  ) {
    this.path = path;
  }

  static open(botDir: string, startedAt: Date, logger: Logger, now: () => Date): RunLog {
    const dir = join(botDir, 'runs');
    ensureDir(dir);
    const stamp = fileStamp(startedAt);
    let path = join(dir, `${stamp}.jsonl`);
    for (let i = 2; existsSync(path); i++) path = join(dir, `${stamp}-${i}.jsonl`);
    appendFileSync(path, '', { mode: 0o640 });
    return new RunLog(path, logger, now);
  }

  write(type: string, data: Record<string, unknown>): void {
    const line = JSON.stringify({ ts: this.now().toISOString(), type, ...data }, (_k, v: unknown) =>
      typeof v === 'bigint' ? v.toString() : v,
    );
    appendFileSync(this.path, this.logger.redact(line) + '\n');
  }
}
