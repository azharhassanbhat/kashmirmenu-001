import { describe, expect, it } from 'vitest';
import {
  claimableOrders,
  isSafeExternal,
  isSafeImage,
  knownActions,
  newRewardSecret,
  safeColor,
  telHref,
} from './landing';

describe('landing page safety checks', () => {
  it('only accepts #rrggbb brand colours', () => {
    expect(safeColor('#D51F32')).toBe('#D51F32');
    expect(safeColor(' #d51f32 ')).toBe('#d51f32');
    for (const bad of [null, undefined, '', 'red', '#fff', '#12345g', 'red;background:url(x)', '#d51f32;}']) {
      expect(safeColor(bad)).toBeNull();
    }
  });

  it('only opens absolute https addresses', () => {
    expect(isSafeExternal('https://g.page/r/abc/review')).toBe(true);
    for (const bad of [
      null,
      '',
      'http://g.page/r/abc/review',
      'javascript:alert(1)',
      'data:text/html,hi',
      '//g.page/r/abc',
      'g.page/r/abc',
      'https://user:pw@g.page/r/abc',
      'https://localhost/x',
      'https://' + 'a'.repeat(600) + '.com',
    ]) {
      expect(isSafeExternal(bad)).toBe(false);
    }
  });

  it('shows our own uploaded photos as well as https photos', () => {
    const key = 'a'.repeat(32);
    expect(isSafeImage(`/api/public/images/${key}`)).toBe(true);
    expect(isSafeImage('https://cdn.example.com/logo.png')).toBe(true);
    for (const bad of [
      null,
      '',
      `/api/public/images/${key}/../x`,
      '/api/public/images/short',
      `//evil.example/api/public/images/${key}`,
      '/other/path.png',
      'http://cdn.example.com/logo.png',
      'javascript:alert(1)',
    ]) {
      expect(isSafeImage(bad)).toBe(false);
    }
  });

  it('builds a tel link only from a clean number', () => {
    expect(telHref('+919876543210')).toBe('tel:+919876543210');
    expect(telHref('01942501234')).toBe('tel:01942501234');
    for (const bad of [null, '', '123', 'javascript:1', '+91 98765 43210', '98765;43210']) {
      expect(telHref(bad)).toBeNull();
    }
  });
});

describe('landing actions', () => {
  it('keeps the cards the app offers (Google review included), in order, and drops retired anonymous feedback', () => {
    expect(knownActions(['MENU', 'REVIEW', 'WIFI', 'FEEDBACK', 'ASSISTANCE', 'REWARDS'])).toEqual(['MENU', 'REVIEW', 'WIFI', 'ASSISTANCE', 'REWARDS']);
    expect(knownActions(['FEEDBACK'])).toEqual([]);
    expect(knownActions(['toString', '__proto__', 'constructor'])).toEqual([]);
    expect(knownActions(null)).toEqual([]);
    expect(knownActions(undefined)).toEqual([]);
  });
});

describe('rewards helpers', () => {
  it('makes a 48-character hex secret from the random source', () => {
    const secret = newRewardSecret((b) => b.fill(171));
    expect(secret).toBe('ab'.repeat(24));
    expect(newRewardSecret((b) => b.fill(1))).toBe('01'.repeat(24));
  });

  it('offers only orders that carry a tracking token', () => {
    const orders = [
      { orderNumber: 'ORD-3', token: 'tok3', placedAt: 'x' },
      { orderNumber: 'ORD-2', placedAt: 'x' },
      { orderNumber: 'ORD-1', token: '', placedAt: 'x' },
    ];
    expect(claimableOrders(orders).map((o) => o.orderNumber)).toEqual(['ORD-3']);
  });
});
