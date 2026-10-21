import { describe, expect, it } from 'vitest';
import { createStatusChimeTracker, statusSound } from './statusChime';

describe('order status chime', () => {
  it('does not chime for the first status seen', () => {
    const t = createStatusChimeTracker();
    expect(t.observe('PREPARING')).toBe(false);
  });

  it('does not chime when a refetch returns the same status', () => {
    const t = createStatusChimeTracker();
    t.observe('PENDING');
    expect(t.observe('PENDING')).toBe(false);
    expect(t.observe('PENDING')).toBe(false);
  });

  it('chimes for each later status once', () => {
    const t = createStatusChimeTracker();
    t.observe('PENDING');
    expect(t.observe('CONFIRMED')).toBe(true);
    expect(t.observe('CONFIRMED')).toBe(false);
    expect(t.observe('PREPARING')).toBe(true);
    expect(t.observe('READY')).toBe(true);
    expect(t.observe('SERVED')).toBe(true);
  });

  it('chimes for a skipped status and for cancellation', () => {
    const t = createStatusChimeTracker();
    t.observe('PENDING');
    expect(t.observe('READY')).toBe(true);
    const c = createStatusChimeTracker();
    c.observe('PREPARING');
    expect(c.observe('CANCELLED')).toBe(true);
  });

  it('never chimes for the same status twice, nor for going backwards', () => {
    const t = createStatusChimeTracker();
    t.observe('CONFIRMED');
    expect(t.observe('PREPARING')).toBe(true);
    expect(t.observe('CONFIRMED')).toBe(false);
    expect(t.observe('PREPARING')).toBe(false);
    expect(t.observe('PENDING')).toBe(false);
  });

  it('gives each guest-facing status its own sound', () => {
    const sounds = (['CONFIRMED', 'PREPARING', 'READY', 'SERVED', 'CANCELLED'] as const).map(statusSound);
    expect(sounds.every((s) => s !== null)).toBe(true);
    expect(new Set(sounds).size).toBe(5);
    expect(statusSound('PENDING')).toBeNull();
  });
});
