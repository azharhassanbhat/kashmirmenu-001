import { describe, expect, it } from 'vitest';
import type { Order } from '../api/types';
import {
  greeting,
  navBadges,
  orderBadge,
  placedAt,
  placedLabel,
  popularItems,
  revenuePoints,
  searchEntries,
  statusSlices,
  tableStates,
} from './data';

const order = (status: Order['status'], items: [string, number, number][]): Order =>
  ({
    id: 1,
    orderNumber: 'ORD-1',
    status,
    total: 0,
    items: items.map(([itemName, quantity, menuItemId], i) => ({ id: i, menuItemId, itemName, quantity, unitPrice: 1, lineTotal: quantity })),
  }) as unknown as Order;

describe('statusSlices', () => {
  it('sums the four statuses and gives rounded shares', () => {
    const r = statusSlices({ pendingOrders: 0, preparingOrders: 1, readyOrders: 1, servedOrders: 2 });
    expect(r.total).toBe(4);
    expect(r.slices.map((s) => [s.key, s.value, s.percent])).toEqual([
      ['new', 0, 0],
      ['preparing', 1, 25],
      ['ready', 1, 25],
      ['served', 2, 50],
    ]);
  });

  it('is all zeros, not NaN, with no orders', () => {
    const r = statusSlices({ pendingOrders: 0, preparingOrders: 0, readyOrders: 0, servedOrders: 0 });
    expect(r.total).toBe(0);
    expect(r.slices.every((s) => s.percent === 0)).toBe(true);
  });
});

describe('popularItems', () => {
  it('ranks dishes by units, ignores cancelled orders and gives shares of all units', () => {
    const orders = [
      order('SERVED', [['Rogan Josh', 3, 1], ['Kahwa', 1, 4]]),
      order('PENDING', [['Rogan Josh', 2, 1], ['Gushtaba', 2, 3]]),
      order('CANCELLED', [['Kashmiri Pulao', 50, 2]]),
    ];
    const top = popularItems(orders, 3);
    expect(top.map((t) => [t.name, t.count, t.percent])).toEqual([
      ['Rogan Josh', 5, 63],
      ['Gushtaba', 2, 25],
      ['Kahwa', 1, 13],
    ]);
  });

  it('is empty without orders and breaks ties by name', () => {
    expect(popularItems([])).toEqual([]);
    const tie = popularItems([order('SERVED', [['B', 1, 2], ['A', 1, 1]])]);
    expect(tie.map((t) => t.name)).toEqual(['A', 'B']);
  });
});

describe('tableStates', () => {
  const tables = [
    { id: 1, tableName: 'T1', active: true },
    { id: 2, tableName: 'T2', active: true },
    { id: 3, tableName: 'T3', active: true },
    { id: 4, tableName: 'T4', active: false },
    { id: 5, tableName: 'T5', active: true },
  ];

  it('puts every table in exactly one state, so the counts add up', () => {
    const r = tableStates(
      tables,
      [{ tableName: 'T1', status: 'OPEN' }, { tableName: 'T2', status: 'OPEN' }, { tableName: 'T3', status: 'CLOSED' }],
      [{ tableId: 2, status: 'PENDING' }, { tableId: 5, status: 'COMPLETED' }],
    );
    const by = Object.fromEntries(r.slices.map((s) => [s.key, s.count]));
    // T2 has an open request (so "requested", not "occupied"); T5's request is done; T4 is switched off.
    expect(by).toEqual({ occupied: 1, available: 2, requested: 1, offline: 1 });
    expect(r.slices.reduce((n, s) => n + s.count, 0)).toBe(r.total);
    expect(r.total).toBe(5);
  });

  it('handles a restaurant with no tables', () => {
    const r = tableStates([], [], []);
    expect(r.total).toBe(0);
    expect(r.slices.every((s) => s.percent === 0)).toBe(true);
  });
});

describe('revenuePoints', () => {
  it('sums every payment method per bucket', () => {
    const points = revenuePoints({
      series: {
        granularity: 'DAILY',
        buckets: ['2026-10-14T00:00:00+05:30', '2026-10-15T00:00:00+05:30'],
        rows: [
          { key: 'CASH', label: 'Cash', amounts: [100, 50], counts: [1, 1] },
          { key: 'UPI', label: 'UPI', amounts: [200, 0], counts: [2, 0] },
        ],
      },
    });
    expect(points.map((p) => [p.revenue, p.orders])).toEqual([[300, 3], [50, 1]]);
    expect(points[0].label).toMatch(/14/);
  });
});

describe('live order helpers', () => {
  it('takes the earliest history entry as the placed time', () => {
    const history = [
      { from: 'PENDING', to: 'CONFIRMED', actor: 'a', reason: null, reasonLabel: null, note: null, at: '2026-10-15T22:42:00+05:30' },
      { from: null, to: 'PENDING', actor: 'a', reason: null, reasonLabel: null, note: null, at: '2026-10-15T22:30:00+05:30' },
    ] as const;
    expect(placedAt([...history])).toBe('2026-10-15T22:30:00+05:30');
    expect(placedAt([])).toBeNull();
    expect(placedAt(undefined)).toBeNull();
  });

  it('labels today and other days differently, and is empty for an unreadable time', () => {
    const now = new Date('2026-10-15T23:00:00+05:30');
    expect(placedLabel('2026-10-15T22:42:00+05:30', now)).toMatch(/^Today, /);
    expect(placedLabel('2026-10-10T09:05:00+05:30', now)).not.toMatch(/^Today/);
    expect(placedLabel(null, now)).toBe('');
  });

  it('gives each status a badge', () => {
    expect(orderBadge('SERVED')).toEqual({ label: 'Served', tone: 'green' });
    expect(orderBadge('PREPARING').tone).toBe('orange');
    expect(orderBadge('READY').tone).toBe('blue');
    expect(orderBadge('CANCELLED').tone).toBe('red');
  });
});

describe('greeting and search', () => {
  it('follows the hour', () => {
    expect(greeting(new Date(2026, 9, 15, 9))).toBe('Good morning');
    expect(greeting(new Date(2026, 9, 15, 14))).toBe('Good afternoon');
    expect(greeting(new Date(2026, 9, 15, 21))).toBe('Good evening');
    expect(greeting(new Date(2026, 9, 15, 2))).toBe('Good night');
  });

  const entries = [
    { label: 'Orders', hint: 'Orders & Tables', to: '/admin/orders' },
    { label: 'Menu items', hint: 'Menu & Inventory', to: '/admin/menu' },
    { label: 'ORD-12', hint: 'Order · Asha', to: '/admin/orders' },
    { label: 'Tables & QR', hint: 'Orders & Tables', to: '/admin/tables' },
  ];

  it('matches case-insensitively, prefix matches first, and ignores an empty query', () => {
    expect(searchEntries(entries, '')).toEqual([]);
    expect(searchEntries(entries, '  ')).toEqual([]);
    expect(searchEntries(entries, 'ord').map((e) => e.label)).toEqual(['Orders', 'ORD-12', 'Tables & QR']);
    expect(searchEntries(entries, 'asha').map((e) => e.label)).toEqual(['ORD-12']);
    expect(searchEntries(entries, 'zzz')).toEqual([]);
  });

  it('caps the number of results', () => {
    const many = Array.from({ length: 20 }, (_, i) => ({ label: `Item ${i}`, hint: '', to: '/' }));
    expect(searchEntries(many, 'item', 5)).toHaveLength(5);
  });
});

describe('navBadges', () => {
  it('counts each kind of unread notification under its own sidebar entry', () => {
    const b = navBadges([
      { notificationType: 'SERVICE_REQUEST_CREATED' },
      { notificationType: 'SERVICE_REQUEST_UPDATED' },
      { notificationType: 'ORDER_PLACED' },
      { notificationType: 'PAYMENT_SUCCESS' },
      { notificationType: 'FEEDBACK_RECEIVED' },
      { notificationType: 'RESTAURANT_APPROVED' },
    ]);
    expect(b).toEqual({ orders: 2, requests: 2, other: 2 });
  });

  it('is all zeros with nothing unread', () => {
    expect(navBadges([])).toEqual({ orders: 0, requests: 0, other: 0 });
  });
});
