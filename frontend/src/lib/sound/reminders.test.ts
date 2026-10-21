import { describe, expect, it } from 'vitest';
import { ruleFor, ruleForEvent, type EventRule } from './eventMap';
import { MAX_AGE_MS, MAX_REPEATS, createReminderLoop, reminderTargets, type Reminder } from './reminders';

const ORDER = ruleFor('ORDER_PLACED');
const WAITER = ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind: 'WAITER_CALL' });
const BILL = ruleForEvent('SERVICE_REQUEST_CREATED', { eventKind: 'BILL_REQUEST' });

/** A manual clock: timers fire only when `advance` passes their time. */
function harness(intervalSec = 60) {
  let now = 1_000_000;
  let seq = 0;
  const timers = new Map<number, { at: number; fn: () => void }>();
  const reminded: string[][] = [];
  let interval = intervalSec;
  const loop = createReminderLoop({
    now: () => now,
    setTimer: (fn: () => void, ms: number) => {
      seq += 1;
      timers.set(seq, { at: now + ms, fn });
      return seq;
    },
    clearTimer: (h: unknown) => void timers.delete(h as number),
    intervalSec: () => interval,
    remind: (due: Reminder[]) => reminded.push(due.map((d) => d.key)),
  });
  function advance(ms: number) {
    const end = now + ms;
    for (;;) {
      const next = [...timers.entries()].filter(([, t]) => t.at <= end).sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      timers.delete(next[0]);
      now = next[1].at;
      next[1].fn();
    }
    now = end;
  }
  const targets = (...entries: [string, EventRule][]) => new Map<string, EventRule>(entries);
  return { loop, advance, reminded, timers, setInterval: (s: number) => (interval = s), targets };
}

describe('reminder loop', () => {
  it('reminds a full interval after a request is first seen, not at once', () => {
    const h = harness(30);
    h.loop.sync(h.targets(['a', ORDER]));
    expect(h.reminded).toEqual([]);
    h.advance(29_000);
    expect(h.reminded).toEqual([]);
    h.advance(1_000);
    expect(h.reminded).toEqual([['a']]);
    h.advance(30_000);
    expect(h.reminded).toEqual([['a'], ['a']]);
  });

  it('stops the moment the server no longer lists the request (acknowledged or resolved)', () => {
    const h = harness(30);
    h.loop.sync(h.targets(['a', WAITER]));
    h.advance(30_000);
    h.loop.sync(h.targets()); // the next read of the board no longer has it pending
    h.advance(600_000);
    expect(h.reminded).toEqual([['a']]);
    expect(h.timers.size).toBe(0);
    expect(h.loop.pendingCount()).toBe(0);
  });

  it('plays ONE reminder for several open requests, most important first', () => {
    const h = harness(30);
    h.loop.sync(h.targets(['bill', BILL], ['order', ORDER], ['waiter', WAITER]));
    h.advance(30_000);
    expect(h.reminded).toEqual([['order', 'waiter', 'bill']]);
  });

  it('keeps a new request on its own clock while an older one keeps its schedule', () => {
    const h = harness(60);
    h.loop.sync(h.targets(['old', WAITER]));
    h.advance(40_000);
    h.loop.sync(h.targets(['old', WAITER], ['new', BILL]));
    h.advance(20_000); // old is due, new is not
    expect(h.reminded).toEqual([['old']]);
    h.advance(40_000); // new is due now (60s after it was first seen)
    expect(h.reminded).toEqual([['old'], ['new']]);
  });

  it('does not restart a request already tracked when the same list arrives again', () => {
    const h = harness(30);
    h.loop.sync(h.targets(['a', WAITER]));
    h.advance(20_000);
    h.loop.sync(h.targets(['a', WAITER]));
    h.loop.sync(h.targets(['a', WAITER]));
    h.advance(10_000);
    expect(h.reminded).toEqual([['a']]);
  });

  it('gives up on a request after the maximum number of reminders', () => {
    const h = harness(10);
    h.loop.sync(h.targets(['a', ORDER]));
    h.advance(10_000 * 50);
    expect(h.reminded).toHaveLength(MAX_REPEATS);
    expect(h.loop.pendingCount()).toBe(0);
  });

  it('does nothing when reminders are off, and follows a changed interval', () => {
    const off = harness(0);
    off.loop.sync(off.targets(['a', ORDER]));
    off.advance(600_000);
    expect(off.reminded).toEqual([]);
    const g = harness(30);
    g.loop.sync(g.targets(['a', ORDER]));
    g.advance(30_000);
    g.setInterval(0);
    g.advance(600_000);
    expect(g.reminded).toEqual([['a']]);
  });

  it('clear stops everything', () => {
    const h = harness(30);
    h.loop.sync(h.targets(['a', ORDER], ['b', WAITER]));
    h.loop.clear();
    h.advance(600_000);
    expect(h.reminded).toEqual([]);
    expect(h.loop.pendingCount()).toBe(0);
  });

  it('a refresh rebuilds the same reminders from server state, without replaying anything at once', () => {
    // Before the refresh: a waiter call has been open for 50 seconds.
    const board = [{ id: 7, type: 'CALL_WAITER' as const, status: 'PENDING' as const, createdAt: new Date(1_000_000 - 50_000).toISOString() }];
    const first = harness(60);
    first.loop.sync(reminderTargets('MANAGER', board, [], 1_000_000));
    // The page reloads: a brand-new loop reads the same server state.
    const second = harness(60);
    second.loop.sync(reminderTargets('MANAGER', board, [], 1_000_000));
    expect(second.reminded).toEqual([]); // nothing plays on load
    second.advance(60_000);
    expect(second.reminded).toEqual([['svc:7']]); // but it is still being reminded about
  });
});

describe('reminderTargets (what the server says is unanswered)', () => {
  const NOW = Date.parse('2026-10-21T12:00:00Z');
  const at = (msAgo: number) => new Date(NOW - msAgo).toISOString();
  type Req = { id: number; type: string; status: string; createdAt: string };
  const req = (id: number, type: string, status: string, msAgo = 60_000): Req => ({ id, type, status, createdAt: at(msAgo) });
  const order = (orderId: number, status: string, msAgo = 60_000) => ({ orderId, status, createdAt: at(msAgo) });
  const keys = (m: Map<string, EventRule>) => [...m.keys()].sort();
  // The StaffServiceRequest type is wider than the fields used here.
  const targets = (role: Parameters<typeof reminderTargets>[0], requests: Req[], orders: ReturnType<typeof order>[]) =>
    reminderTargets(role, requests as unknown as Parameters<typeof reminderTargets>[1], orders, NOW);

  it('lists pending waiter calls, bills and orders for a manager', () => {
    const m = targets('MANAGER', [req(1, 'CALL_WAITER', 'PENDING'), req(2, 'REQUEST_BILL', 'PENDING')], [order(9, 'PENDING')]);
    expect(keys(m)).toEqual(['order:9', 'svc:1', 'svc:2']);
    expect(m.get('svc:1')?.sound).toBe('WAITER_CALL');
    expect(m.get('svc:2')?.sound).toBe('BILL_REQUEST');
    expect(m.get('order:9')?.sound).toBe('NEW_ORDER');
  });

  it('drops anything acknowledged, in progress, finished or cancelled', () => {
    const m = targets(
      'MANAGER',
      ['ACKNOWLEDGED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'].map((s, i) => req(i, 'CALL_WAITER', s)),
      ['CONFIRMED', 'PREPARING', 'READY', 'SERVED', 'CANCELLED'].map((s, i) => order(i, s)),
    );
    expect(m.size).toBe(0);
  });

  it('handles several open requests of the same kind independently', () => {
    const m = targets('MANAGER', [req(1, 'CALL_WAITER', 'PENDING'), req(2, 'CALL_WAITER', 'PENDING'), req(3, 'CALL_WAITER', 'ACKNOWLEDGED')], []);
    expect(keys(m)).toEqual(['svc:1', 'svc:2']);
  });

  it('ignores ordinary requests such as water, and stale ones', () => {
    const m = targets('MANAGER', [req(1, 'DRINKING_WATER', 'PENDING'), req(2, 'CALL_WAITER', 'PENDING', MAX_AGE_MS + 1000)], [order(5, 'PENDING', MAX_AGE_MS + 1000)]);
    expect(m.size).toBe(0);
  });

  it('only reminds a role about what that role hears', () => {
    const reqs = [req(1, 'CALL_WAITER', 'PENDING'), req(2, 'REQUEST_BILL', 'PENDING')];
    const orders = [order(9, 'PENDING')];
    expect(keys(targets('CHEF', reqs, orders))).toEqual(['order:9']);
    expect(keys(targets('WAITER', reqs, orders))).toEqual(['svc:1', 'svc:2']);
    expect(keys(targets('CASHIER', reqs, orders))).toEqual(['svc:2']);
  });
});
