import { SOUND_DEFS, type SoundKind } from './soundDefs';
import type { EffectiveGroup } from './soundPrefs';

/*
 * Decides WHEN a sound is allowed to play. A busy kitchen can produce several events in the same second,
 * and a row of overlapping chimes helps nobody, so:
 *   - sounds never overlap and start at least MIN_GAP_MS apart;
 *   - events that arrive close together collapse into the one with the highest priority, EXCEPT that each
 *     "must hear" kind (new order, waiter call, bill request, critical) that lost still gets its own turn right
 *     after, one sound per kind: a waiter call that lands with a new order is queued behind it, not swallowed;
 *     ten waiter calls in the same moment are one bell, not ten;
 *   - a NEW_ORDER sound is never dropped (if something else is playing it plays right after), but several
 *     waiting new-order sounds collapse into one;
 *   - the waiting list is capped.
 * The clock, timers and audio are injected so the whole thing is testable with fake timers.
 */

export const MIN_GAP_MS = 700;
/** Events inside this window of the first one are weighed against each other before anything plays. */
export const COLLAPSE_WINDOW_MS = 120;
export const MAX_PENDING = 6;

/** Sounds that must never be lost to a more important one arriving at the same moment: they follow it instead. */
export const MUST_HEAR: ReadonlySet<SoundKind> = new Set<SoundKind>(['NEW_ORDER', 'WAITER_CALL', 'BILL_REQUEST', 'CRITICAL']);

export interface SoundRequest {
  kind: SoundKind;
  /** 0-100. */
  volume: number;
  priority: number;
  /** Handed back to `allow`, so a preference changed while the request waited can still veto it. */
  group?: EffectiveGroup;
}

export interface SchedulerDeps {
  now: () => number;
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (handle: unknown) => void;
  /** Starts the sound; false when audio is blocked. */
  play: (kind: SoundKind, volume: number) => boolean;
  /** Called when a sound was due but the browser would not play it. */
  onBlocked?: (kind: SoundKind) => void;
  /**
   * Asked again when a waiting request is about to start, so muting (or switching a category off) while
   * it waited silences it. Requests it refuses are dropped without a sound.
   */
  allow?: (request: SoundRequest) => boolean;
  /** The collapse window to use right now; 0 starts a free slot at once (a hidden tab's timers are throttled). */
  collapseWindowMs?: () => number;
}

export interface SoundScheduler {
  enqueue: (request: SoundRequest) => void;
  /** How many requests are waiting. */
  pending: () => number;
  /** Forgets everything waiting and stops the timer. */
  clear: () => void;
}

export function createSoundScheduler(deps: SchedulerDeps): SoundScheduler {
  let queue: SoundRequest[] = [];
  let nextFreeAt = 0;
  let timer: unknown = null;
  let timerAt = 0;

  function best(): SoundRequest {
    // Highest priority wins; the earliest of equals keeps its place.
    return queue.reduce((winner, r) => (r.priority > winner.priority ? r : winner));
  }

  function flush() {
    timer = null;
    if (deps.allow) queue = queue.filter((r) => deps.allow!(r));
    if (queue.length === 0) return;
    const winner = best();
    // The losers that must still be heard, one per kind (the most important of each), keep their place in line.
    const followers = new Map<SoundKind, SoundRequest>();
    for (const r of queue) {
      if (r === winner || r.kind === winner.kind || !MUST_HEAR.has(r.kind)) continue;
      const held = followers.get(r.kind);
      if (!held || r.priority > held.priority) followers.set(r.kind, r);
    }
    queue = [];
    const ok = deps.play(winner.kind, winner.volume);
    if (ok) {
      const t = deps.now();
      nextFreeAt = t + Math.max(MIN_GAP_MS, SOUND_DEFS[winner.kind].durationMs);
      queue = [...followers.values()];
      if (queue.length > 0) arm();
    } else {
      // Audio is blocked: waiting followers would only be refused too (and spin), so they go with the winner.
      deps.onBlocked?.(winner.kind);
    }
  }

  function arm() {
    const t = deps.now();
    const at = Math.max(nextFreeAt, t + (deps.collapseWindowMs?.() ?? COLLAPSE_WINDOW_MS));
    if (at <= t && timer === null) {
      // Nothing to wait for: do not depend on a timer a background tab may delay for seconds.
      flush();
      return;
    }
    if (timer !== null) {
      // An earlier slot is never pushed back by a later arrival.
      if (timerAt <= at) return;
      deps.clearTimer(timer);
    }
    timerAt = at;
    timer = deps.setTimer(flush, Math.max(0, at - t));
  }

  function enqueue(request: SoundRequest) {
    // A request for a sound that is already waiting (same kind, same switch) adds nothing: the one in line
    // covers it. Without this, ten waiter calls would fill the list and push a waiting bill request out.
    const same = queue.findIndex((r) => r.kind === request.kind && r.group === request.group);
    if (same !== -1) {
      if (request.priority > queue[same].priority) queue[same] = request;
      arm();
      return;
    }
    if (request.kind === 'NEW_ORDER' && queue.some((r) => r.kind === 'NEW_ORDER')) {
      // One waiting new-order chime already covers this one.
    } else if (queue.length >= MAX_PENDING) {
      // Full: make room by dropping the least important waiting request, unless the new one is no better.
      // New-order sounds are never the one that goes.
      let victim = -1;
      queue.forEach((r, i) => {
        if (r.kind === 'NEW_ORDER') return;
        if (victim === -1 || r.priority < queue[victim].priority) victim = i;
      });
      if (victim !== -1 && (request.kind === 'NEW_ORDER' || request.priority > queue[victim].priority)) {
        queue.splice(victim, 1);
        queue.push(request);
      }
    } else {
      queue.push(request);
    }
    if (queue.length > 0) arm();
  }

  return {
    enqueue,
    pending: () => queue.length,
    clear() {
      queue = [];
      if (timer !== null) deps.clearTimer(timer);
      timer = null;
    },
  };
}

/** Wires the scheduler to the real clock and timers. */
export function browserSchedulerDeps(
  play: SchedulerDeps['play'],
  onBlocked?: SchedulerDeps['onBlocked'],
): SchedulerDeps {
  return {
    now: () => Date.now(),
    setTimer: (fn, ms) => setTimeout(fn, ms),
    clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
    play,
    onBlocked,
  };
}
