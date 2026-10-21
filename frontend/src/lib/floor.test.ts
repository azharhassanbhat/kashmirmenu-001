import { describe, expect, it } from 'vitest';
import type { TableSessionSummary } from '../api/types';
import { closeReasonLabel, closeWarning, howPaid, owed, payStateLabel, payStateTone, sortLive } from './floor';

const fmt = (n: number) => `₹${n}`;

function table(over: Partial<TableSessionSummary> = {}): TableSessionSummary {
  return {
    id: 1,
    visitCode: 'v',
    tableName: 'Table 5',
    status: 'OPEN',
    openedAt: '2026-10-14T12:00:00+05:30',
    lastActivityAt: '2026-10-14T12:05:00+05:30',
    guestCount: 3,
    due: 0,
    inProgress: 0,
    paid: 0,
    awaitingConfirmation: 0,
    payState: 'UNPAID',
    idle: false,
    needsStaff: false,
    closedAt: null,
    closeReason: null,
    ...over,
  };
}

describe('pay states', () => {
  it('reads each state in words and colour', () => {
    expect(payStateLabel('PARTIALLY_PAID')).toBe('Partly paid');
    expect(payStateLabel('PAID')).toBe('Paid');
    expect(payStateLabel('UNPAID')).toBe('Unpaid');
    expect(payStateLabel('NO_ORDERS')).toBe('No orders');
    expect(payStateTone('PAID')).toBe('green');
    expect(payStateTone('PARTIALLY_PAID')).toBe('blue');
    expect(payStateTone('UNPAID')).toBe('amber');
    expect(payStateTone('NO_ORDERS')).toBe('gray');
  });
});

describe('howPaid', () => {
  it('tells separate, combined and counter payments apart', () => {
    expect(howPaid('SEPARATE')).toBe('Own bill');
    expect(howPaid('COMBINED')).toBe('Whole table');
    expect(howPaid(null)).toBe('At the counter');
  });
});

describe('owed', () => {
  it('adds what is due, waiting for staff and waiting for the kitchen', () => {
    expect(owed({ due: 750, inProgress: 0, awaitingConfirmation: 0 })).toBe(750);
    expect(owed({ due: 500, inProgress: 250, awaitingConfirmation: 100 })).toBe(850);
  });

  it('does not drift in floating point', () => {
    expect(owed({ due: 0.1, inProgress: 0.2, awaitingConfirmation: 0 })).toBe(0.3);
  });
});

describe('closeWarning', () => {
  it('says plainly when nothing is owed', () => {
    expect(closeWarning({ due: 0, inProgress: 0, awaitingConfirmation: 0 }, fmt)).toMatch(/Nothing is owed/);
  });

  it('says what is owed, and that closing deletes and settles nothing', () => {
    const text = closeWarning({ due: 750, inProgress: 0, awaitingConfirmation: 0 }, fmt);
    expect(text).toMatch(/₹750 is still owed/);
    expect(text).toMatch(/does not delete or settle anything/);
  });

  it('points out a payment still waiting for confirmation', () => {
    const text = closeWarning({ due: 500, inProgress: 250, awaitingConfirmation: 0 }, fmt);
    expect(text).toMatch(/₹750 is still owed/);
    expect(text).toMatch(/₹250 of it is a payment waiting for you to confirm/);
  });
});

describe('sortLive', () => {
  it('puts tables that need a person first, then the longest open', () => {
    const calm = table({ id: 1, openedAt: '2026-10-14T10:00:00+05:30' });
    const later = table({ id: 2, openedAt: '2026-10-14T11:00:00+05:30' });
    const stale = table({ id: 3, openedAt: '2026-10-14T12:00:00+05:30', needsStaff: true });

    expect(sortLive([later, stale, calm]).map((t) => t.id)).toEqual([3, 1, 2]);
  });

  it('does not change the list it was given', () => {
    const a = table({ id: 1, openedAt: '2026-10-14T12:00:00+05:30' });
    const b = table({ id: 2, openedAt: '2026-10-14T10:00:00+05:30' });
    const input = [a, b];
    sortLive(input);
    expect(input.map((t) => t.id)).toEqual([1, 2]);
  });
});

describe('closeReasonLabel', () => {
  it('says who or what closed the table', () => {
    expect(closeReasonLabel('IDLE_EXPIRED')).toMatch(/automatically/);
    expect(closeReasonLabel('STAFF')).toBe('Closed by staff');
    expect(closeReasonLabel(null)).toBe('Closed');
  });
});
