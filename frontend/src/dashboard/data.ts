import type {
  AnalyticsOverview,
  DashboardSummary,
  Order,
  OrderHistoryEntry,
  RestaurantTable,
  StaffServiceRequest,
  TableSessionSummary,
} from '../api/types';
import { bucketLabel } from '../lib/charts';
import { parseServerTime } from '../lib/format';

/*
 * Everything the dashboard shows that has to be worked out from API responses, kept free of React so it can
 * be unit tested. Panels receive plain, already-consistent numbers.
 */

/* ---------- orders by status ---------- */

export type StatusKey = 'new' | 'preparing' | 'ready' | 'served';

export interface StatusSlice {
  key: StatusKey;
  label: string;
  value: number;
  percent: number;
}

/** The four live statuses from the dashboard summary. The centre figure is their sum, so it always matches the legend. */
export function statusSlices(summary: Pick<DashboardSummary, 'pendingOrders' | 'preparingOrders' | 'readyOrders' | 'servedOrders'>): {
  total: number;
  slices: StatusSlice[];
} {
  const raw: [StatusKey, string, number][] = [
    ['new', 'New', summary.pendingOrders],
    ['preparing', 'Preparing', summary.preparingOrders],
    ['ready', 'Ready', summary.readyOrders],
    ['served', 'Served', summary.servedOrders],
  ];
  const total = raw.reduce((n, [, , v]) => n + v, 0);
  return {
    total,
    slices: raw.map(([key, label, value]) => ({
      key,
      label,
      value,
      percent: total === 0 ? 0 : Math.round((value / total) * 100),
    })),
  };
}

/* ---------- popular items ---------- */

export interface PopularItem {
  name: string;
  menuItemId: number | null;
  /** Units ordered. */
  count: number;
  /** Share of all units in the orders considered. */
  percent: number;
}

/**
 * The most ordered dishes in the given orders (cancelled orders do not count). Percentages are shares of
 * every unit ordered, so they add up to at most 100.
 */
export function popularItems(orders: readonly Order[], limit = 4): PopularItem[] {
  const byName = new Map<string, PopularItem>();
  let totalUnits = 0;
  for (const order of orders) {
    if (order.status === 'CANCELLED') continue;
    for (const item of order.items ?? []) {
      totalUnits += item.quantity;
      const existing = byName.get(item.itemName);
      if (existing) existing.count += item.quantity;
      else byName.set(item.itemName, { name: item.itemName, menuItemId: item.menuItemId, count: item.quantity, percent: 0 });
    }
  }
  return [...byName.values()]
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
    .slice(0, limit)
    .map((p) => ({ ...p, percent: totalUnits === 0 ? 0 : Math.round((p.count / totalUnits) * 100) }));
}

/* ---------- table status ---------- */

export type TableStateKey = 'occupied' | 'available' | 'requested' | 'offline';

export interface TableStateSlice {
  key: TableStateKey;
  label: string;
  count: number;
  percent: number;
}

/**
 * Every table lands in exactly one state, so the counts add up to the total: switched off is offline; otherwise a
 * table with an open waiter/water/bill request is "requested"; otherwise one with an open visit is "occupied";
 * everything else is available.
 */
export function tableStates(
  tables: readonly Pick<RestaurantTable, 'id' | 'tableName' | 'active'>[],
  sessions: readonly Pick<TableSessionSummary, 'tableName' | 'status'>[],
  requests: readonly Pick<StaffServiceRequest, 'tableId' | 'status'>[],
): { total: number; slices: TableStateSlice[] } {
  const openSessions = new Set(sessions.filter((s) => s.status === 'OPEN').map((s) => s.tableName));
  const requesting = new Set(
    requests.filter((r) => r.status !== 'COMPLETED' && r.status !== 'CANCELLED').map((r) => r.tableId),
  );
  const counts: Record<TableStateKey, number> = { occupied: 0, available: 0, requested: 0, offline: 0 };
  for (const t of tables) {
    if (!t.active) counts.offline += 1;
    else if (requesting.has(t.id)) counts.requested += 1;
    else if (openSessions.has(t.tableName)) counts.occupied += 1;
    else counts.available += 1;
  }
  const total = tables.length;
  const label: Record<TableStateKey, string> = {
    occupied: 'Occupied',
    available: 'Available',
    requested: 'Requested',
    offline: 'Offline',
  };
  return {
    total,
    slices: (['occupied', 'available', 'requested', 'offline'] as TableStateKey[]).map((key) => ({
      key,
      label: label[key],
      count: counts[key],
      percent: total === 0 ? 0 : Math.round((counts[key] / total) * 100),
    })),
  };
}

/* ---------- revenue chart ---------- */

export interface RevenuePoint {
  label: string;
  revenue: number;
  orders: number;
}

export type ChartMetric = 'revenue' | 'orders';

/** One point per bucket: money and payments summed over every payment method. */
export function revenuePoints(overview: Pick<AnalyticsOverview, 'series'>): RevenuePoint[] {
  const { buckets, rows, granularity } = overview.series;
  return buckets.map((bucket, i) => ({
    label: bucketLabel(bucket, granularity),
    revenue: rows.reduce((n, r) => n + (r.amounts[i] ?? 0), 0),
    orders: rows.reduce((n, r) => n + (r.counts[i] ?? 0), 0),
  }));
}

/* ---------- live orders ---------- */

/** When an order was placed: the time of its first status entry, or null when its history is not known. */
export function placedAt(history: readonly OrderHistoryEntry[] | undefined): string | null {
  if (!history || history.length === 0) return null;
  const first = [...history].sort((a, b) => (parseServerTime(a.at)?.getTime() ?? 0) - (parseServerTime(b.at)?.getTime() ?? 0))[0];
  return first.at;
}

/** "Today, 10:42 PM" for today, "14 Oct, 10:42 PM" otherwise, in the viewer's zone. */
export function placedLabel(iso: string | null, now: Date = new Date()): string {
  const at = parseServerTime(iso);
  if (!at) return '';
  const time = new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit', hour12: true }).format(at).toUpperCase();
  const sameDay = at.toDateString() === now.toDateString();
  if (sameDay) return `Today, ${time}`;
  return `${new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short' }).format(at)}, ${time}`;
}

/** Order statuses as the badge on a row: label and colour family. */
export function orderBadge(status: Order['status']): { label: string; tone: 'green' | 'blue' | 'orange' | 'red' | 'gray' } {
  switch (status) {
    case 'PENDING':
      return { label: 'New', tone: 'blue' };
    case 'CONFIRMED':
      return { label: 'Confirmed', tone: 'blue' };
    case 'PREPARING':
      return { label: 'Preparing', tone: 'orange' };
    case 'READY':
      return { label: 'Ready', tone: 'blue' };
    case 'SERVED':
      return { label: 'Served', tone: 'green' };
    case 'CANCELLED':
      return { label: 'Cancelled', tone: 'red' };
    default:
      return { label: status, tone: 'gray' };
  }
}

/* ---------- greeting and search ---------- */

export function greeting(now: Date): string {
  const h = now.getHours();
  if (h < 5) return 'Good night';
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}

export interface SearchEntry {
  label: string;
  hint: string;
  to: string;
}

/** Case-insensitive match on the label or hint; the best (prefix) matches first; at most `limit`. */
export function searchEntries(entries: readonly SearchEntry[], query: string, limit = 8): SearchEntry[] {
  const q = query.trim().toLowerCase();
  if (q === '') return [];
  const scored: { entry: SearchEntry; rank: number }[] = [];
  for (const entry of entries) {
    const label = entry.label.toLowerCase();
    const hint = entry.hint.toLowerCase();
    if (label.startsWith(q)) scored.push({ entry, rank: 0 });
    else if (label.includes(q)) scored.push({ entry, rank: 1 });
    else if (hint.includes(q)) scored.push({ entry, rank: 2 });
  }
  return scored.sort((a, b) => a.rank - b.rank).slice(0, limit).map((s) => s.entry);
}

/* ---------- sidebar badges ---------- */

export interface NavBadges {
  /** Unread about orders and payments. */
  orders: number;
  /** Unread table requests (call waiter, water, bill). */
  requests: number;
  /** Everything else (guest feedback, restaurant decisions). */
  other: number;
}

/**
 * Splits the unread notifications by what they are about, so each sidebar entry counts only its own: a guest
 * calling a waiter is a table request, not an order.
 */
export function navBadges(unread: readonly { notificationType: string }[]): NavBadges {
  const out: NavBadges = { orders: 0, requests: 0, other: 0 };
  for (const n of unread) {
    const t = n.notificationType;
    if (t.startsWith('SERVICE_REQUEST')) out.requests += 1;
    else if (t.startsWith('ORDER_') || t.startsWith('PAYMENT_') || t.startsWith('COUPON_')) out.orders += 1;
    else out.other += 1;
  }
  return out;
}
