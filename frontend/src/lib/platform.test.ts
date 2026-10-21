import { describe, expect, it } from 'vitest';
import type { RestaurantStatus, RoleName } from '../api/types';
import {
  allowedActions,
  buildListQuery,
  formatDate,
  formatLocation,
  gatedStatus,
  nextSort,
  pageToFallBackTo,
  reasonProblem,
  statusBadgeClass,
  statusLabel,
} from './platform';
import { PLATFORM_ROLES, homeFor } from './staff';

describe('homeFor', () => {
  it('sends the platform owner to the platform area', () => {
    expect(homeFor('PLATFORM_OWNER')).toBe('/platform-admin');
    expect(PLATFORM_ROLES).toEqual(['PLATFORM_OWNER']);
  });

  it('leaves the restaurant roles where they were', () => {
    const expected: [RoleName, string][] = [
      ['ADMIN', '/admin'],
      ['RESTAURANT_OWNER', '/admin'],
      ['MANAGER', '/admin'],
      ['CHEF', '/kitchen'],
      ['WAITER', '/kitchen'],
      ['CASHIER', '/floor'],
      ['CUSTOMER', '/'],
    ];
    for (const [role, home] of expected) expect(homeFor(role)).toBe(home);
  });
});

describe('allowedActions', () => {
  it('follows the approval state machine', () => {
    expect(allowedActions('PENDING')).toEqual(['approve', 'reject']);
    expect(allowedActions('REJECTED')).toEqual(['approve']);
    expect(allowedActions('APPROVED')).toEqual(['suspend']);
    expect(allowedActions('SUSPENDED')).toEqual(['reactivate']);
  });
});

describe('status labels and badges', () => {
  it('has a label and a badge class for every status', () => {
    const all: RestaurantStatus[] = ['PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'];
    expect(all.map(statusLabel)).toEqual(['Pending', 'Approved', 'Rejected', 'Suspended']);
    expect(all.map(statusBadgeClass)).toEqual(['badge-amber', 'badge-green', 'badge-red', 'badge-gray']);
  });
});

describe('reasonProblem', () => {
  it('requires a reason of 5 to 500 characters after trimming', () => {
    expect(reasonProblem('')).toMatch(/enter a reason/i);
    expect(reasonProblem('     ')).toMatch(/enter a reason/i);
    expect(reasonProblem(' abcd ')).toMatch(/at least 5/);
    expect(reasonProblem('abcde')).toBeNull();
    expect(reasonProblem('x'.repeat(500))).toBeNull();
    expect(reasonProblem('x'.repeat(501))).toMatch(/at most 500/);
  });
});

describe('formatLocation', () => {
  it('joins the parts that exist', () => {
    expect(formatLocation('Srinagar', 'J&K', 'India')).toBe('Srinagar, J&K, India');
    expect(formatLocation('Srinagar', null, 'India')).toBe('Srinagar, India');
    expect(formatLocation('  ', '', undefined)).toBe('—');
    expect(formatLocation()).toBe('—');
  });
});

describe('formatDate', () => {
  it('shows a dash for a missing or invalid timestamp', () => {
    expect(formatDate(null)).toBe('—');
    expect(formatDate('not a date')).toBe('—');
    expect(formatDate('2026-10-13T14:35:00+05:30')).toMatch(/2026/);
  });
});

describe('buildListQuery', () => {
  it('omits blank search and status and keeps paging and sorting', () => {
    expect(
      buildListQuery({ q: '  ', status: '', page: 0, size: 10, sortBy: 'createdAt', direction: 'desc' }),
    ).toBe('page=0&size=10&sortBy=createdAt&direction=desc');
  });

  it('encodes the search text', () => {
    const qs = buildListQuery({ q: ' a&b ', status: 'PENDING', page: 2, size: 10, sortBy: 'name', direction: 'asc' });
    const parsed = new URLSearchParams(qs);
    expect(parsed.get('q')).toBe('a&b');
    expect(parsed.get('status')).toBe('PENDING');
    expect(parsed.get('page')).toBe('2');
  });
});

describe('nextSort', () => {
  it('flips the direction of the active column', () => {
    expect(nextSort({ sortBy: 'name', direction: 'asc' }, 'name')).toEqual({ sortBy: 'name', direction: 'desc' });
    expect(nextSort({ sortBy: 'name', direction: 'desc' }, 'name')).toEqual({ sortBy: 'name', direction: 'asc' });
  });

  it('starts a new column newest-first for dates and A-Z otherwise', () => {
    expect(nextSort({ sortBy: 'name', direction: 'asc' }, 'createdAt')).toEqual({ sortBy: 'createdAt', direction: 'desc' });
    expect(nextSort({ sortBy: 'createdAt', direction: 'desc' }, 'status')).toEqual({ sortBy: 'status', direction: 'asc' });
  });
});

describe('gatedStatus', () => {
  it('lets approved restaurants, accounts without one, and the platform owner through', () => {
    expect(gatedStatus({ role: 'RESTAURANT_OWNER', restaurantStatus: 'APPROVED' })).toBeNull();
    expect(gatedStatus({ role: 'RESTAURANT_OWNER', restaurantStatus: null })).toBeNull();
    expect(gatedStatus({ role: 'ADMIN', restaurantStatus: undefined })).toBeNull();
    expect(gatedStatus({ role: 'PLATFORM_OWNER', restaurantStatus: 'PENDING' })).toBeNull();
  });

  it('gates every restaurant role while the restaurant is not approved', () => {
    expect(gatedStatus({ role: 'RESTAURANT_OWNER', restaurantStatus: 'PENDING' })).toBe('PENDING');
    expect(gatedStatus({ role: 'MANAGER', restaurantStatus: 'REJECTED' })).toBe('REJECTED');
    expect(gatedStatus({ role: 'CHEF', restaurantStatus: 'SUSPENDED' })).toBe('SUSPENDED');
    expect(gatedStatus({ role: 'CASHIER', restaurantStatus: 'PENDING' })).toBe('PENDING');
  });
});

describe('pageToFallBackTo', () => {
  it('leaves the first page and any page that still has rows alone', () => {
    expect(pageToFallBackTo(0, 0, 10)).toBeNull();
    expect(pageToFallBackTo(1, 11, 10)).toBeNull();
    expect(pageToFallBackTo(1, 20, 10)).toBeNull();
  });

  it('steps back when the last row of the last page was just decided on', () => {
    // 11 restaurants was two pages; deciding on the 11th leaves 10, so page 1 no longer exists.
    expect(pageToFallBackTo(1, 10, 10)).toBe(0);
    expect(pageToFallBackTo(3, 21, 10)).toBe(2);
  });

  it('goes to the first page when nothing is left at all', () => {
    expect(pageToFallBackTo(2, 0, 10)).toBe(0);
  });
});
