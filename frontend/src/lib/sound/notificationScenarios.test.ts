import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { AppNotification, RealtimeMessage, RoleName } from '../../api/types';
import {
  baselineFrom,
  createSeenIds,
  planCatchUp,
  planLiveMessage,
  type SeenIds,
} from '../notificationCenter';
import { createSoundClaims, type LockManagerLike } from './soundClaims';
import type { SoundKind } from './soundDefs';
import { audible, DEFAULT_PREFS, loadPrefs, prefsKey, sanitizePrefs, savePrefs, type SoundPrefs, type StorageLike } from './soundPrefs';
import { browserSchedulerDeps, createSoundScheduler } from './soundScheduler';
import { createReminderLoop, reminderTargets } from './reminders';

/*
 * End-to-end scenarios with realistic events. A simulated dashboard runs the SAME pure decisions the real one
 * does (planLiveMessage, planCatchUp, the scheduler, the reminder loop, preferences); only the browser (sockets,
 * Web Audio) is replaced by a recorder, so these show which sounds would actually be heard.
 */

const T0 = Date.parse('2026-10-21T12:00:00Z');
let nextId = 1000;

function message(over: Partial<RealtimeMessage> & { notificationType: string }): RealtimeMessage {
  nextId += 1;
  return {
    notificationId: nextId,
    restaurantId: 1,
    orderId: null,
    title: 't',
    message: 'm',
    createdAt: new Date().toISOString(),
    ...over,
  };
}
const newOrder = (over: Partial<RealtimeMessage> = {}) =>
  message({ notificationType: 'ORDER_PLACED', eventKind: 'NEW_ORDER', orderId: nextId + 1, title: 'New order', ...over });
const waiterCall = (over: Partial<RealtimeMessage> = {}) =>
  message({ notificationType: 'SERVICE_REQUEST_CREATED', eventKind: 'WAITER_CALL', title: 'New request · Table 5', message: 'Call waiter', ...over });
const billRequest = (over: Partial<RealtimeMessage> = {}) =>
  message({ notificationType: 'SERVICE_REQUEST_CREATED', eventKind: 'BILL_REQUEST', title: 'New request · Table 7', message: 'Bring the bill', ...over });
const orderReady = (over: Partial<RealtimeMessage> = {}) => message({ notificationType: 'ORDER_READY', eventKind: 'ORDER_READY', orderId: 5, ...over });

class Dashboard {
  readonly heard: SoundKind[] = [];
  readonly toasts: string[] = [];
  readonly seen: SeenIds<number> = createSeenIds<number>();
  baseline: number | null = 0;
  prefs: SoundPrefs = { ...DEFAULT_PREFS };
  readonly scheduler = createSoundScheduler({
    ...browserSchedulerDeps((kind) => {
      this.heard.push(kind);
      return true;
    }),
    allow: (r) => r.group === undefined || audible(this.prefs, r.group),
  });

  constructor(
    readonly restaurantId: number,
    readonly role: RoleName,
  ) {}

  /** One message from the live socket, handled exactly as the provider does. */
  receive(m: RealtimeMessage) {
    const plan = planLiveMessage(m, this.restaurantId, this.baseline, this.seen, this.role);
    if (plan.verdict !== 'live') return plan.verdict;
    this.toasts.push(plan.rule.label);
    if (plan.hears && audible(this.prefs, plan.rule.group) && plan.rule.sound) {
      this.scheduler.enqueue({ kind: plan.rule.sound, volume: this.prefs.volume, priority: plan.rule.priority, group: plan.rule.group });
    }
    return 'live';
  }

  /** Time passes: collapse windows end and queued sounds play. */
  settle(ms = 6000) {
    vi.advanceTimersByTime(ms);
  }
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(T0);
});
afterEach(() => vi.useRealTimers());

describe('which sound each event makes', () => {
  it.each<[string, () => RealtimeMessage, SoundKind]>([
    ['customer places an order', newOrder, 'NEW_ORDER'],
    ['customer calls the waiter', waiterCall, 'WAITER_CALL'],
    ['customer asks for the bill', billRequest, 'BILL_REQUEST'],
    ['kitchen marks an order ready', orderReady, 'ORDER_READY'],
    ['an order is cancelled', () => message({ notificationType: 'ORDER_CANCELLED', eventKind: 'ORDER_CANCELLED', orderId: 3 }), 'ORDER_CANCELLED'],
    ['a payment succeeds', () => message({ notificationType: 'PAYMENT_SUCCESS', eventKind: 'PAYMENT_SUCCESS', orderId: 3 }), 'SUCCESS'],
    ['a payment fails', () => message({ notificationType: 'PAYMENT_FAILED', eventKind: 'PAYMENT_FAILED', orderId: 3 }), 'WARNING'],
  ])('%s', (_name, make, sound) => {
    const d = new Dashboard(1, 'MANAGER');
    d.receive(make());
    d.settle();
    expect(d.heard).toEqual([sound]);
  });

  it('keeps the right sound when the notification text is reworded or translated', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.receive(waiterCall({ message: 'Hilfe am Tisch 5 bitte' }));
    d.settle();
    d.receive(billRequest({ message: 'La cuenta, por favor' }));
    d.settle();
    expect(d.heard).toEqual(['WAITER_CALL', 'BILL_REQUEST']);
  });

  it('still works against an older server that sends no eventKind', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.receive(waiterCall({ eventKind: undefined }));
    d.settle();
    d.receive(billRequest({ eventKind: null }));
    d.settle();
    expect(d.heard).toEqual(['WAITER_CALL', 'BILL_REQUEST']);
  });

  it('never gives a new order and a waiter call the same sound', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.receive(newOrder());
    d.settle();
    d.receive(waiterCall());
    d.settle();
    expect(d.heard).toHaveLength(2);
    expect(new Set(d.heard).size).toBe(2);
  });
});

describe('duplicates, refresh and reconnection', () => {
  it('plays a notification once even if it is delivered twice (a reconnect redelivery)', () => {
    const d = new Dashboard(1, 'MANAGER');
    const m = newOrder();
    expect(d.receive(m)).toBe('live');
    expect(d.receive({ ...m })).toBe('duplicate');
    d.settle();
    expect(d.heard).toEqual(['NEW_ORDER']);
    expect(d.toasts).toHaveLength(1);
  });

  it('does not replay old alerts after a refresh; only what arrives afterwards sounds', () => {
    // Before the refresh the server already holds three unread notifications.
    const stored = [newOrder(), waiterCall(), billRequest()];
    const d = new Dashboard(1, 'MANAGER');
    d.baseline = baselineFrom(stored.map((m) => ({ id: m.notificationId as number })));
    // A late redelivery of one of them (ids at or below the baseline) is history.
    expect(d.receive(stored[0])).toBe('historical');
    expect(d.receive(stored[1])).toBe('historical');
    d.settle();
    expect(d.heard).toEqual([]);
    expect(d.toasts).toEqual([]);
    // A genuinely new one still sounds.
    d.receive(waiterCall());
    d.settle();
    expect(d.heard).toEqual(['WAITER_CALL']);
  });

  it('waits for the baseline before judging anything (no sound from history during start-up)', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.baseline = null;
    expect(d.receive(newOrder())).toBe('not-ready');
    d.settle();
    expect(d.heard).toEqual([]);
  });

  describe('recovering what was missed while disconnected', () => {
    const asUnread = (m: RealtimeMessage, createdAt: string): AppNotification => ({
      id: m.notificationId as number,
      orderId: m.orderId,
      orderNumber: null,
      title: m.title,
      message: m.message,
      notificationType: m.notificationType,
      eventKind: m.eventKind,
      read: false,
      createdAt,
    });
    const ago = (ms: number) => new Date(Date.now() - ms).toISOString();

    it('summarises everything missed in one notice and marks it seen so it cannot sound twice', () => {
      const seen = createSeenIds<number>();
      const missed = [waiterCall(), newOrder(), billRequest()].map((m) => asUnread(m, ago(30_000)));
      const first = planCatchUp(missed, 0, seen, Date.now());
      expect(first?.count).toBe(3);
      // The live socket then redelivers one of them: it is a duplicate.
      expect(planCatchUp(missed, 0, seen, Date.now())).toBeNull();
    });

    it('lets a very recent missed waiter call or order be heard once, and keeps older ones silent', () => {
      const seen = createSeenIds<number>();
      const recent = planCatchUp([asUnread(waiterCall(), ago(20_000))], 0, seen, Date.now());
      expect(recent?.recentWaiterCallId).not.toBeNull();
      const old = planCatchUp([asUnread(waiterCall(), ago(20 * 60_000)), asUnread(newOrder(), ago(20 * 60_000))], 0, createSeenIds<number>(), Date.now());
      expect(old?.recentWaiterCallId).toBeNull();
      expect(old?.recentOrderId).toBeNull();
    });

    it('recognises a missed waiter call on an older server by its text', () => {
      const m = waiterCall({ eventKind: undefined });
      const plan = planCatchUp([asUnread(m, ago(10_000))], 0, createSeenIds<number>(), Date.now());
      expect(plan?.recentWaiterCallId).toBe(m.notificationId);
    });

    it('does not treat a missed bill request as a waiter call', () => {
      const plan = planCatchUp([asUnread(billRequest(), ago(10_000))], 0, createSeenIds<number>(), Date.now());
      expect(plan?.recentWaiterCallId).toBeNull();
    });
  });
});

describe('several events at once', () => {
  it('controls playback when three orders and two waiter calls arrive together', () => {
    const d = new Dashboard(1, 'MANAGER');
    for (const m of [newOrder(), waiterCall(), newOrder(), waiterCall(), newOrder()]) d.receive(m);
    d.settle(10_000);
    // The most important sound first (a new order), then the waiter bell; repeats of either are one sound.
    expect(d.heard).toEqual(['NEW_ORDER', 'WAITER_CALL']);
    expect(d.toasts).toHaveLength(5); // every event is still shown
  });

  it('never drops a new order, even while another sound is playing', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.receive(waiterCall());
    d.settle(200); // the waiter bell has started
    d.receive(newOrder());
    d.settle(5000);
    expect(d.heard).toEqual(['WAITER_CALL', 'NEW_ORDER']);
  });

  it('starts sounds at least a gap apart, so none overlap', () => {
    const times: number[] = [];
    const scheduler = createSoundScheduler(
      browserSchedulerDeps(() => {
        times.push(Date.now());
        return true;
      }),
    );
    scheduler.enqueue({ kind: 'WAITER_CALL', volume: 70, priority: 80 });
    vi.advanceTimersByTime(300);
    scheduler.enqueue({ kind: 'NEW_ORDER', volume: 70, priority: 100 });
    scheduler.enqueue({ kind: 'BILL_REQUEST', volume: 70, priority: 62 });
    vi.advanceTimersByTime(10_000);
    for (let i = 1; i < times.length; i++) expect(times[i] - times[i - 1]).toBeGreaterThanOrEqual(700);
  });
});

describe('who hears what, per role', () => {
  const heardBy = (role: RoleName, m: RealtimeMessage) => {
    const d = new Dashboard(1, role);
    d.receive(m);
    d.settle();
    return d.heard;
  };

  it('owner and manager hear every restaurant event', () => {
    for (const role of ['RESTAURANT_OWNER', 'MANAGER', 'ADMIN'] as const) {
      expect(heardBy(role, newOrder())).toEqual(['NEW_ORDER']);
      expect(heardBy(role, waiterCall())).toEqual(['WAITER_CALL']);
      expect(heardBy(role, billRequest())).toEqual(['BILL_REQUEST']);
    }
  });

  it('a chef hears kitchen orders and cancellations only', () => {
    expect(heardBy('CHEF', newOrder())).toEqual(['NEW_ORDER']);
    expect(heardBy('CHEF', message({ notificationType: 'ORDER_CANCELLED', eventKind: 'ORDER_CANCELLED', orderId: 1 }))).toEqual(['ORDER_CANCELLED']);
    expect(heardBy('CHEF', waiterCall())).toEqual([]);
    expect(heardBy('CHEF', billRequest())).toEqual([]);
  });

  it('a waiter hears waiter calls, bills and ready orders, not new orders', () => {
    expect(heardBy('WAITER', waiterCall())).toEqual(['WAITER_CALL']);
    expect(heardBy('WAITER', billRequest())).toEqual(['BILL_REQUEST']);
    expect(heardBy('WAITER', orderReady())).toEqual(['ORDER_READY']);
    expect(heardBy('WAITER', newOrder())).toEqual([]);
  });

  it('a cashier hears bills and payments only', () => {
    expect(heardBy('CASHIER', billRequest())).toEqual(['BILL_REQUEST']);
    expect(heardBy('CASHIER', message({ notificationType: 'PAYMENT_SUCCESS', eventKind: 'PAYMENT_SUCCESS', orderId: 1 }))).toEqual(['SUCCESS']);
    expect(heardBy('CASHIER', waiterCall())).toEqual([]);
    expect(heardBy('CASHIER', newOrder())).toEqual([]);
  });

  it('still shows the toast for events a role does not hear', () => {
    const d = new Dashboard(1, 'CHEF');
    d.receive(waiterCall());
    expect(d.toasts).toEqual(['Waiter called']);
    expect(d.heard).toEqual([]);
  });
});

describe('two restaurants are completely isolated', () => {
  it('a dashboard ignores every message that names another restaurant', () => {
    const a = new Dashboard(1, 'MANAGER');
    const b = new Dashboard(2, 'MANAGER');
    const forB = [newOrder({ restaurantId: 2 }), waiterCall({ restaurantId: 2 }), billRequest({ restaurantId: 2 })];
    for (const m of forB) {
      expect(a.receive(m)).toBe('foreign');
      expect(b.receive(m)).toBe('live');
    }
    a.settle();
    b.settle(10_000);
    expect(a.heard).toEqual([]);
    expect(a.toasts).toEqual([]);
    expect(b.heard.length).toBeGreaterThan(0);
  });

  it('a foreign message leaves no trace: its id is not marked seen', () => {
    const a = new Dashboard(1, 'MANAGER');
    const foreign = newOrder({ restaurantId: 2 });
    a.receive(foreign);
    expect(a.seen.has(foreign.notificationId as number)).toBe(false);
  });

  it('never reminds about another restaurant (the server scopes the lists; ids never mix)', () => {
    const board1 = [{ id: 1, type: 'CALL_WAITER', status: 'PENDING', createdAt: new Date().toISOString() }];
    const board2 = [{ id: 2, type: 'CALL_WAITER', status: 'PENDING', createdAt: new Date().toISOString() }];
    const asReq = (b: typeof board1) => b as unknown as Parameters<typeof reminderTargets>[1];
    expect([...reminderTargets('MANAGER', asReq(board1), [], Date.now()).keys()]).toEqual(['svc:1']);
    expect([...reminderTargets('MANAGER', asReq(board2), [], Date.now()).keys()]).toEqual(['svc:2']);
  });
});

describe('devices and tabs', () => {
  it('two tabs of one browser: only the first to claim an event plays it', async () => {
    const held = new Set<string>();
    const locks: LockManagerLike = {
      request: (name, _opts, cb) => {
        if (held.has(name)) return Promise.resolve(cb(null));
        held.add(name);
        return Promise.resolve(cb({}));
      },
    };
    const tabA = createSoundClaims(locks, 0, () => Promise.resolve());
    const tabB = createSoundClaims(locks, 0, () => Promise.resolve());
    const m = waiterCall();
    const key = `r1:n${m.notificationId}`;
    const results = await Promise.all([tabA.claim(key), tabB.claim(key)]);
    expect(results.filter(Boolean)).toHaveLength(1);
    // A different event is a different claim.
    expect(await tabB.claim(`r1:n${(m.notificationId as number) + 1}`)).toBe(true);
  });

  it('separate devices each play: nothing is shared between two dashboards', () => {
    const phone = new Dashboard(1, 'WAITER');
    const tablet = new Dashboard(1, 'WAITER');
    const m = waiterCall();
    phone.receive(m);
    tablet.receive({ ...m });
    phone.settle();
    tablet.settle();
    expect(phone.heard).toEqual(['WAITER_CALL']);
    expect(tablet.heard).toEqual(['WAITER_CALL']);
  });

  it('two waiters signed in at once both hear a waiter call', () => {
    const w1 = new Dashboard(1, 'WAITER');
    const w2 = new Dashboard(1, 'WAITER');
    const m = waiterCall();
    w1.receive(m);
    w2.receive({ ...m });
    w1.settle();
    w2.settle();
    expect([w1.heard, w2.heard]).toEqual([['WAITER_CALL'], ['WAITER_CALL']]);
  });

  it('a customer who calls the waiter repeatedly makes distinct requests, each of which sounds', () => {
    const d = new Dashboard(1, 'WAITER');
    d.receive(waiterCall({ title: 'New request · Table 5' }));
    d.settle();
    d.receive(waiterCall({ title: 'New request · Table 5' }));
    d.settle();
    expect(d.heard).toEqual(['WAITER_CALL', 'WAITER_CALL']);
  });
});

describe('reminders follow the server, not the browser', () => {
  function loopWith(intervalSec: number) {
    const reminded: string[][] = [];
    const loop = createReminderLoop({
      now: () => Date.now(),
      setTimer: (fn, ms) => setTimeout(fn, ms),
      clearTimer: (h) => clearTimeout(h as ReturnType<typeof setTimeout>),
      intervalSec: () => intervalSec,
      remind: (due) => reminded.push(due.map((r) => r.key)),
    });
    return { loop, reminded };
  }
  const board = (status: string) =>
    [{ id: 9, type: 'CALL_WAITER', status, createdAt: new Date().toISOString() }] as unknown as Parameters<typeof reminderTargets>[1];

  it('reminds while the waiter call is pending and stops when the waiter acknowledges it', () => {
    const { loop, reminded } = loopWith(30);
    loop.sync(reminderTargets('WAITER', board('PENDING'), [], Date.now()));
    vi.advanceTimersByTime(30_000);
    expect(reminded).toEqual([['svc:9']]);
    // The next read of the board shows it acknowledged (by this waiter or another).
    loop.sync(reminderTargets('WAITER', board('ACKNOWLEDGED'), [], Date.now()));
    vi.advanceTimersByTime(300_000);
    expect(reminded).toEqual([['svc:9']]);
  });

  it('signing out clears every reminder and every queued sound', () => {
    const { loop, reminded } = loopWith(30);
    const d = new Dashboard(1, 'MANAGER');
    loop.sync(reminderTargets('MANAGER', board('PENDING'), [], Date.now()));
    d.receive(newOrder());
    loop.clear();
    d.scheduler.clear();
    vi.advanceTimersByTime(600_000);
    expect(reminded).toEqual([]);
    expect(d.heard).toEqual([]);
  });
});

describe('preferences persist per user', () => {
  function memoryStorage(): StorageLike & { data: Record<string, string> } {
    const data: Record<string, string> = {};
    return { data, getItem: (k) => data[k] ?? null, setItem: (k, v) => void (data[k] = v) };
  }

  it('saves theme, volume, reminders and per-event switches, and reads them back', () => {
    const storage = memoryStorage();
    const mine: SoundPrefs = { ...DEFAULT_PREFS, theme: 'crystal', volume: 40, reminderSec: 120, waiterCalls: false, billRequests: false };
    expect(savePrefs(storage, prefsKey(42), mine)).toBe(true);
    expect(loadPrefs(storage, prefsKey(42))).toEqual(mine);
  });

  it('keeps one user\'s settings away from another on the same device', () => {
    const storage = memoryStorage();
    savePrefs(storage, prefsKey(1), { ...DEFAULT_PREFS, theme: 'warm', master: false });
    expect(loadPrefs(storage, prefsKey(2))).toEqual(DEFAULT_PREFS);
    expect(loadPrefs(storage, prefsKey(1)).theme).toBe('warm');
  });

  it('upgrades a record saved before the new settings existed', () => {
    const storage = memoryStorage();
    storage.setItem(prefsKey(5), JSON.stringify({ master: true, newOrder: false, volume: 55 }));
    const prefs = loadPrefs(storage, prefsKey(5));
    expect(prefs.newOrder).toBe(false);
    expect(prefs.volume).toBe(55);
    expect(prefs.theme).toBe('bell');
    expect(prefs.reminderSec).toBe(60);
    expect(prefs.waiterCalls).toBe(true);
  });

  it('rejects an unknown theme or reminder interval instead of trusting storage', () => {
    const prefs = sanitizePrefs({ theme: 'disco', reminderSec: 7 });
    expect(prefs.theme).toBe('bell');
    expect(prefs.reminderSec).toBe(60);
  });

  it('a switched-off event does not sound, while the others do', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.prefs = { ...d.prefs, waiterCalls: false };
    d.receive(waiterCall());
    d.receive(billRequest());
    d.settle();
    expect(d.heard).toEqual(['BILL_REQUEST']);
  });

  it('muting everything silences every event', () => {
    const d = new Dashboard(1, 'MANAGER');
    d.prefs = { ...d.prefs, master: false };
    d.receive(newOrder());
    d.settle();
    expect(d.heard).toEqual([]);
  });
});
