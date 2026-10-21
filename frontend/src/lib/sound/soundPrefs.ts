/*
 * The user's sound preferences. There is no preferences store on the server, so they live in
 * localStorage, one record per signed-in user, and are validated on every read: anything missing,
 * the wrong type or from an older shape falls back field by field to the defaults.
 */

import { DEFAULT_THEME, isSoundTheme, type SoundTheme } from './soundDefs';

export type PrefGroup =
  | 'newOrder'
  | 'waiterCalls'
  | 'billRequests'
  | 'orderReady'
  | 'payments'
  | 'cancellations'
  | 'messages'
  | 'general';
/** 'critical' has no toggle of its own: it follows the master switch only. */
export type EffectiveGroup = PrefGroup | 'critical';

export interface SoundPrefs {
  master: boolean;
  newOrder: boolean;
  waiterCalls: boolean;
  billRequests: boolean;
  orderReady: boolean;
  payments: boolean;
  cancellations: boolean;
  messages: boolean;
  general: boolean;
  /** 0-100. */
  volume: number;
  theme: SoundTheme;
  /** Seconds between reminders for an unanswered waiter call, bill request or new order; 0 turns reminders off. */
  reminderSec: number;
}

/** The reminder intervals offered, in seconds (0 = off). */
export const REMINDER_CHOICES: readonly number[] = [0, 30, 60, 120, 300];
export const DEFAULT_REMINDER_SEC = 60;

export const PREF_GROUPS: readonly PrefGroup[] = [
  'newOrder',
  'waiterCalls',
  'billRequests',
  'orderReady',
  'payments',
  'cancellations',
  'messages',
  'general',
];

export const DEFAULT_VOLUME = 70;

export const DEFAULT_PREFS: SoundPrefs = {
  master: true,
  newOrder: true,
  waiterCalls: true,
  billRequests: true,
  orderReady: true,
  payments: true,
  cancellations: true,
  messages: true,
  general: true,
  volume: DEFAULT_VOLUME,
  theme: DEFAULT_THEME,
  reminderSec: DEFAULT_REMINDER_SEC,
};

/** Diners start with sound off: nothing should make noise on a guest's phone until they ask for it. */
export const GUEST_DEFAULT_PREFS: SoundPrefs = { ...DEFAULT_PREFS, master: false, volume: 35 };

export const GUEST_PREFS_KEY = 'kmenu.sound.v1.guest';

export function prefsKey(userId: number | string): string {
  return `kmenu.sound.v1.${userId}`;
}

/** The slice of Storage the helpers need, so tests can pass a plain object. */
export interface StorageLike {
  getItem: (key: string) => string | null;
  setItem: (key: string, value: string) => void;
}

/** localStorage or sessionStorage, or null where the browser blocks them (private modes, embedded views). */
export function safeStorage(kind: 'local' | 'session'): StorageLike | null {
  try {
    if (typeof window === 'undefined') return null;
    return kind === 'local' ? window.localStorage : window.sessionStorage;
  } catch {
    return null;
  }
}

export function clampVolume(value: unknown, fallback: number = DEFAULT_VOLUME): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
  return Math.min(100, Math.max(0, Math.round(value)));
}

/** Turns anything read from storage into valid prefs. Never throws. */
export function sanitizePrefs(raw: unknown, defaults: SoundPrefs = DEFAULT_PREFS): SoundPrefs {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return { ...defaults };
  const r = raw as Record<string, unknown>;
  const flag = (key: PrefGroup | 'master'): boolean =>
    typeof r[key] === 'boolean' ? r[key] : defaults[key];
  return {
    master: flag('master'),
    newOrder: flag('newOrder'),
    waiterCalls: flag('waiterCalls'),
    billRequests: flag('billRequests'),
    orderReady: flag('orderReady'),
    payments: flag('payments'),
    cancellations: flag('cancellations'),
    messages: flag('messages'),
    general: flag('general'),
    volume: clampVolume(r.volume, defaults.volume),
    theme: isSoundTheme(r.theme) ? r.theme : defaults.theme,
    reminderSec: REMINDER_CHOICES.includes(r.reminderSec as number) ? (r.reminderSec as number) : defaults.reminderSec,
  };
}

export function loadPrefs(
  storage: StorageLike | null,
  key: string,
  defaults: SoundPrefs = DEFAULT_PREFS,
): SoundPrefs {
  if (!storage) return { ...defaults };
  try {
    const text = storage.getItem(key);
    if (text === null) return { ...defaults };
    return sanitizePrefs(JSON.parse(text) as unknown, defaults);
  } catch {
    // Unreadable storage or invalid JSON.
    return { ...defaults };
  }
}

/** Returns false when the browser refused (private mode, quota): the change then lasts for this page only. */
export function savePrefs(storage: StorageLike | null, key: string, prefs: SoundPrefs): boolean {
  if (!storage) return false;
  try {
    storage.setItem(key, JSON.stringify(sanitizePrefs(prefs)));
    return true;
  } catch {
    return false;
  }
}

/**
 * Whether sounds of this group may play. The master switch silences everything, critical sounds
 * included; critical sounds have no toggle of their own.
 */
export function effective(prefs: SoundPrefs, group: EffectiveGroup): boolean {
  if (!prefs.master) return false;
  return group === 'critical' ? true : prefs[group];
}

/** `effective`, and the volume is above zero so there is something to hear. */
export function audible(prefs: SoundPrefs, group: EffectiveGroup): boolean {
  return effective(prefs, group) && prefs.volume > 0;
}
