import { describe, expect, it } from 'vitest';
import { CANCEL_REASONS, historyText, MAX_NOTE, reasonProblem, statusWord } from './cancelReasons';
import { delayText, timingLine } from './kitchenTiming';

describe('cancel reasons', () => {
  it('lists the six reasons with readable labels, OTHER last', () => {
    expect(CANCEL_REASONS).toHaveLength(6);
    expect(CANCEL_REASONS[5].code).toBe('OTHER');
    expect(new Set(CANCEL_REASONS.map((r) => r.code)).size).toBe(6);
    for (const r of CANCEL_REASONS) expect(r.label.length).toBeGreaterThan(3);
  });

  it('the screen insists on a reason even though the server does not', () => {
    expect(reasonProblem(null, '')).toBe('Please choose a reason.');
  });

  it('"another reason" needs a note', () => {
    expect(reasonProblem('OTHER', '')).toMatch(/other reason/);
    expect(reasonProblem('OTHER', '   ')).toMatch(/other reason/);
    expect(reasonProblem('OTHER', 'Power cut')).toBeNull();
  });

  it('the other reasons do not', () => {
    expect(reasonProblem('OUT_OF_STOCK', '')).toBeNull();
    expect(reasonProblem('GUEST_REQUEST', 'He left')).toBeNull();
  });

  it('caps the note like the server', () => {
    expect(reasonProblem('OUT_OF_STOCK', 'x'.repeat(MAX_NOTE))).toBeNull();
    expect(reasonProblem('OUT_OF_STOCK', 'x'.repeat(MAX_NOTE + 1))).toMatch(/200/);
  });

  it('words each status, naming the first one "Placed"', () => {
    expect(statusWord('PENDING')).toBe('Placed');
    expect(statusWord('SERVED')).toBe('Served');
    expect(statusWord('SOMETHING')).toBe('SOMETHING');
  });

  it('adds the reason only to a cancellation', () => {
    expect(historyText({ to: 'CANCELLED', reasonLabel: 'A dish is out of stock' })).toBe('Cancelled: A dish is out of stock');
    expect(historyText({ to: 'CANCELLED', reasonLabel: null })).toBe('Cancelled');
    expect(historyText({ to: 'READY', reasonLabel: 'ignored' })).toBe('Ready');
  });
});

describe('kitchen timing words', () => {
  it('shows time open against the target', () => {
    expect(timingLine({ elapsedMinutes: 12, prepTargetMinutes: 15 })).toBe('12 of 15 min');
    expect(timingLine({})).toBe('');
    expect(timingLine({ elapsedMinutes: 0, prepTargetMinutes: 10 })).toBe('0 of 10 min');
  });

  it('says how late, only when late', () => {
    expect(delayText({ delayed: true, overdueMinutes: 7 })).toBe('Delayed 7 min');
    expect(delayText({ delayed: true, overdueMinutes: 0 })).toBe('Delayed');
    expect(delayText({ delayed: false, overdueMinutes: 7 })).toBeNull();
    expect(delayText({})).toBeNull();
  });
});
