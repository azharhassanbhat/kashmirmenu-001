import type { StaffServiceRequest } from '../../api/types';
import { roleHears, ruleForEvent, ruleFor, type EventRule } from './eventMap';
import type { RoleName } from '../../api/types';

/*
 * Repeat reminders for requests nobody has answered yet: a waiter call, a bill request or a new order.
 *
 * The SERVER decides what is unanswered. The dashboard reads the staff board and the kitchen's pending orders
 * (on load, after every live message, on reconnect and every half minute) and hands the result to the loop
 * below with `sync`. A request that is no longer pending drops out of the next sync, so its reminder stops
 * the moment anyone, on any device, acknowledges it; a refresh simply reads the same server state again, so
 * nothing depends on what one browser tab remembers.
 *
 * To stay useful without becoming annoying:
 *   - however many requests are open, ONE sound plays per reminder moment (the most important one);
 *   - each request is reminded at most MAX_REPEATS times, and never once it is older than MAX_AGE_MS;
 *   - the first reminder comes a full interval after the request is first seen (the live message already
 *     sounded for it), so signing in or refreshing never replays old alerts at once.
 * Pure and clock-injected so it can be tested with a manual clock.
 */

export const MAX_REPEATS = 5;
/** A request older than this is stale, not urgent: it is shown on the board but no longer sounds. */
export const MAX_AGE_MS = 30 * 60_000;

export interface Reminder {
  key: string;
  rule: EventRule;
}

export interface ReminderLoopDeps {
  now: () => number;
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (handle: unknown) => void;
  /** Seconds between reminders right now; read each time so a settings change applies at once. 0 = off. */
  intervalSec: () => number;
  /** Called once per reminder moment with every request that is due (most important first). */
  remind: (due: Reminder[]) => void;
}

export interface ReminderLoop {
  /** Replaces the set of unanswered requests. New ones start their clock now; missing ones stop at once. */
  sync: (targets: ReadonlyMap<string, EventRule>) => void;
  clear: () => void;
  pendingCount: () => number;
}

interface Tracked {
  rule: EventRule;
  due: number;
  count: number;
}

export function createReminderLoop(deps: ReminderLoopDeps): ReminderLoop {
  const tracked = new Map<string, Tracked>();
  let timer: unknown = null;

  function disarm() {
    if (timer !== null) deps.clearTimer(timer);
    timer = null;
  }

  function arm() {
    disarm();
    const seconds = deps.intervalSec();
    if (!(seconds > 0) || tracked.size === 0) return;
    const next = Math.min(...[...tracked.values()].map((t) => t.due));
    timer = deps.setTimer(tick, Math.max(0, next - deps.now()));
  }

  function tick() {
    timer = null;
    const seconds = deps.intervalSec();
    if (!(seconds > 0)) return; // switched off while waiting
    const now = deps.now();
    const due: Reminder[] = [];
    for (const [key, t] of tracked) {
      if (t.due > now) continue;
      due.push({ key, rule: t.rule });
      t.count += 1;
      t.due = now + seconds * 1000;
      if (t.count >= MAX_REPEATS) tracked.delete(key);
    }
    if (due.length > 0) {
      due.sort((a, b) => b.rule.priority - a.rule.priority);
      deps.remind(due);
    }
    arm();
  }

  return {
    sync(targets) {
      const seconds = deps.intervalSec();
      for (const key of [...tracked.keys()]) if (!targets.has(key)) tracked.delete(key);
      for (const [key, rule] of targets) {
        if (!tracked.has(key)) tracked.set(key, { rule, due: deps.now() + Math.max(seconds, 0) * 1000, count: 0 });
      }
      arm();
    },
    clear() {
      tracked.clear();
      disarm();
    },
    pendingCount: () => tracked.size,
  };
}

/* ---------- what the server says is unanswered ---------- */

export interface PendingOrderLike {
  orderId: number;
  status: string;
  createdAt: string;
}

function recentEnough(createdAt: string, now: number): boolean {
  const at = Date.parse(createdAt);
  // An unreadable time is treated as recent: better one extra reminder than a silently dropped request.
  return !Number.isFinite(at) || now - at <= MAX_AGE_MS;
}

/**
 * The requests that should currently be reminded about, for this role. A table request counts while it is
 * PENDING (nobody has acknowledged it); an order while it is PENDING (nobody has confirmed it). Only the
 * roles that hear the original event are reminded, and each request only through the sound and the
 * per-event switch of its own kind.
 */
export function reminderTargets(
  role: RoleName | null,
  requests: readonly Pick<StaffServiceRequest, 'id' | 'type' | 'status' | 'createdAt'>[],
  orders: readonly PendingOrderLike[],
  now: number,
): Map<string, EventRule> {
  const out = new Map<string, EventRule>();
  for (const r of requests) {
    if (r.status !== 'PENDING' || !recentEnough(r.createdAt, now)) continue;
    const eventKind =
      r.type === 'CALL_WAITER' || r.type === 'ASSISTANCE'
        ? 'WAITER_CALL'
        : r.type === 'REQUEST_BILL'
          ? 'BILL_REQUEST'
          : 'TABLE_REQUEST';
    if (eventKind === 'TABLE_REQUEST') continue; // water, cutlery...: not urgent enough to repeat
    if (!roleHears(role, 'SERVICE_REQUEST_CREATED', { eventKind })) continue;
    out.set(`svc:${r.id}`, ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind }));
  }
  if (roleHears(role, 'ORDER_PLACED')) {
    for (const o of orders) {
      if (o.status !== 'PENDING' || !recentEnough(o.createdAt, now)) continue;
      out.set(`order:${o.orderId}`, ruleFor('ORDER_PLACED'));
    }
  }
  return out;
}
