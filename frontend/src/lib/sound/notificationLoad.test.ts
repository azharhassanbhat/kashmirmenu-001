import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RealtimeMessage } from '../../api/types';
import {
  MAX_TOASTS,
  REQUEST_TOAST_MS,
  SEEN_LIMIT,
  EMPTY_TOASTS,
  createCoalescer,
  createSeenIds,
  planLiveMessage,
  pushToast,
  toastForMessage,
} from '../notificationCenter';
import type { SoundKind } from './soundDefs';
import { audible, DEFAULT_PREFS } from './soundPrefs';
import { MAX_PENDING, browserSchedulerDeps, createSoundScheduler } from './soundScheduler';

/*
 * Burst behaviour of the dashboard's decision pipeline (the browser, sockets and audio are not involved, so
 * these numbers are CPU cost of the logic only, on the machine that ran the tests).
 */

// Captured before fake timers replace the clocks, so the timings below are real.
const realNow = ((hr) => () => Number(hr()) / 1e6)(process.hrtime.bigint.bind(process.hrtime));

let nextId = 0;
function msg(type: string, eventKind: string, over: Partial<RealtimeMessage> = {}): RealtimeMessage {
  nextId += 1;
  return {
    notificationId: nextId,
    restaurantId: 1,
    orderId: type.startsWith('ORDER') ? nextId : null,
    title: 'New request · Table ' + nextId,
    message: type === 'SERVICE_REQUEST_CREATED' ? (eventKind === 'BILL_REQUEST' ? 'Bring the bill' : 'Call waiter') : 'm',
    notificationType: type,
    eventKind,
    createdAt: new Date().toISOString(),
    ...over,
  };
}
const mix = (i: number): RealtimeMessage =>
  i % 3 === 0
    ? msg('ORDER_PLACED', 'NEW_ORDER')
    : i % 3 === 1
      ? msg('SERVICE_REQUEST_CREATED', 'WAITER_CALL')
      : msg('SERVICE_REQUEST_CREATED', 'BILL_REQUEST');

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
});
afterEach(() => vi.useRealTimers());

describe('burst of live events', () => {
  it.each([10, 50, 100, 500])('handles %i simultaneous events with no duplicates and bounded work', (n) => {
    const seen = createSeenIds<number>();
    const heard: SoundKind[] = [];
    const scheduler = createSoundScheduler({
      ...browserSchedulerDeps((k) => {
        heard.push(k);
        return true;
      }),
      allow: (r) => audible(DEFAULT_PREFS, r.group!),
    });
    let toasts = EMPTY_TOASTS;
    let peakPending = 0;
    const events = Array.from({ length: n }, (_, i) => mix(i));
    const started = realNow();
    // Every event arrives twice (a reconnect redelivery), in scrambled order.
    const deliveries = [...events, ...events].sort((a, b) => ((a.notificationId as number) * 7919) % 101 - ((b.notificationId as number) * 7919) % 101);
    let live = 0;
    for (const m of deliveries) {
      const plan = planLiveMessage(m, 1, 0, seen, 'MANAGER');
      if (plan.verdict !== 'live') continue;
      live += 1;
      toasts = pushToast(toasts, toastForMessage(m, plan.rule.label, plan.rule.tone, 'MANAGER'), `t${live}`);
      if (plan.hears && plan.rule.sound) {
        scheduler.enqueue({ kind: plan.rule.sound, volume: 70, priority: plan.rule.priority, group: plan.rule.group });
        peakPending = Math.max(peakPending, scheduler.pending());
      }
    }
    const elapsed = realNow() - started;
    vi.advanceTimersByTime(60_000);

    expect(live).toBe(n); // each event counted exactly once: zero duplicates out of 2n deliveries
    expect(toasts.items.length).toBeLessThanOrEqual(MAX_TOASTS);
    expect(peakPending).toBeLessThanOrEqual(MAX_PENDING);
    expect(heard.length).toBeLessThanOrEqual(Math.ceil(60_000 / 700)); // never faster than one sound per gap
    expect(heard).toContain('NEW_ORDER'); // a new order is never dropped
    // ...and neither are the other must-hear kinds: one sound each, in priority order, however many events.
    expect(heard).toEqual(['NEW_ORDER', 'WAITER_CALL', 'BILL_REQUEST']);
    // Logic cost, not delivery latency: even 500 events (1000 deliveries) is far below a frame budget per event.
    expect(elapsed / deliveries.length).toBeLessThan(5);
    console.log(`[load] n=${n} deliveries=${deliveries.length} live=${live} sounds=${heard.length} peakPending=${peakPending} toasts=${toasts.items.length} total=${elapsed.toFixed(1)}ms perDelivery=${((elapsed * 1000) / deliveries.length).toFixed(1)}us`);
  });

  it('does not grow memory with the number of events (seen set is bounded)', () => {
    const seen = createSeenIds<number>();
    for (let i = 1; i <= SEEN_LIMIT * 3; i++) seen.add(i);
    expect(seen.size()).toBe(SEEN_LIMIT);
  });

  it('keeps ten thousand events cheap in memory', () => {
    const seen = createSeenIds<number>();
    const before = process.memoryUsage().heapUsed;
    let toasts = EMPTY_TOASTS;
    for (let i = 0; i < 10_000; i++) {
      const m = mix(i);
      planLiveMessage(m, 1, 0, seen, 'MANAGER');
      toasts = pushToast(toasts, toastForMessage(m, 'x', 'info', 'MANAGER'), `t${i}`);
    }
    const grewMb = (process.memoryUsage().heapUsed - before) / 1_048_576;
    console.log(`[load] 10000 events heap growth ${grewMb.toFixed(2)} MB, toasts kept ${toasts.items.length}, seen ${seen.size()}`);
    expect(grewMb).toBeLessThan(20);
    expect(toasts.items.length).toBeLessThanOrEqual(MAX_TOASTS);
  });

  it('still recognises a recent duplicate after a very busy spell', () => {
    const seen = createSeenIds<number>();
    const events = Array.from({ length: 1500 }, (_, i) => mix(i));
    for (const m of events) planLiveMessage(m, 1, 0, seen, 'MANAGER');
    // The last few hundred are still remembered, so a redelivery of them is a duplicate.
    expect(planLiveMessage(events[1499], 1, 0, seen, 'MANAGER').verdict).toBe('duplicate');
    expect(planLiveMessage(events[1000], 1, 0, seen, 'MANAGER').verdict).toBe('duplicate');
  });
});

describe('toasts during a rush', () => {
  const call = (table: number, kind = 'WAITER_CALL') =>
    msg('SERVICE_REQUEST_CREATED', kind, { title: `New request · Table ${table}`, message: kind === 'BILL_REQUEST' ? 'Bring the bill' : 'Call waiter' });

  it('shows each table separately, so ten calls are never squashed into one "x10"', () => {
    let toasts = EMPTY_TOASTS;
    for (let t = 1; t <= 10; t++) toasts = pushToast(toasts, toastForMessage(call(t), 'Waiter called', 'warn', 'WAITER'), `t${t}`);
    expect(toasts.items.map((i) => i.title)).toEqual([
      'Waiter called · Table 7',
      'Waiter called · Table 8',
      'Waiter called · Table 9',
      'Waiter called · Table 10',
    ]);
    expect(toasts.hidden).toBe(6); // the rest are counted, and still in the bell and on the requests board
    expect(toasts.items.every((i) => i.count === 1)).toBe(true);
  });

  it('merges a repeat from the same table, and keeps waiter and bill for one table apart', () => {
    let toasts = EMPTY_TOASTS;
    toasts = pushToast(toasts, toastForMessage(call(5), 'Waiter called', 'warn', 'WAITER'), 'a');
    toasts = pushToast(toasts, toastForMessage(call(5), 'Waiter called', 'warn', 'WAITER'), 'b');
    toasts = pushToast(toasts, toastForMessage(call(5, 'BILL_REQUEST'), 'Bill requested', 'info', 'WAITER'), 'c');
    expect(toasts.items).toHaveLength(2);
    expect(toasts.items.find((i) => i.title.startsWith('Waiter'))?.count).toBe(2);
  });

  it('keeps waiter and bill toasts up for a minute with a way to the requests board, unlike a plain toast', () => {
    const t = toastForMessage(call(3), 'Waiter called', 'warn', 'WAITER');
    expect(t.ttlMs).toBe(REQUEST_TOAST_MS);
    expect(t.action?.to).toBe('/requests');
    const water = toastForMessage(msg('SERVICE_REQUEST_CREATED', 'TABLE_REQUEST', { message: 'Drinking water' }), 'Table request', 'info', 'WAITER');
    expect(water.ttlMs).toBeLessThan(REQUEST_TOAST_MS);
  });
});

describe('refresh coalescing', () => {
  const deps = {
    now: () => Date.now(),
    setTimer: (f: () => void, ms: number) => setTimeout(f, ms),
    clearTimer: (h: unknown) => clearTimeout(h as ReturnType<typeof setTimeout>),
  };

  it('refreshes a single event at once, as before', () => {
    const fn = vi.fn();
    createCoalescer(fn, 500, deps).trigger();
    expect(fn).toHaveBeenCalledTimes(1);
  });

  it('turns a burst of 500 events into two refreshes (the first, and one when it ends)', () => {
    const fn = vi.fn();
    const c = createCoalescer(fn, 500, deps);
    for (let i = 0; i < 500; i++) {
      c.trigger();
      vi.advanceTimersByTime(1); // the burst lasts 0.5 s
    }
    vi.advanceTimersByTime(2000);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('never leaves the lists stale: an event after the window refreshes again', () => {
    const fn = vi.fn();
    const c = createCoalescer(fn, 500, deps);
    c.trigger();
    vi.advanceTimersByTime(600);
    c.trigger();
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('cancel drops a pending refresh (sign-out, unmount)', () => {
    const fn = vi.fn();
    const c = createCoalescer(fn, 500, deps);
    c.trigger();
    c.trigger();
    c.cancel();
    vi.advanceTimersByTime(5000);
    expect(fn).toHaveBeenCalledTimes(1);
  });
});
