import type { RoleName } from '../../api/types';
import { MANAGER_ROLES } from '../staff';
import type { EffectiveGroup } from './soundPrefs';
import type { SoundKind } from './soundDefs';

/** How a toast is styled. Failures use 'error'. */
export type EventTone = 'info' | 'ok' | 'warn' | 'error';

export interface EventRule {
  /** The sound to play; null for a visual-only event. */
  sound: SoundKind | null;
  /** Which preference switches it. */
  group: EffectiveGroup;
  /** Higher wins when several events arrive together. */
  priority: number;
  label: string;
  /** True for events that are shown but never heard. */
  silent?: boolean;
  tone: EventTone;
}

const silent = (label: string, tone: EventTone = 'info'): EventRule => ({
  sound: null,
  group: 'general',
  priority: 0,
  label,
  silent: true,
  tone,
});

/**
 * notificationType (and the platform event type) to what the app does with it. Order progress that
 * staff caused with their own taps (confirmed, preparing, served) and coupon use are silent: a sound on
 * each of those would only be noise.
 */
export const EVENT_RULES: Readonly<Record<string, EventRule>> = {
  ORDER_PLACED: { sound: 'NEW_ORDER', group: 'newOrder', priority: 100, label: 'New order', tone: 'ok' },
  ORDER_CONFIRMED: silent('Order confirmed'),
  ORDER_PREPARING: silent('Order being prepared'),
  ORDER_READY: { sound: 'ORDER_READY', group: 'orderReady', priority: 45, label: 'Order ready', tone: 'ok' },
  ORDER_SERVED: silent('Order served'),
  ORDER_CANCELLED: {
    sound: 'ORDER_CANCELLED',
    group: 'cancellations',
    priority: 65,
    label: 'Order cancelled',
    tone: 'warn',
  },
  PAYMENT_SUCCESS: { sound: 'SUCCESS', group: 'payments', priority: 50, label: 'Payment received', tone: 'ok' },
  PAYMENT_FAILED: { sound: 'WARNING', group: 'payments', priority: 70, label: 'Payment failed', tone: 'error' },
  PAYMENT_REFUNDED: { sound: 'MESSAGE', group: 'payments', priority: 35, label: 'Payment refunded', tone: 'info' },
  COUPON_APPLIED: silent('Coupon applied'),
  SERVICE_REQUEST_CREATED: {
    sound: 'MESSAGE',
    group: 'messages',
    priority: 60,
    label: 'Table request',
    tone: 'info',
  },
  SERVICE_REQUEST_UPDATED: {
    sound: 'MESSAGE',
    group: 'general',
    priority: 15,
    label: 'Table request updated',
    tone: 'info',
  },
  FEEDBACK_RECEIVED: { sound: 'MESSAGE', group: 'messages', priority: 40, label: 'New feedback', tone: 'info' },
  RESTAURANT_APPROVED: {
    sound: 'SUCCESS',
    group: 'general',
    priority: 30,
    label: 'Restaurant approved',
    tone: 'ok',
  },
  RESTAURANT_REACTIVATED: {
    sound: 'SUCCESS',
    group: 'general',
    priority: 30,
    label: 'Restaurant reactivated',
    tone: 'ok',
  },
  RESTAURANT_REJECTED: {
    sound: 'CRITICAL',
    group: 'critical',
    priority: 90,
    label: 'Registration rejected',
    tone: 'error',
  },
  RESTAURANT_SUSPENDED: {
    sound: 'CRITICAL',
    group: 'critical',
    priority: 90,
    label: 'Restaurant suspended',
    tone: 'error',
  },
  // Platform topic (the platform owner's own preferences apply).
  PLATFORM_RESTAURANT_REGISTERED: {
    sound: 'MESSAGE',
    group: 'general',
    priority: 20,
    label: 'New restaurant registered',
    tone: 'info',
  },
};

const UNKNOWN: EventRule = silent('Notification');

/** The rule for a type; anything the app does not know is shown but never heard. */
export function ruleFor(type: string): EventRule {
  return Object.prototype.hasOwnProperty.call(EVENT_RULES, type) ? EVENT_RULES[type] : UNKNOWN;
}

/** Types that report a decision about the restaurant itself (also derived from /me when no socket exists). */
export const RESTAURANT_STATUS_TYPES: readonly string[] = [
  'RESTAURANT_APPROVED',
  'RESTAURANT_REJECTED',
  'RESTAURANT_SUSPENDED',
  'RESTAURANT_REACTIVATED',
];

/* ---------- table requests: waiter call and bill have their own sounds ---------- */

export type ServiceKind = 'WAITER_CALL' | 'BILL_REQUEST' | 'OTHER';

/** What the server says an event is (`eventKind`), plus the display text that older servers are read by. */
export interface EventInfo {
  eventKind?: string | null;
  text?: string | null;
}

const SERVICE_KINDS_BY_EVENT: Readonly<Record<string, ServiceKind>> = {
  WAITER_CALL: 'WAITER_CALL',
  BILL_REQUEST: 'BILL_REQUEST',
  TABLE_REQUEST: 'OTHER',
  TABLE_REQUEST_UPDATE: 'OTHER',
};

/**
 * Which kind of table request an event is. The structured `eventKind` decides whenever the server sent a
 * kind this app knows; changing the wording of a notification can therefore never change its sound. Only
 * an older server (no `eventKind`) falls back to the text prefix.
 */
export function serviceKindFor(info?: EventInfo): ServiceKind {
  const kind = info?.eventKind;
  if (typeof kind === 'string' && Object.prototype.hasOwnProperty.call(SERVICE_KINDS_BY_EVENT, kind)) {
    return SERVICE_KINDS_BY_EVENT[kind];
  }
  return serviceKindOf(info?.text);
}

/**
 * LEGACY: the kind read from the text the server writes first in the message ("Call waiter", "Bring the
 * bill", then an optional note). Used only when there is no `eventKind`; prefer `serviceKindFor`.
 */
export function serviceKindOf(message: string | null | undefined): ServiceKind {
  const text = (message ?? '').trimStart().toLowerCase();
  if (text.startsWith('call waiter') || text.startsWith('assistance')) return 'WAITER_CALL';
  if (text.startsWith('bring the bill')) return 'BILL_REQUEST';
  return 'OTHER';
}

const WAITER_RULE: EventRule = {
  sound: 'WAITER_CALL',
  group: 'waiterCalls',
  priority: 80,
  label: 'Waiter called',
  tone: 'warn',
};
const BILL_RULE: EventRule = {
  sound: 'BILL_REQUEST',
  group: 'billRequests',
  priority: 62,
  label: 'Bill requested',
  tone: 'info',
};

/** `ruleFor`, refined for table requests by the structured kind (or, on an older server, the message text). */
export function ruleForEvent(type: string, info?: EventInfo): EventRule {
  if (type === 'SERVICE_REQUEST_CREATED') {
    const kind = serviceKindFor(info);
    if (kind === 'WAITER_CALL') return WAITER_RULE;
    if (kind === 'BILL_REQUEST') return BILL_RULE;
  }
  return ruleFor(type);
}

/* ---------- who hears what ---------- */

const STATUS_DECISIONS = new Set<string>(RESTAURANT_STATUS_TYPES);

/**
 * Whether a signed-in role should HEAR (not merely see) this event. Everyone still gets the toast; only the
 * sound is filtered, so a chef is not interrupted by a table asking for water. Managers and owners hear all.
 */
export function roleHears(role: RoleName | null, type: string, info?: EventInfo): boolean {
  if (role === null || MANAGER_ROLES.includes(role) || role === 'PLATFORM_OWNER') return true;
  if (STATUS_DECISIONS.has(type)) return true;
  switch (role) {
    case 'CHEF':
      return type === 'ORDER_PLACED' || type === 'ORDER_CANCELLED';
    case 'WAITER':
      return type === 'SERVICE_REQUEST_CREATED' || type === 'SERVICE_REQUEST_UPDATED' || type === 'ORDER_READY';
    case 'CASHIER':
      return (
        type.startsWith('PAYMENT_') ||
        (type === 'SERVICE_REQUEST_CREATED' && serviceKindFor(info) === 'BILL_REQUEST')
      );
    default:
      return false;
  }
}
