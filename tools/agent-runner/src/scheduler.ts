// AGENT_SCHEDULE_ET on trading days (ARCHITECTURE §20.5): at each slot every configured bot starts in the same
// minute (concurrently); each run first asks the market clock and skips a non-trading day itself.

import type { BotConfig } from './config.js';
import { errorMessage } from './log.js';
import { runBot, type RunnerDeps, type RunSummary } from './runner.js';
import { nextSlot, type Slot } from './time.js';

/** A slot that starts this late (process paused, host asleep) is skipped instead of run late. */
export const MAX_LATE_MS = 10 * 60_000;
/** Timers are re-armed at least this often, so a clock change or a sleep/resume is noticed. */
const MAX_TIMER_MS = 60_000;

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve();
      return;
    }
    const timer = setTimeout(done, Math.max(0, ms));
    function done(): void {
      clearTimeout(timer);
      signal.removeEventListener('abort', done);
      resolve();
    }
    signal.addEventListener('abort', done, { once: true });
  });
}

export async function runSlot(bots: BotConfig[], deps: RunnerDeps, slot: Slot): Promise<RunSummary[]> {
  return Promise.all(
    bots.map((bot) =>
      runBot(bot, deps, { trigger: 'schedule', slot: slot.label, requireTradingDay: true }).catch((e: unknown) => {
        deps.logger.error(`run failed: ${errorMessage(e)}`, bot.id);
        return null;
      }),
    ),
  ).then((all) => all.filter((s): s is RunSummary => s !== null));
}

/** Runs until `deps.shutdown` aborts. */
export async function runSchedule(bots: BotConfig[], deps: RunnerDeps): Promise<void> {
  const { logger, clock, config, shutdown } = deps;
  let after = clock.now();
  while (!shutdown.aborted) {
    const slot = nextSlot(after, config.schedule);
    logger.info(
      `next runs: ${slot.dateEt} ${slot.label} ET (${slot.at.toISOString()}) for ${bots.map((b) => b.id).join(', ')}`,
    );
    for (;;) {
      const remaining = slot.at.getTime() - clock.now().getTime();
      if (remaining <= 0 || shutdown.aborted) break;
      await wait(Math.min(remaining, MAX_TIMER_MS), shutdown);
    }
    if (shutdown.aborted) break;
    const late = clock.now().getTime() - slot.at.getTime();
    if (late > MAX_LATE_MS) {
      logger.warn(`skipping the ${slot.dateEt} ${slot.label} ET runs: ${Math.round(late / 60_000)} min late`);
    } else {
      await runSlot(bots, deps, slot);
    }
    after = new Date(Math.max(slot.at.getTime(), clock.now().getTime()));
  }
}
