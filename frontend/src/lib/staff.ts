import type { RoleName } from '../api/types';

/*
 * Mirrors the server's staff rules so the screen offers only what the server would allow. The server
 * enforces every rule again regardless; nothing here is a security boundary.
 */

export const MANAGER_ROLES: RoleName[] = ['ADMIN', 'RESTAURANT_OWNER', 'MANAGER'];
export const KITCHEN_ROLES: RoleName[] = [...MANAGER_ROLES, 'CHEF', 'WAITER'];
/** Who may see the live tables, confirm payments and close tables: management and the floor staff. */
export const FLOOR_ROLES: RoleName[] = [...MANAGER_ROLES, 'WAITER', 'CASHIER'];
/** The platform owner (super admin) who approves restaurant registrations. Not tied to any restaurant. */
export const PLATFORM_ROLES: RoleName[] = ['PLATFORM_OWNER'];

/** Where a role lands after signing in. */
export function homeFor(role: RoleName): string {
  if (PLATFORM_ROLES.includes(role)) return '/platform-admin';
  if (MANAGER_ROLES.includes(role)) return '/admin';
  if (KITCHEN_ROLES.includes(role)) return '/kitchen';
  if (role === 'CASHIER') return '/floor';
  return '/';
}

/**
 * Where to send someone who has just signed in. The page they were turned away from is honoured,
 * except across the platform boundary: a different account signing in after a sign-out must not be
 * sent to the other area (the platform owner to /admin, or a restaurant account to /platform-admin),
 * where it would only meet "No access".
 */
export function landingAfterLogin(from: string | undefined, role: RoleName): string {
  const home = homeFor(role);
  if (!from) return home;
  const inPlatformArea = from === '/platform-admin' || from.startsWith('/platform-admin/');
  return inPlatformArea === PLATFORM_ROLES.includes(role) ? from : home;
}

export const ROLE_LABELS: Partial<Record<RoleName, string>> = {
  PLATFORM_OWNER: 'Platform owner',
  RESTAURANT_OWNER: 'Owner',
  ADMIN: 'Admin',
  MANAGER: 'Manager',
  CHEF: 'Chef',
  WAITER: 'Waiter',
  CASHIER: 'Cashier',
};

export const roleLabel = (role: RoleName): string => ROLE_LABELS[role] ?? role;

/** What the role does, in a sentence for the person choosing it. */
export const ROLE_HELP: Partial<Record<RoleName, string>> = {
  MANAGER: 'Runs the restaurant day to day: menu, tables, staff, reports. Cannot manage other managers.',
  CHEF: 'Works the kitchen board and table requests.',
  WAITER: 'Works the kitchen board, live tables, payments and table requests.',
  CASHIER: 'Works live tables, payments and table requests.',
};

/** The roles a signed-in person may give to someone. Owners and admins: all four; managers: not manager. */
export function assignableRoles(actor: RoleName | undefined): RoleName[] {
  if (actor === 'RESTAURANT_OWNER' || actor === 'ADMIN') return ['MANAGER', 'CHEF', 'WAITER', 'CASHIER'];
  if (actor === 'MANAGER') return ['CHEF', 'WAITER', 'CASHIER'];
  return [];
}

/** A sentence about what is wrong with a password, or null. Same rules as the server. */
export function passwordProblem(password: string, email: string, name: string): string | null {
  if (!password.trim()) return 'Please enter a password.';
  if (password.length < 8) return 'The password must be at least 8 characters.';
  if (password.length > 100) return 'The password must be at most 100 characters.';
  if (!/\p{L}/u.test(password) || !/\p{N}/u.test(password)) {
    return 'The password must contain at least one letter and one number.';
  }
  const lowered = password.toLowerCase();
  const e = email.toLowerCase().trim();
  if (e && (lowered === e || lowered === e.split('@')[0])) return 'The password must not be the same as the email address.';
  if (name.trim() && lowered === name.toLowerCase().trim()) return 'The password must not be the same as the name.';
  return null;
}

// No 0/O, 1/l/I: a password read out loud or copied from a screen is not misread.
const LETTERS = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz';
const DIGITS = '23456789';

/**
 * A random 12-character password that always contains a letter and a digit. `random` returns an
 * integer in [0, max); the default uses the browser's cryptographic generator, not Math.random.
 */
export function generatePassword(random: (max: number) => number = secureInt): string {
  const all = LETTERS + DIGITS;
  const chars = [LETTERS[random(LETTERS.length)], DIGITS[random(DIGITS.length)]];
  while (chars.length < 12) chars.push(all[random(all.length)]);
  // Fisher-Yates, so the guaranteed letter and digit are not always first.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = random(i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}

function secureInt(max: number): number {
  // Rejection sampling: no modulo bias.
  const limit = Math.floor(0x1_0000_0000 / max) * max;
  const buf = new Uint32Array(1);
  let value: number;
  do {
    crypto.getRandomValues(buf);
    value = buf[0];
  } while (value >= limit);
  return value % max;
}
