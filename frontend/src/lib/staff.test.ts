import { describe, expect, it } from 'vitest';
import type { RoleName } from '../api/types';
import { assignableRoles, generatePassword, landingAfterLogin, passwordProblem, roleLabel } from './staff';

describe('staff helpers', () => {
  it('lets owners and admins hand out four roles, and managers only three', () => {
    expect(assignableRoles('RESTAURANT_OWNER')).toEqual(['MANAGER', 'CHEF', 'WAITER', 'CASHIER']);
    expect(assignableRoles('ADMIN')).toEqual(['MANAGER', 'CHEF', 'WAITER', 'CASHIER']);
    expect(assignableRoles('MANAGER')).toEqual(['CHEF', 'WAITER', 'CASHIER']);
  });

  it('offers nothing to anyone else', () => {
    for (const r of ['CHEF', 'WAITER', 'CASHIER', 'CUSTOMER'] as const) {
      expect(assignableRoles(r)).toEqual([]);
    }
    // Platform staff never use this screen, and the type does not name them; the answer must still be none.
    expect(assignableRoles('PLATFORM_OWNER' as RoleName)).toEqual([]);
    expect(assignableRoles(undefined)).toEqual([]);
  });

  it('never offers an owner, admin, platform or diner role', () => {
    for (const actor of ['RESTAURANT_OWNER', 'ADMIN', 'MANAGER'] as const) {
      const roles = assignableRoles(actor);
      for (const forbidden of ['RESTAURANT_OWNER', 'ADMIN', 'PLATFORM_OWNER', 'CUSTOMER']) {
        expect(roles).not.toContain(forbidden);
      }
    }
  });

  it('explains weak passwords like the server does', () => {
    expect(passwordProblem('Waiter2026', 'w@x.com', 'Sam')).toBeNull();
    expect(passwordProblem('', 'w@x.com', 'Sam')).toMatch(/enter a password/i);
    expect(passwordProblem('abc12', 'w@x.com', 'Sam')).toMatch(/8/);
    expect(passwordProblem('12345678', 'w@x.com', 'Sam')).toMatch(/letter/);
    expect(passwordProblem('abcdefgh', 'w@x.com', 'Sam')).toMatch(/number/);
    expect(passwordProblem('waiter12', 'waiter12@x.com', 'Sam')).toMatch(/email/);
    expect(passwordProblem('sam12345', 'w@x.com', 'Sam12345')).toMatch(/name/);
  });

  it('generates passwords that always satisfy the rules', () => {
    for (let i = 0; i < 300; i++) {
      const p = generatePassword();
      expect(p).toHaveLength(12);
      expect(passwordProblem(p, 'a@b.com', 'Name')).toBeNull();
    }
  });

  it('never uses look-alike characters', () => {
    for (let i = 0; i < 300; i++) expect(generatePassword()).not.toMatch(/[0O1lI]/);
  });

  it('is not the same every time', () => {
    const seen = new Set(Array.from({ length: 50 }, () => generatePassword()));
    expect(seen.size).toBe(50);
  });

  it('labels roles for people', () => {
    expect(roleLabel('RESTAURANT_OWNER')).toBe('Owner');
    expect(roleLabel('CASHIER')).toBe('Cashier');
  });
});

describe('landingAfterLogin', () => {
  it('goes home when there was no earlier page', () => {
    expect(landingAfterLogin(undefined, 'PLATFORM_OWNER')).toBe('/platform-admin');
    expect(landingAfterLogin(undefined, 'MANAGER')).toBe('/admin');
  });

  it('returns to the page the person was turned away from', () => {
    expect(landingAfterLogin('/admin/orders', 'RESTAURANT_OWNER')).toBe('/admin/orders');
    expect(landingAfterLogin('/platform-admin/restaurants', 'PLATFORM_OWNER')).toBe('/platform-admin/restaurants');
  });

  it('never sends an account across the platform boundary', () => {
    expect(landingAfterLogin('/admin', 'PLATFORM_OWNER')).toBe('/platform-admin');
    expect(landingAfterLogin('/platform-admin/restaurants', 'RESTAURANT_OWNER')).toBe('/admin');
  });
});
