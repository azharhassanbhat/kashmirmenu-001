import { describe, expect, it } from 'vitest';
import { parseServerTime, price, timeAgo, timeOfDay, titleCase, toLocalDateTime } from './format';

describe('format helpers', () => {
  it('formats elapsed time', () => {
    const now = Date.parse('2026-01-01T12:00:00Z');
    expect(timeAgo('2026-01-01T11:59:50Z', now)).toBe('just now');
    expect(timeAgo('2026-01-01T11:45:00Z', now)).toBe('15 min ago');
    expect(timeAgo('2026-01-01T10:30:00Z', now)).toBe('1h 30m ago');
  });

  it('treats a timestamp with an offset as the instant it names', () => {
    // The kitchen board showed a just-placed order as "5h 30m ago" because the server sent a bare
    // local time and the browser read it in its own zone. With the offset, an order placed now is new.
    const now = Date.parse('2026-01-01T12:00:00Z');
    expect(timeAgo('2026-01-01T17:30:00+05:30', now)).toBe('just now');
    expect(timeAgo('2026-01-01T17:15:00+05:30', now)).toBe('15 min ago');
    expect(parseServerTime('2026-01-01T17:30:00+05:30')?.getTime()).toBe(now);
  });

  it('has nothing to show for a missing or unreadable timestamp', () => {
    expect(parseServerTime(null)).toBe(null);
    expect(parseServerTime('not-a-date')).toBe(null);
    expect(timeAgo('not-a-date')).toBe('');
    expect(timeOfDay(null)).toBe('');
  });

  it('title-cases enum values', () => {
    expect(titleCase('PREPARING')).toBe('Preparing');
    expect(titleCase('RESTAURANT_OWNER')).toBe('Restaurant owner');
  });

  it('adds seconds to datetime-local values', () => {
    expect(toLocalDateTime('2026-01-31T10:00')).toBe('2026-01-31T10:00:00');
    expect(toLocalDateTime('2026-01-31T10:00:30')).toBe('2026-01-31T10:00:30');
  });

  it('prints whole-rupee prices without paise and keeps paise otherwise', () => {
    expect(price(320)).toBe('₹320');
    expect(price(1250)).toBe('₹1,250');
    expect(price(99.5)).toBe('₹99.50');
    expect(price(null)).toBe('₹0');
  });
});
