import type { LandingActionKey } from '../api/types';
import type { RememberedOrder } from './orderHistory';

/**
 * Helpers for the QR landing page. Everything here that decides what the page may render or open is pure, so it
 * is tested. The server already validates every link; these are a second line of defence on the way out.
 */

/** A restaurant's brand colour as `#rrggbb`, or null. Anything else is ignored rather than put into CSS. */
export function safeColor(value: string | null | undefined): string | null {
  return typeof value === 'string' && /^#[0-9a-fA-F]{6}$/.test(value.trim()) ? value.trim() : null;
}

/** True only for an absolute https address with a host: the only kind the page will open. */
export function isSafeExternal(value: string | null | undefined): value is string {
  if (typeof value !== 'string' || value.length > 500) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname.includes('.') && !url.username && !url.password;
  } catch {
    return false;
  }
}

const OUR_PHOTO = /^\/api\/public\/images\/[0-9a-f]{32}$/;

/** A photo fit to show: one of this app's own uploads, or an https link. Same rule as the server's dish photos. */
export function isSafeImage(value: string | null | undefined): value is string {
  return typeof value === 'string' && (OUR_PHOTO.test(value) || isSafeExternal(value));
}

/** A `tel:` link from the server's already-cleaned number (digits and a leading +), else null. */
export function telHref(dial: string | null | undefined): string | null {
  return typeof dial === 'string' && /^\+?[0-9]{5,15}$/.test(dial) ? `tel:${dial}` : null;
}

export interface ActionText {
  title: string;
  hint: string;
}

/**
 * The cards this app still offers. A server may still list retired ones (such as anonymous feedback), so
 * anything not in ACTION_TEXT is dropped before it reaches the page.
 */
export function knownActions(actions: readonly string[] | null | undefined): LandingActionKey[] {
  return (actions ?? []).filter((a): a is LandingActionKey => Object.prototype.hasOwnProperty.call(ACTION_TEXT, a));
}

/** What each landing card says. The stored key never changes; only these words may. */
export const ACTION_TEXT: Record<LandingActionKey, ActionText> = {
  REWARDS: { title: 'Start earning rewards', hint: 'Points on every visit' },
  REVIEW: { title: 'Leave a Google Review', hint: 'Tell others about your visit' },
  MENU: { title: 'View Menu', hint: 'Browse and order from your table' },
  WIFI: { title: 'Connect to Wi-Fi', hint: 'Guest network' },
  ASSISTANCE: { title: 'Call a waiter', hint: 'Water, cutlery, the bill…' },
};

/** A guest secret made of 48 hex characters from the platform's own random source. */
export function newRewardSecret(
  fill: (bytes: Uint8Array<ArrayBuffer>) => unknown = (b) => crypto.getRandomValues(b),
): string {
  const bytes = new Uint8Array(24);
  fill(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

const secretKey = (restaurantId: number) => `kmenu.reward.${restaurantId}`;

/** This device's rewards secret for one restaurant, made on first use. Never sent anywhere except the rewards calls. */
export function rewardSecretFor(restaurantId: number): string {
  try {
    const existing = localStorage.getItem(secretKey(restaurantId));
    if (existing && /^[0-9a-f]{48}$/.test(existing)) return existing;
    const made = newRewardSecret();
    localStorage.setItem(secretKey(restaurantId), made);
    return made;
  } catch {
    // Storage blocked: a secret that lasts only this page view still lets the guest look, though not keep a balance.
    return newRewardSecret();
  }
}

export function hasRewardSecret(restaurantId: number): boolean {
  try {
    return /^[0-9a-f]{48}$/.test(localStorage.getItem(secretKey(restaurantId)) ?? '');
  } catch {
    return false;
  }
}

/** Orders on this device that carry a tracking token, newest first: the only ones a guest can claim points for. */
export function claimableOrders(orders: RememberedOrder[]): (RememberedOrder & { token: string })[] {
  return orders.filter((o): o is RememberedOrder & { token: string } => typeof o.token === 'string' && o.token !== '');
}

const landedKey = (qr: string) => `kmenu.landed.${qr}`;

/** Once a guest has chosen to go on to the menu this visit, the QR address opens the menu directly. */
export function hasLanded(qr: string): boolean {
  try {
    return sessionStorage.getItem(landedKey(qr)) === '1';
  } catch {
    return false;
  }
}

export function markLanded(qr: string): void {
  try {
    sessionStorage.setItem(landedKey(qr), '1');
  } catch {
    // private mode: the landing page simply shows again, which is harmless
  }
}
