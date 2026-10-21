import type { Me, PlatformRealtimeEvent, RealtimeMessage, RestaurantStatus, RoleName } from '../api/types';
import { money } from './format';
import { FLOOR_ROLES, KITCHEN_ROLES, MANAGER_ROLES, PLATFORM_ROLES } from './staff';
import { roleHears, ruleForEvent, serviceKindFor, type EventRule, type EventTone } from './sound/eventMap';

/*
 * The decisions behind the notification centre, kept free of React, sockets and audio so each one can be
 * unit tested: which live messages may be heard, what to show after a reconnect, what a change of
 * restaurant status means, and how toasts stack.
 */

/* ---------- who listens to what ---------- */

export type NotificationScope = 'restaurant' | 'platform' | 'none';

const RESTAURANT_ROLES: RoleName[] = [...new Set([...MANAGER_ROLES, ...KITCHEN_ROLES, ...FLOOR_ROLES])];

/** Whether this signed-in person works for a restaurant (whatever its approval state). */
export function isRestaurantStaff(user: Me | null): boolean {
  return user !== null && user.restaurantId !== null && RESTAURANT_ROLES.includes(user.role);
}

/**
 * Which topic to subscribe to. A restaurant that is not approved cannot subscribe (the server refuses),
 * so nothing is opened for it; an account with no status at all (older server) is treated as approved.
 */
export function notificationScope(user: Me | null): NotificationScope {
  if (user === null) return 'none';
  if (PLATFORM_ROLES.includes(user.role)) return 'platform';
  if (!isRestaurantStaff(user)) return 'none';
  const status = user.restaurantStatus;
  return status === undefined || status === null || status === 'APPROVED' ? 'restaurant' : 'none';
}

/** Where "Open order" goes for a role, or null when the role has no order screen. */
export function orderRouteFor(role: RoleName): string | null {
  if (MANAGER_ROLES.includes(role)) return '/admin/orders';
  if (KITCHEN_ROLES.includes(role)) return '/kitchen';
  if (FLOOR_ROLES.includes(role)) return '/floor';
  return null;
}

/* ---------- wire shapes ---------- */

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null;
const optionalString = (v: unknown): boolean => v === undefined || v === null || typeof v === 'string';

export function isRealtimeMessage(value: unknown): value is RealtimeMessage {
  return (
    isRecord(value) &&
    // A stored notification has a numeric id; a transient one (a service request update) has none.
    (value.notificationId === undefined ||
      value.notificationId === null ||
      (typeof value.notificationId === 'number' && Number.isFinite(value.notificationId))) &&
    typeof value.restaurantId === 'number' &&
    typeof value.notificationType === 'string' &&
    typeof value.title === 'string' &&
    typeof value.message === 'string' &&
    optionalString(value.orderNumber) &&
    optionalString(value.orderTotal) &&
    optionalString(value.eventKind)
  );
}

export function isPlatformEvent(value: unknown): value is PlatformRealtimeEvent {
  return (
    isRecord(value) &&
    typeof value.eventId === 'string' &&
    value.eventId !== '' &&
    typeof value.type === 'string' &&
    typeof value.title === 'string' &&
    typeof value.message === 'string'
  );
}

/* ---------- de-duplication ---------- */

/** A bounded set of ids already handled. The oldest are forgotten first, so it cannot grow for ever. */
export interface SeenIds<T> {
  has: (id: T) => boolean;
  add: (id: T) => void;
  clear: () => void;
  size: () => number;
}

/** Room for a very busy day of events; each entry is one number, so the cost of a large limit is tiny. */
export const SEEN_LIMIT = 2000;

export function createSeenIds<T extends string | number>(limit: number = SEEN_LIMIT): SeenIds<T> {
  const ids = new Set<T>();
  return {
    has: (id) => ids.has(id),
    add(id) {
      if (ids.has(id)) return;
      ids.add(id);
      if (ids.size > limit) {
        const oldest = ids.values().next();
        if (!oldest.done) ids.delete(oldest.value);
      }
    },
    clear: () => ids.clear(),
    size: () => ids.size,
  };
}

/** The highest notification id among those already stored, or 0 when there are none. */
export function baselineFrom(items: readonly { id: number }[]): number {
  return items.reduce((max, n) => (Number.isFinite(n.id) && n.id > max ? n.id : max), 0);
}

export type MessageVerdict =
  /** Not for this restaurant (or malformed): ignored without a trace. */
  | 'foreign'
  /** Already handled once. */
  | 'duplicate'
  /** Older than what was already stored when we started: history, never heard or shown. */
  | 'historical'
  /** The baseline is not known yet, so nothing can be told apart from history. */
  | 'not-ready'
  /** New: show it and, if the preferences allow, play its sound. */
  | 'live';

/**
 * Judges one message from the restaurant topic. `baseline` is the highest id stored before we started
 * listening (null until known). Marks the id as seen when it is historical or live.
 *
 * The baseline is deliberately never raised: ids are strictly increasing in the database, but two
 * transactions can commit out of order, so a lower id may legitimately arrive after a higher one. The
 * seen set is what stops repeats.
 */
export function decideRestaurantMessage(
  message: RealtimeMessage,
  ownRestaurantId: number,
  baseline: number | null,
  seen: SeenIds<number>,
): MessageVerdict {
  if (message.restaurantId !== ownRestaurantId) return 'foreign';
  const id = message.notificationId;
  // Transient messages are never stored and the socket never replays, so one that arrives is happening now.
  if (id === undefined || id === null) return 'live';
  if (baseline === null) return 'not-ready';
  if (seen.has(id)) return 'duplicate';
  seen.add(id);
  return id > baseline ? 'live' : 'historical';
}

export interface LivePlan {
  verdict: MessageVerdict;
  /** The sound rule for the event (structured kind first, text only for an older server). */
  rule: EventRule;
  /** Whether this signed-in role should HEAR it; the toast is shown regardless. */
  hears: boolean;
  /** Names the event across tabs and in the dedupe sets. */
  key: string;
}

/** Everything the dashboard decides about one live message, in one pure step (so it can be tested end to end). */
export function planLiveMessage(
  message: RealtimeMessage,
  ownRestaurantId: number,
  baseline: number | null,
  seen: SeenIds<number>,
  role: RoleName | null,
): LivePlan {
  const info = { eventKind: message.eventKind, text: message.message };
  return {
    verdict: decideRestaurantMessage(message, ownRestaurantId, baseline, seen),
    rule: ruleForEvent(message.notificationType, info),
    hears: roleHears(role, message.notificationType, info),
    key: messageKey(message),
  };
}

/** Names a message across tabs: its database id, or for a transient one its timestamp and text. */
export function messageKey(message: RealtimeMessage): string {
  const id = message.notificationId;
  return id === undefined || id === null
    ? `r${message.restaurantId}:t${message.createdAt}:${message.notificationType}:${message.title}`
    : `r${message.restaurantId}:n${id}`;
}

/** Platform events have no database id; the UUID in `eventId` is the identity. */
export function decidePlatformEvent(event: PlatformRealtimeEvent, seen: SeenIds<string>): 'duplicate' | 'live' {
  if (seen.has(event.eventId)) return 'duplicate';
  seen.add(event.eventId);
  return 'live';
}

/* ---------- catching up after being away ---------- */

export interface CatchUpItem {
  id: number;
  title: string;
  message: string;
  notificationType?: string;
  eventKind?: string | null;
  createdAt?: string;
}

export interface CatchUpSummary {
  count: number;
  /** Titles of the newest few, newest first. */
  newest: string[];
  text: string;
  /** The newest new order placed within CATCH_UP_SOUND_WINDOW_MS (the one thing worth a chime after a gap), else null. */
  recentOrderId: number | null;
  /** Likewise for a waiter call (by structured kind, or by text on an older server). */
  recentWaiterCallId: number | null;
}

export const CATCH_UP_LISTED = 3;
/** A missed order younger than this still chimes when the connection catches up; older ones never do. */
export const CATCH_UP_SOUND_WINDOW_MS = 2 * 60_000;

function isRecent(createdAt: string | undefined, now: number): boolean {
  if (!createdAt) return false;
  const at = Date.parse(createdAt);
  return Number.isFinite(at) && now - at >= 0 && now - at <= CATCH_UP_SOUND_WINDOW_MS;
}

/**
 * Compares the server's unread list with what this tab has already handled. Everything newer than the
 * baseline that was not seen becomes ONE summary (a sound only if it reports a very recent new order), and is marked seen so the live socket
 * cannot announce it again. Returns null when nothing is new.
 */
export function planCatchUp(
  unread: readonly CatchUpItem[],
  baseline: number,
  seen: SeenIds<number>,
  now: number = Date.now(),
): CatchUpSummary | null {
  const fresh = unread.filter((n) => n.id > baseline && !seen.has(n.id)).sort((a, b) => b.id - a.id);
  if (fresh.length === 0) return null;
  fresh.forEach((n) => seen.add(n.id));
  return {
    count: fresh.length,
    newest: fresh.slice(0, CATCH_UP_LISTED).map((n) => n.title),
    text: `While you were away: ${fresh.length} new notification${fresh.length === 1 ? '' : 's'}`,
    recentOrderId: fresh.find((n) => n.notificationType === 'ORDER_PLACED' && isRecent(n.createdAt, now))?.id ?? null,
    recentWaiterCallId:
      fresh.find(
        (n) =>
          n.notificationType === 'SERVICE_REQUEST_CREATED' &&
          serviceKindFor({ eventKind: n.eventKind, text: n.message }) === 'WAITER_CALL' &&
          isRecent(n.createdAt, now),
      )?.id ?? null,
  };
}

/* ---------- restaurant status decisions ---------- */

/**
 * What a change of the signed-in user's restaurant status means, as a notification type. A status
 * seen for the first time (no previous one) is not a change.
 */
export function detectStatusTransition(
  previous: RestaurantStatus | null | undefined,
  next: RestaurantStatus | null | undefined,
): string | null {
  if (!previous || !next || previous === next) return null;
  switch (next) {
    case 'APPROVED':
      return previous === 'SUSPENDED' ? 'RESTAURANT_REACTIVATED' : 'RESTAURANT_APPROVED';
    case 'SUSPENDED':
      return 'RESTAURANT_SUSPENDED';
    case 'REJECTED':
      return 'RESTAURANT_REJECTED';
    default:
      return null;
  }
}

export const DECISION_DEDUPE_MS = 15_000;

/**
 * One decision about a restaurant must not sound twice when both the socket and the /me refresh report
 * it. `claim` is true the first time a type is seen inside the window.
 */
export function createDecisionDeduper(windowMs: number = DECISION_DEDUPE_MS) {
  const last = new Map<string, number>();
  return {
    claim(type: string, now: number): boolean {
      const at = last.get(type);
      if (at !== undefined && now - at < windowMs) return false;
      last.set(type, now);
      return true;
    },
    clear: () => last.clear(),
  };
}

export function statusEventCopy(type: string, reason: string | null | undefined): { title: string; message: string } {
  const why = reason && reason.trim() ? ` Reason: ${reason.trim()}` : '';
  switch (type) {
    case 'RESTAURANT_APPROVED':
      return { title: 'Restaurant approved', message: 'Your restaurant is approved. You can set up your menu and start taking orders.' };
    case 'RESTAURANT_REACTIVATED':
      return { title: 'Restaurant reactivated', message: 'Your restaurant is active again.' };
    case 'RESTAURANT_REJECTED':
      return { title: 'Registration not approved', message: `Your restaurant could not be approved.${why}` };
    case 'RESTAURANT_SUSPENDED':
      return { title: 'Restaurant suspended', message: `Access to your restaurant has been paused.${why}` };
    default:
      return { title: 'Notification', message: '' };
  }
}

/* ---------- query invalidation ---------- */

/** Every list a restaurant event can change. Only queries that are mounted refetch; the rest go stale. */
export const RESTAURANT_QUERY_KEYS: readonly (readonly string[])[] = [
  ['orders'],
  ['order-payment-summary'],
  ['order-history'],
  ['kitchen-orders'],
  ['kitchen-orders-today'],
  ['notifications'],
  ['dashboard'],
  ['service-requests'],
  ['service-requests-board'],
  ['reminder-sync'],
  ['service-request'],
  ['live-tables'],
  ['live-tables-recent'],
  ['live-table'],
  ['live-settlements'],
  ['payment-overview'],
  ['payment-transactions'],
  ['reconciliation'],
  ['feedback'],
  ['feedback-summary'],
  ['inventory'],
];

export const PLATFORM_QUERY_KEYS: readonly (readonly string[])[] = [['platform']];

/* ---------- toasts ---------- */

export const MAX_TOASTS = 4;
export const ORDER_TOAST_MS = 15_000;
export const DEFAULT_TOAST_MS = 6_000;
/** A waiter call or bill request stays up until someone dismisses it or this long passes (it is still in the bell and on the board). */
export const REQUEST_TOAST_MS = 60_000;

export interface ToastAction {
  label: string;
  to: string;
}

export interface ToastInput {
  /** Toasts with the same key merge into one with a count. */
  key: string;
  tone: EventTone;
  title: string;
  body: string | null;
  /** Extra lines, for example the newest items of a catch-up summary. */
  lines?: string[];
  action?: ToastAction | null;
  ttlMs: number;
}

export interface ToastItem extends ToastInput {
  id: string;
  count: number;
  /** Changes when the toast is refreshed, so its timer restarts. */
  stamp: number;
}

export interface ToastState {
  items: ToastItem[];
  /** Toasts pushed out by newer ones while the list was full. */
  hidden: number;
}

export const EMPTY_TOASTS: ToastState = { items: [], hidden: 0 };

export function pushToast(state: ToastState, input: ToastInput, id: string, max: number = MAX_TOASTS): ToastState {
  const existing = state.items.findIndex((t) => t.key === input.key);
  if (existing !== -1) {
    const merged: ToastItem = { ...state.items[existing], ...input, count: state.items[existing].count + 1, stamp: state.items[existing].stamp + 1 };
    const rest = state.items.filter((_, i) => i !== existing);
    return { items: [...rest, merged], hidden: state.hidden };
  }
  const items = [...state.items, { ...input, id, count: 1, stamp: 0 }];
  const overflow = Math.max(0, items.length - max);
  return { items: items.slice(overflow), hidden: state.hidden + overflow };
}

export function dismissToast(state: ToastState, id: string): ToastState {
  const items = state.items.filter((t) => t.id !== id);
  return { items, hidden: items.length === 0 ? 0 : state.hidden };
}

/** "450.00" to the app's money format; null when it is missing or not a number. */
export function formatOrderTotal(total: string | null | undefined): string | null {
  if (total === null || total === undefined || total.trim() === '') return null;
  const n = Number(total);
  return Number.isFinite(n) ? money(n) : null;
}

/** The toast for a message from the restaurant topic. */
export function toastForMessage(message: RealtimeMessage, label: string, tone: EventTone, role: RoleName): ToastInput {
  const key = `${message.notificationType}:${message.orderId ?? 'none'}`;
  if (message.notificationType === 'ORDER_PLACED') {
    const total = formatOrderTotal(message.orderTotal);
    const parts = [message.orderNumber ? `Order ${message.orderNumber}` : null, total].filter((p): p is string => p !== null);
    const route = orderRouteFor(role);
    return {
      key,
      tone,
      title: 'New Order Received!',
      body: parts.length > 0 ? parts.join(' · ') : message.message || null,
      action: route ? { label: 'Open order', to: route } : null,
      ttlMs: ORDER_TOAST_MS,
    };
  }
  if (message.notificationType === 'SERVICE_REQUEST_CREATED') {
    const kind = serviceKindFor({ eventKind: message.eventKind, text: message.message });
    if (kind !== 'OTHER') {
      // One toast PER request: ten tables calling at once must show ten tables, not one "x10" with the last name.
      const table = message.title.includes('·') ? message.title.slice(message.title.indexOf('·') + 1).trim() : '';
      return {
        key: `svc:${kind}:${table || message.title}`,
        tone,
        title: table ? `${label} · ${table}` : label,
        body: message.message || null,
        action: { label: 'Open requests', to: '/requests' },
        ttlMs: REQUEST_TOAST_MS,
      };
    }
  }
  return {
    key,
    tone,
    title: message.title || label,
    body: message.message || null,
    action: null,
    ttlMs: DEFAULT_TOAST_MS,
  };
}

/* ---------- coalescing refreshes ---------- */

export interface Coalescer {
  /** Runs `fn` now if it has been quiet, otherwise once more when the window ends. */
  trigger: () => void;
  cancel: () => void;
}

/**
 * A burst of live messages must not become a burst of refetches (each message used to refresh a dozen lists).
 * The first trigger runs at once, so a single event is as fast as before; triggers inside the window collapse
 * into ONE trailing run when it ends, so the lists end up current without a request per event.
 */
export function createCoalescer(
  fn: () => void,
  windowMs: number,
  deps: {
    now: () => number;
    setTimer: (f: () => void, ms: number) => unknown;
    clearTimer: (h: unknown) => void;
  },
): Coalescer {
  let last = -Infinity;
  let timer: unknown = null;
  let dirty = false;

  function run() {
    last = deps.now();
    dirty = false;
    fn();
  }

  return {
    trigger() {
      const wait = last + windowMs - deps.now();
      if (timer === null && wait <= 0) {
        run();
        return;
      }
      dirty = true;
      if (timer === null) {
        timer = deps.setTimer(() => {
          timer = null;
          if (dirty) run();
        }, Math.max(0, wait));
      }
    },
    cancel() {
      if (timer !== null) deps.clearTimer(timer);
      timer = null;
      dirty = false;
    },
  };
}
