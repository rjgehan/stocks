// stdout logging with secret redaction. Secrets (the LLM and stocks keys) never reach a log line or a run log: every
// line passes through redact(), which removes the configured secret values and anything shaped like a key.

const KEY_SHAPES: RegExp[] = [
  /stk_[A-Za-z0-9_-]{6,}/g, // stocks API keys
  /sk-ant-[A-Za-z0-9_-]{6,}/g, // Anthropic keys
  /\bsk-[A-Za-z0-9_-]{16,}/g, // OpenAI keys
  /(authorization["']?\s*[:=]\s*["']?)(bearer\s+)?[^\s"',}]+/gi, // Authorization headers
  /(x-api-key["']?\s*[:=]\s*["']?)[^\s"',}]+/gi,
];

export type Level = 'INFO' | 'WARN' | 'ERROR';

export interface Sink {
  write(line: string): void;
}

export const stdoutSink: Sink = { write: (line) => process.stdout.write(line) };

export class Logger {
  private readonly secrets: string[];

  constructor(
    secrets: Iterable<string>,
    private readonly sink: Sink = stdoutSink,
    private readonly now: () => Date = () => new Date(),
  ) {
    // Longest first, so a secret that contains another is removed whole.
    this.secrets = [...new Set([...secrets].filter((s) => s.length >= 8))].sort((a, b) => b.length - a.length);
  }

  redact(text: string): string {
    let out = text;
    for (const s of this.secrets) out = out.split(s).join('[redacted]');
    for (const re of KEY_SHAPES) {
      out = out.replace(re, (_m, prefix?: string, bearer?: string) =>
        typeof prefix === 'string' && /^(authorization|x-api-key)/i.test(prefix)
          ? `${prefix}${bearer ?? ''}[redacted]`
          : '[redacted]',
      );
    }
    return out;
  }

  log(level: Level, message: string, bot?: string): void {
    const tag = bot ? ` [${bot}]` : '';
    this.sink.write(this.redact(`${this.now().toISOString()} ${level.padEnd(5)}${tag} ${message}`) + '\n');
  }

  info(message: string, bot?: string): void {
    this.log('INFO', message, bot);
  }

  warn(message: string, bot?: string): void {
    this.log('WARN', message, bot);
  }

  error(message: string, bot?: string): void {
    this.log('ERROR', message, bot);
  }
}

export function errorMessage(e: unknown): string {
  if (e instanceof Error) return e.message;
  return String(e);
}
