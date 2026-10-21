import type {
  Me,
  PlatformSortBy,
  RestaurantStatus,
  SortDirection,
} from '../api/types';
import { parseServerTime } from './format';

/*
 * Pure rules for the platform owner's approval screens and for the restaurant owner's status gate.
 * They mirror the server's state machine so the UI offers only what the server would accept; the
 * server enforces every rule again regardless, nothing here is a security boundary.
 */

export const RESTAURANT_STATUSES: RestaurantStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'];

export const STATUS_LABELS: Record<RestaurantStatus, string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SUSPENDED: 'Suspended',
};

export const statusLabel = (status: RestaurantStatus): string => STATUS_LABELS[status];

/** Badge colour class (see .badge-* in styles.css). Colour is never the only cue: the label is always shown. */
export const STATUS_BADGE_CLASS: Record<RestaurantStatus, string> = {
  PENDING: 'badge-amber',
  APPROVED: 'badge-green',
  REJECTED: 'badge-red',
  SUSPENDED: 'badge-gray',
};

export const statusBadgeClass = (status: RestaurantStatus): string => STATUS_BADGE_CLASS[status];

export type DecisionAction = 'approve' | 'reject' | 'suspend' | 'reactivate';

/** The decisions that are valid from each status. PENDING/REJECTED -> APPROVED, and so on. */
export function allowedActions(status: RestaurantStatus): DecisionAction[] {
  switch (status) {
    case 'PENDING':
      return ['approve', 'reject'];
    case 'REJECTED':
      return ['approve'];
    case 'APPROVED':
      return ['suspend'];
    case 'SUSPENDED':
      return ['reactivate'];
  }
}

export const ACTION_LABELS: Record<DecisionAction, string> = {
  approve: 'Approve',
  reject: 'Reject',
  suspend: 'Suspend',
  reactivate: 'Reactivate',
};

/** Past tense, for the success notice. */
export const ACTION_DONE: Record<DecisionAction, string> = {
  approve: 'approved',
  reject: 'rejected',
  suspend: 'suspended',
  reactivate: 'reactivated',
};

export const REASON_MIN = 5;
export const REASON_MAX = 500;

/** A sentence about what is wrong with a rejection/suspension reason, or null. Same 5..500 rule as the server. */
export function reasonProblem(reason: string): string | null {
  const trimmed = reason.trim();
  if (!trimmed) return 'Please enter a reason.';
  if (trimmed.length < REASON_MIN) return `The reason must be at least ${REASON_MIN} characters.`;
  if (trimmed.length > REASON_MAX) return `The reason must be at most ${REASON_MAX} characters.`;
  return null;
}

/** "Srinagar, Jammu and Kashmir, India"; empty parts are skipped and nothing at all gives an em dash. */
export function formatLocation(city?: string | null, state?: string | null, country?: string | null): string {
  const parts = [city, state, country].map((p) => (p ?? '').trim()).filter((p) => p.length > 0);
  return parts.length > 0 ? parts.join(', ') : '—';
}

/** The status each preset list page is locked to, keyed by the route's last segment. */
export const PRESET_PAGES: Record<'pending' | 'approved' | 'rejected', RestaurantStatus> = {
  pending: 'PENDING',
  approved: 'APPROVED',
  rejected: 'REJECTED',
};

export interface PlatformListParams {
  q: string;
  status: RestaurantStatus | '';
  page: number;
  size: number;
  sortBy: PlatformSortBy;
  direction: SortDirection;
}

/** The query string for GET /api/platform-admin/restaurants. Blank search and no status are omitted. */
export function buildListQuery(p: PlatformListParams): string {
  const qs = new URLSearchParams();
  const q = p.q.trim();
  if (q) qs.set('q', q);
  if (p.status) qs.set('status', p.status);
  qs.set('page', String(p.page));
  qs.set('size', String(p.size));
  qs.set('sortBy', p.sortBy);
  qs.set('direction', p.direction);
  return qs.toString();
}

/**
 * The page to show when the current one has no rows left (the last row on it was just decided on):
 * the last page that still has rows, or null when the current page is fine.
 */
export function pageToFallBackTo(page: number, totalItems: number, size: number): number | null {
  if (page <= 0) return null;
  const lastPage = Math.max(0, Math.ceil(totalItems / size) - 1);
  return page > lastPage ? lastPage : null;
}

/** Clicking a header: the same column flips direction, a new one starts descending for dates, else ascending. */
export function nextSort(
  current: { sortBy: PlatformSortBy; direction: SortDirection },
  column: PlatformSortBy,
): { sortBy: PlatformSortBy; direction: SortDirection } {
  if (current.sortBy === column) {
    return { sortBy: column, direction: current.direction === 'asc' ? 'desc' : 'asc' };
  }
  return { sortBy: column, direction: column === 'createdAt' ? 'desc' : 'asc' };
}

/** Roles that belong to a restaurant, as opposed to the platform owner and diners. */
export function isRestaurantScopedRole(role: Me['role']): boolean {
  return role !== 'PLATFORM_OWNER' && role !== 'CUSTOMER';
}

/**
 * The status to show on the gate screen instead of the restaurant areas, or null when the user may
 * go in. Accounts without a restaurant status (older server, or no restaurant) are not gated.
 */
export function gatedStatus(user: Pick<Me, 'role' | 'restaurantStatus'>): Exclude<RestaurantStatus, 'APPROVED'> | null {
  if (!isRestaurantScopedRole(user.role)) return null;
  const status = user.restaurantStatus;
  if (status == null || status === 'APPROVED') return null;
  return status;
}

/** "13 Oct 2026", or an em dash when there is no (valid) timestamp. */
export function formatDate(iso: string | null | undefined): string {
  const d = parseServerTime(iso);
  return d ? d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '—';
}

/** "13 Oct 2026, 2:35 pm", or an em dash. */
export function formatDateTime(iso: string | null | undefined): string {
  const d = parseServerTime(iso);
  return d
    ? d.toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' })
    : '—';
}
