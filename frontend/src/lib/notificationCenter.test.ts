import { describe, expect, it } from 'vitest';
import type { Me, PlatformRealtimeEvent, RealtimeMessage, RoleName } from '../api/types';
import {
  DECISION_DEDUPE_MS,
  EMPTY_TOASTS,
  MAX_TOASTS,
  ORDER_TOAST_MS,
  RESTAURANT_QUERY_KEYS,
  baselineFrom,
  createDecisionDeduper,
  createSeenIds,
  decidePlatformEvent,
  decideRestaurantMessage,
  detectStatusTransition,
  dismissToast,
  formatOrderTotal,
  isPlatformEvent,
  isRealtimeMessage,
  messageKey,
  notificationScope,
  orderRouteFor,
  planCatchUp,
  pushToast,
  toastForMessage,
  type CatchUpItem,
  type ToastInput,
} from './notificationCenter';

const msg = (id: number, over: Partial<RealtimeMessage> = {}): RealtimeMessage => ({
  notificationId: id,
  restaurantId: 7,
  orderId: id,
  title: `t${id}`,
  message: `m${id}`,
  notificationType: 'ORDER_PLACED',
  createdAt: '2026-10-20T10:00:00+05:30',
  orderNumber: `ORD-${id}`,
  orderTotal: '450.00',
  ...over,
});

const me = (over: Partial<Me> = {}): Me => ({
  id: 1,
  name: 'A',
  email: 'a@x.com',
  role: 'MANAGER',
  restaurantId: 7,
  restaurantStatus: 'APPROVED',
  ...over,
});

describe('baseline, de-duplication and history', () => {
  it('takes the baseline as the highest stored id (0 when none)', () => {
    expect(baselineFrom([{ id: 3 }, { id: 11 }, { id: 5 }])).toBe(11);
    expect(baselineFrom([])).toBe(0);
  });

  it('plays a new message once and skips its duplicate', () => {
    const seen = createSeenIds<number>();
    expect(decideRestaurantMessage(msg(12), 7, 10, seen)).toBe('live');
    expect(decideRestaurantMessage(msg(12), 7, 10, seen)).toBe('duplicate');
  });

  it('never treats an id at or below the baseline as live', () => {
    const seen = createSeenIds<number>();
    expect(decideRestaurantMessage(msg(10), 7, 10, seen)).toBe('historical');
    expect(decideRestaurantMessage(msg(4), 7, 10, seen)).toBe('historical');
    expect(decideRestaurantMessage(msg(4), 7, 10, seen)).toBe('duplicate');
  });

  it('drops another restaurant without recording it', () => {
    const seen = createSeenIds<number>();
    expect(decideRestaurantMessage(msg(50, { restaurantId: 8 }), 7, 10, seen)).toBe('foreign');
    expect(seen.has(50)).toBe(false);
  });

  it('does not judge messages before the baseline is known', () => {
    const seen = createSeenIds<number>();
    expect(decideRestaurantMessage(msg(50), 7, null, seen)).toBe('not-ready');
    expect(seen.has(50)).toBe(false);
  });

  it('accepts a lower id that arrives after a higher one (out-of-order commits)', () => {
    const seen = createSeenIds<number>();
    expect(decideRestaurantMessage(msg(21), 7, 10, seen)).toBe('live');
    expect(decideRestaurantMessage(msg(20), 7, 10, seen)).toBe('live');
  });

  it('bounds the seen set, forgetting the oldest first', () => {
    const seen = createSeenIds<number>(3);
    [1, 2, 3, 4].forEach((n) => seen.add(n));
    expect(seen.size()).toBe(3);
    expect(seen.has(1)).toBe(false);
    expect(seen.has(4)).toBe(true);
  });

  it('identifies platform events by eventId', () => {
    const seen = createSeenIds<string>();
    const e: PlatformRealtimeEvent = {
      eventId: 'u-1',
      type: 'PLATFORM_RESTAURANT_REGISTERED',
      title: 'New restaurant',
      message: 'Spice Route registered',
      restaurantId: 9,
      restaurantName: 'Spice Route',
      createdAt: '2026-10-20T10:00:00Z',
    };
    expect(decidePlatformEvent(e, seen)).toBe('live');
    expect(decidePlatformEvent(e, seen)).toBe('duplicate');
    expect(decidePlatformEvent({ ...e, eventId: 'u-2' }, seen)).toBe('live');
  });

  it('checks the shape of what arrives on the wire', () => {
    expect(isRealtimeMessage(msg(1))).toBe(true);
    expect(isRealtimeMessage({ ...msg(1), orderTotal: undefined, orderNumber: undefined })).toBe(true);
    expect(isRealtimeMessage({ ...msg(1), notificationId: '1' })).toBe(false);
    expect(isRealtimeMessage(null)).toBe(false);
    expect(isRealtimeMessage('x')).toBe(false);
    expect(isPlatformEvent({ eventId: '', type: 't', title: 'a', message: 'b' })).toBe(false);
  });
});

describe('catching up after a reconnect', () => {
  const unread = [
    { id: 8, title: 'Old', message: '' },
    { id: 11, title: 'Eleven', message: '' },
    { id: 12, title: 'Twelve', message: '' },
    { id: 13, title: 'Thirteen', message: '' },
    { id: 14, title: 'Fourteen', message: '' },
  ];

  it('produces one summary of what is new, newest first, and no per-item output', () => {
    const seen = createSeenIds<number>();
    const summary = planCatchUp(unread, 10, seen);
    expect(summary).not.toBeNull();
    expect(summary?.count).toBe(4);
    expect(summary?.text).toBe('While you were away: 4 new notifications');
    expect(summary?.newest).toEqual(['Fourteen', 'Thirteen', 'Twelve']);
  });

  it('marks the items seen so the live socket cannot announce them again', () => {
    const seen = createSeenIds<number>();
    planCatchUp(unread, 10, seen);
    expect(decideRestaurantMessage(msg(12), 7, 10, seen)).toBe('duplicate');
    // And a second catch-up has nothing left to say.
    expect(planCatchUp(unread, 10, seen)).toBeNull();
  });

  it('ignores history and what was already heard live', () => {
    const seen = createSeenIds<number>();
    decideRestaurantMessage(msg(11), 7, 10, seen);
    decideRestaurantMessage(msg(12), 7, 10, seen);
    const summary = planCatchUp(unread, 10, seen);
    expect(summary?.count).toBe(2);
    expect(planCatchUp([{ id: 3, title: 'x', message: '' }], 10, createSeenIds<number>())).toBeNull();
  });

  it('uses the singular for one notification', () => {
    expect(planCatchUp([{ id: 20, title: 'One', message: '' }], 10, createSeenIds<number>())?.text).toBe(
      'While you were away: 1 new notification',
    );
  });

  it('flags a very recent missed order as worth one chime, and nothing older', () => {
    const now = Date.parse('2026-10-20T10:05:00+05:30');
    const order = (id: number, createdAt: string, notificationType = 'ORDER_PLACED') => ({
      id,
      title: 't',
      message: '',
      notificationType,
      createdAt,
    });
    const run = (items: CatchUpItem[]) => planCatchUp(items, 10, createSeenIds<number>(), now);
    expect(run([order(11, '2026-10-20T10:04:30+05:30')])?.recentOrderId).toBe(11);
    expect(run([order(11, '2026-10-20T09:00:00+05:30')])?.recentOrderId).toBeNull();
    expect(run([order(11, '2026-10-20T10:04:30+05:30', 'PAYMENT_SUCCESS')])?.recentOrderId).toBeNull();
    expect(run([order(11, 'not a date')])?.recentOrderId).toBeNull();
    expect(run([{ id: 11, title: 't', message: '' }])?.recentOrderId).toBeNull();
  });

  it('lets live messages after the reconnect play once', () => {
    const seen = createSeenIds<number>();
    planCatchUp(unread, 10, seen);
    expect(decideRestaurantMessage(msg(15), 7, 10, seen)).toBe('live');
    expect(decideRestaurantMessage(msg(15), 7, 10, seen)).toBe('duplicate');
  });
});

describe('restaurant status transitions', () => {
  it.each([
    ['PENDING', 'APPROVED', 'RESTAURANT_APPROVED'],
    ['REJECTED', 'APPROVED', 'RESTAURANT_APPROVED'],
    ['SUSPENDED', 'APPROVED', 'RESTAURANT_REACTIVATED'],
    ['APPROVED', 'SUSPENDED', 'RESTAURANT_SUSPENDED'],
    ['PENDING', 'REJECTED', 'RESTAURANT_REJECTED'],
  ] as const)('%s -> %s is %s', (from, to, type) => {
    expect(detectStatusTransition(from, to)).toBe(type);
  });

  it('reports nothing for no change, a first sighting, or a return to pending', () => {
    expect(detectStatusTransition('APPROVED', 'APPROVED')).toBeNull();
    expect(detectStatusTransition(null, 'APPROVED')).toBeNull();
    expect(detectStatusTransition(undefined, 'SUSPENDED')).toBeNull();
    expect(detectStatusTransition('APPROVED', null)).toBeNull();
    expect(detectStatusTransition('APPROVED', 'PENDING')).toBeNull();
  });

  it('lets one decision sound only once inside the window', () => {
    const d = createDecisionDeduper();
    expect(d.claim('RESTAURANT_SUSPENDED', 1000)).toBe(true);
    expect(d.claim('RESTAURANT_SUSPENDED', 1000 + DECISION_DEDUPE_MS - 1)).toBe(false);
    expect(d.claim('RESTAURANT_APPROVED', 2000)).toBe(true); // a different decision
    expect(d.claim('RESTAURANT_SUSPENDED', 1000 + DECISION_DEDUPE_MS)).toBe(true); // window over
  });
});

describe('who listens to what', () => {
  it('subscribes the restaurant topic for approved staff or an unknown status', () => {
    expect(notificationScope(me())).toBe('restaurant');
    expect(notificationScope(me({ restaurantStatus: null }))).toBe('restaurant');
    expect(notificationScope(me({ restaurantStatus: undefined }))).toBe('restaurant');
  });

  it('subscribes nothing for a restaurant that is not approved', () => {
    for (const status of ['PENDING', 'REJECTED', 'SUSPENDED'] as const) {
      expect(notificationScope(me({ restaurantStatus: status }))).toBe('none');
    }
  });

  it('uses the platform topic for the platform owner, and nothing for customers or anonymous', () => {
    expect(notificationScope(me({ role: 'PLATFORM_OWNER', restaurantId: null, restaurantStatus: null }))).toBe(
      'platform',
    );
    expect(notificationScope(me({ role: 'CUSTOMER' }))).toBe('none');
    expect(notificationScope(me({ restaurantId: null }))).toBe('none');
    expect(notificationScope(null)).toBe('none');
  });

  it('sends each role to the screen where it handles orders', () => {
    const route = (role: RoleName) => orderRouteFor(role);
    expect(route('RESTAURANT_OWNER')).toBe('/admin/orders');
    expect(route('ADMIN')).toBe('/admin/orders');
    expect(route('MANAGER')).toBe('/admin/orders');
    expect(route('CHEF')).toBe('/kitchen');
    expect(route('WAITER')).toBe('/kitchen');
    expect(route('CASHIER')).toBe('/floor');
    expect(route('CUSTOMER')).toBeNull();
    expect(route('PLATFORM_OWNER')).toBeNull();
  });
});

describe('toasts', () => {
  it('builds the new-order toast with number, total and an action for the role', () => {
    const t = toastForMessage(msg(5), 'New order', 'ok', 'CHEF');
    expect(t.title).toBe('New Order Received!');
    expect(t.body).toContain('ORD-5');
    expect(t.body).toContain('450');
    expect(t.action).toEqual({ label: 'Open order', to: '/kitchen' });
    expect(t.ttlMs).toBe(ORDER_TOAST_MS);
  });

  it('falls back to the message when an old server sends no number or total', () => {
    const t = toastForMessage(msg(5, { orderNumber: null, orderTotal: null, message: 'A new order' }), 'x', 'ok', 'MANAGER');
    expect(t.body).toBe('A new order');
    expect(t.action?.to).toBe('/admin/orders');
  });

  it('shows other types with their own title, message and a short life', () => {
    const t = toastForMessage(msg(6, { notificationType: 'PAYMENT_FAILED', title: 'Payment failed', message: 'Card declined' }), 'Payment failed', 'error', 'MANAGER');
    expect(t).toMatchObject({ title: 'Payment failed', body: 'Card declined', tone: 'error', action: null, ttlMs: 6000 });
  });

  it('formats totals with the money helper and rejects junk', () => {
    expect(formatOrderTotal('450.00')).toMatch(/450/);
    expect(formatOrderTotal(null)).toBeNull();
    expect(formatOrderTotal('')).toBeNull();
    expect(formatOrderTotal('abc')).toBeNull();
  });

  const input = (key: string, ttlMs = 6000): ToastInput => ({ key, tone: 'info', title: key, body: null, ttlMs });

  it('caps visible toasts and counts the ones pushed out', () => {
    let state = EMPTY_TOASTS;
    for (let i = 1; i <= 7; i++) state = pushToast(state, input(`k${i}`), `id${i}`);
    expect(state.items).toHaveLength(MAX_TOASTS);
    expect(state.items[0].key).toBe('k4');
    expect(state.hidden).toBe(3);
  });

  it('merges a repeat of the same event into one toast with a count', () => {
    let state = pushToast(EMPTY_TOASTS, input('PAYMENT_SUCCESS:1'), 'a');
    state = pushToast(state, input('PAYMENT_SUCCESS:1'), 'b');
    expect(state.items).toHaveLength(1);
    expect(state.items[0].count).toBe(2);
    expect(state.items[0].id).toBe('a');
    expect(state.items[0].stamp).toBe(1);
  });

  it('clears the overflow count once everything is dismissed', () => {
    let state = EMPTY_TOASTS;
    for (let i = 1; i <= 6; i++) state = pushToast(state, input(`k${i}`), `id${i}`);
    for (const t of state.items) state = dismissToast(state, t.id);
    expect(state).toEqual(EMPTY_TOASTS);
  });
});

describe('query invalidation', () => {
  it('covers the lists the pages really use', () => {
    const keys = RESTAURANT_QUERY_KEYS.map((k) => k[0]);
    for (const key of [
      'orders',
      'order-payment-summary',
      'kitchen-orders',
      'kitchen-orders-today',
      'notifications',
      'dashboard',
      'service-requests-board',
      'live-tables',
    ]) {
      expect(keys).toContain(key);
    }
  });
});

describe('transient messages (no database id)', () => {
  const transient = (over: Partial<RealtimeMessage> = {}): RealtimeMessage => ({
    ...msg(1),
    notificationId: null,
    orderId: null,
    notificationType: 'SERVICE_REQUEST_UPDATED',
    title: 'Request updated · Table T1',
    ...over,
  });

  it('accepts a message whose id is null or absent, and still rejects a malformed one', () => {
    expect(isRealtimeMessage(transient())).toBe(true);
    const { notificationId: _omit, ...withoutId } = transient();
    void _omit;
    expect(isRealtimeMessage(withoutId)).toBe(true);
    expect(isRealtimeMessage({ ...transient(), notificationId: 'x' })).toBe(false);
    expect(isRealtimeMessage({ ...transient(), notificationId: Number.NaN })).toBe(false);
  });

  it('is live even before the baseline is known, never historical, and still dropped for another restaurant', () => {
    const seen = createSeenIds<number>();
    expect(decideRestaurantMessage(transient(), 7, null, seen)).toBe('live');
    expect(decideRestaurantMessage(transient(), 7, 500, seen)).toBe('live');
    expect(decideRestaurantMessage(transient({ restaurantId: 8 }), 7, 500, seen)).toBe('foreign');
    expect(seen.size()).toBe(0);
  });

  it('keys a stored message by id and a transient one by time and text, per restaurant', () => {
    expect(messageKey(msg(41))).toBe('r7:n41');
    expect(messageKey(transient({ createdAt: 'T1' }))).not.toBe(messageKey(transient({ createdAt: 'T2' })));
    expect(messageKey(transient({ restaurantId: 8 }))).not.toBe(messageKey(transient()));
  });
});
