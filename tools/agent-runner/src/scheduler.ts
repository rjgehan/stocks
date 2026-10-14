// AGENT_SCHEDULE_ET on trading days (ARCHITECTURE §20.5), plus the extra runs each bot books for itself with
// schedule_run (<bot>/requested-runs.json). Every bot has its own loop, so at each slot all configured bots start in
// the same minute (concurrently) and one bot's booked run never waits for another bot. Each run first asks the market
// clock and skips a non-trading day itself.

import type { BotConfig } from './config.js';
import { errorMessage } from './log.js';
import { nextPendingRun, type RequestedRun, takeDue } from './requested.js';
import { runBot, type RunnerDeps, type RunOptions } from './runner.js';
import { botDir } from './store.js';
import { etTime, nextSlot } from './time.js';

/** A slot or booked run that starts this late (process paused, host asleep, runner down) is skipped instead of run late. */
export const MAX_LATE_MS = 10 * 60_000;
/** Timers are re-armed at least this often, so a clock change, a sleep/resume or a newly booked run is noticed. */
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

/** Runs every bot's schedule until `deps.shutdown` aborts. */
export async function runSchedule(bots: BotConfig[], deps: RunnerDeps): Promise<void> {
  await Promise.all(bots.map((bot) => botSchedule(bot, deps)));
}

async function run(bot: BotConfig, deps: RunnerDeps, opts: RunOptions): Promise<void> {
  try {
    await runBot(bot, deps, opts);
  } catch (e) {
    deps.logger.error(`run failed: ${errorMessage(e)}`, bot.id);
  }
}

async function botSchedule(bot: BotConfig, deps: RunnerDeps): Promise<void> {
  const { logger, clock, config, shutdown } = deps;
  const dir = botDir(config.dataDir, bot.id);
  let after = clock.now();
  let announced = '';
  let readError = '';
  while (!shutdown.aborted) {
    const slot = nextSlot(after, config.schedule);
    let booked: RequestedRun | null = null;
    if (config.extraRunsPerDay > 0) {
      try {
        booked = nextPendingRun(dir);
        readError = '';
      } catch (e) {
        const message = `cannot read the booked runs: ${errorMessage(e)}`;
        if (message !== readError) logger.warn(message, bot.id);
        readError = message;
      }
    }
    const useBooked = booked !== null && Date.parse(booked.at) <= slot.at.getTime();
    const at = useBooked && booked ? new Date(booked.at) : slot.at;
    const next =
      useBooked && booked
        ? `next run: ${booked.atEt} ET (${at.toISOString()}), extra run ${booked.id}`
        : `next run: ${slot.dateEt} ${slot.label} ET (${at.toISOString()})`;
    if (next !== announced) {
      logger.info(next, bot.id);
      announced = next;
    }
    const remaining = at.getTime() - clock.now().getTime();
    if (remaining > 0) {
      await wait(Math.min(remaining, MAX_TIMER_MS), shutdown);
      continue; // re-plan: the bot may have booked or cancelled a run meanwhile
    }

    if (useBooked) {
      let due: RequestedRun[] = [];
      try {
        const taken = takeDue(dir, clock.now(), MAX_LATE_MS);
        due = taken.due;
        for (const m of taken.missed) {
          const late = Math.round((clock.now().getTime() - Date.parse(m.at)) / 60_000);
          logger.warn(`skipping the extra run ${m.id} booked for ${m.atEt} ET: ${late} min late`, bot.id);
        }
      } catch (e) {
        logger.error(`cannot update the booked runs: ${errorMessage(e)}`, bot.id);
        await wait(MAX_TIMER_MS, shutdown);
        continue;
      }
      const first = due[0];
      if (first) {
        await run(bot, deps, {
          trigger: 'requested',
          slot: etTime(new Date(first.at)),
          requireTradingDay: true,
          requested: due,
        });
      }
      continue;
    }

    const late = clock.now().getTime() - slot.at.getTime();
    if (late > MAX_LATE_MS) {
      logger.warn(`skipping the ${slot.dateEt} ${slot.label} ET run: ${Math.round(late / 60_000)} min late`, bot.id);
    } else {
      await run(bot, deps, { trigger: 'schedule', slot: slot.label, requireTradingDay: true });
    }
    after = new Date(Math.max(slot.at.getTime(), clock.now().getTime()));
  }
}
