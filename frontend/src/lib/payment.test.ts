import { describe, expect, it } from 'vitest';
import type { DinerBill, Settlement, TableBill, TableBillGuest } from '../api/types';
import {
  billBreakdown,
  describeSettlement,
  isValidUpiId,
  isValidUpiReference,
  kindLabel,
  lastAttemptThatDidNotPay,
  methodLabel,
  newIdempotencyKey,
  orderPayLabel,
  payChoices,
  pendingSettlement,
  shortReference,
  unavailableReason,
  upiPayLink,
} from './payment';

const fmt = (n: number) => `₹${n}`;

function bill(over: Partial<DinerBill> = {}): DinerBill {
  return {
    displayName: 'Rahul',
    visitId: 'v-1',
    orders: [],
    due: 0,
    inProgress: 0,
    paid: 0,
    awaitingConfirmation: 0,
    ...over,
  };
}

function guest(name: string, over: Partial<TableBillGuest> = {}): TableBillGuest {
  return { displayName: name, you: false, due: 0, inProgress: 0, awaitingConfirmation: 0, ...over };
}

function table(guests: TableBillGuest[], over: Partial<TableBill> = {}): TableBill {
  const due = guests.reduce((n, g) => n + g.due, 0);
  return { visitId: 'v-1', tableName: 'Table 5', guests, due, inProgress: 0, paid: 0, awaitingConfirmation: 0, ...over };
}

function settlement(over: Partial<Settlement> = {}): Settlement {
  return {
    code: '3f2b8c1e-aaaa-bbbb-cccc-1234567890ab',
    kind: 'SEPARATE',
    status: 'PENDING',
    paymentMethod: 'CASH',
    total: 500,
    pending: 500,
    confirmed: 0,
    createdAt: '2026-10-14T12:00:00+05:30',
    confirmedAt: null,
    reference: null,
    customerConfirmedAt: null,
    guests: [],
    ...over,
  };
}

describe('payChoices', () => {
  // Table 5 from the brief: Rahul 500, Amit 350, Priya 400, all still to pay.
  const guests = [
    guest('Rahul', { you: true, due: 500 }),
    guest('Amit', { due: 350 }),
    guest('Priya', { due: 400 }),
  ];

  it('offers both options when the guest and the table both owe something', () => {
    const c = payChoices(bill({ due: 500 }), table(guests));

    expect(c.mine).toEqual({ due: 500, available: true });
    expect(c.table.due).toBe(1250);
    expect(c.table.othersDue).toBe(750);
    expect(c.table.available).toBe(true);
    expect(c.anyAvailable).toBe(true);
  });

  it('lets a guest who owes nothing still pay for the others', () => {
    const c = payChoices(
      bill({ due: 0, paid: 500 }),
      table([guest('Rahul', { you: true }), guest('Amit', { due: 350 }), guest('Priya', { due: 400 })]),
    );

    expect(c.mine.available).toBe(false);
    expect(c.table.available).toBe(true);
    expect(c.table.othersDue).toBe(750);
  });

  it('lists only guests with something still to pay or a payment in progress', () => {
    const c = payChoices(
      bill({ due: 500 }),
      table([
        guest('Rahul', { you: true, due: 500 }),
        guest('Amit', { due: 0 }),
        guest('Priya', { due: 0, inProgress: 400 }),
      ]),
    );

    expect(c.table.guests.map((g) => g.displayName)).toEqual(['Rahul', 'Priya']);
    expect(c.table.othersInProgress).toBe(true);
  });

  it('does no money arithmetic in floating point', () => {
    const c = payChoices(
      bill({ due: 0.1 }),
      table([guest('Rahul', { you: true, due: 0.1 }), guest('Amit', { due: 0.2 })], { due: 0.3 }),
    );
    expect(c.table.othersDue).toBe(0.2);
  });

  it('offers nothing when nothing is payable', () => {
    const c = payChoices(bill(), table([guest('Rahul', { you: true })]));
    expect(c.anyAvailable).toBe(false);
  });
});

describe('unavailableReason', () => {
  it('is null for an option that can be chosen', () => {
    const c = payChoices(bill({ due: 100 }), table([guest('Rahul', { you: true, due: 100 })]));
    expect(unavailableReason('SEPARATE', c)).toBeNull();
    expect(unavailableReason('COMBINED', c)).toBeNull();
  });

  it('says to wait when the kitchen has not confirmed the order', () => {
    const c = payChoices(bill({ awaitingConfirmation: 100 }), table([guest('Rahul', { you: true, awaitingConfirmation: 100 })]));
    expect(unavailableReason('SEPARATE', c)).toMatch(/kitchen/);
  });

  it('says a payment is already waiting when one is in progress', () => {
    const c = payChoices(bill({ inProgress: 100 }), table([guest('Rahul', { you: true, inProgress: 100 })]));
    expect(unavailableReason('SEPARATE', c)).toMatch(/waiting for staff/);
    expect(unavailableReason('COMBINED', c)).toMatch(/waiting for staff/);
  });

  it('says there is nothing left when everything is paid', () => {
    const c = payChoices(bill({ paid: 100 }), table([guest('Rahul', { you: true })]));
    expect(unavailableReason('SEPARATE', c)).toMatch(/nothing left/);
    expect(unavailableReason('COMBINED', c)).toMatch(/Nobody/);
  });
});

describe('orderPayLabel', () => {
  it('describes each state in turn', () => {
    expect(orderPayLabel({ due: 10, inProgress: 0, awaitingConfirmation: 0 })).toBe('To pay');
    expect(orderPayLabel({ due: 0, inProgress: 10, awaitingConfirmation: 0 })).toBe('Payment waiting for staff');
    expect(orderPayLabel({ due: 0, inProgress: 0, awaitingConfirmation: 10 })).toBe('Waiting for the kitchen');
    expect(orderPayLabel({ due: 0, inProgress: 0, awaitingConfirmation: 0 })).toBe('Paid');
  });
});

describe('describeSettlement', () => {
  it('never says a pending payment is paid', () => {
    const copy = describeSettlement(settlement({ paymentMethod: 'CASH' }), fmt);
    expect(copy.tone).toBe('wait');
    expect(copy.title).toMatch(/Waiting for staff/);
    expect(copy.detail).toMatch(/₹500 by cash/i);
  });

  it('asks a UPI guest to finish paying until they say they have', () => {
    const copy = describeSettlement(settlement({ paymentMethod: 'UPI' }), fmt);
    expect(copy.title).toBe('Complete your UPI payment');
    expect(copy.detail).toMatch(/₹500/);
  });

  it('shows verification pending, not paid, once the guest has claimed a UPI payment', () => {
    const copy = describeSettlement(
      settlement({ paymentMethod: 'UPI', customerConfirmedAt: '2026-10-14T12:05:00+05:30' }),
      fmt,
    );
    expect(copy.tone).toBe('wait');
    expect(copy.title).toBe('Verification pending');
    expect(copy.detail).toContain('The restaurant will verify your payment shortly');
  });

  it('confirms a received payment', () => {
    const copy = describeSettlement(settlement({ status: 'SUCCESS', pending: 0, confirmed: 500 }), fmt);
    expect(copy.tone).toBe('ok');
    expect(copy.title).toBe('Payment received');
  });

  it('lets the guest try again after a failure or a cancellation', () => {
    expect(describeSettlement(settlement({ status: 'FAILED' }), fmt).detail).toMatch(/try again/);
    expect(describeSettlement(settlement({ status: 'CANCELLED' }), fmt).tone).toBe('muted');
  });

  it('reports a partly received payment with the real figures', () => {
    const copy = describeSettlement(settlement({ status: 'PARTIAL', total: 1700, confirmed: 800, pending: 0 }), fmt);
    expect(copy.detail).toMatch(/₹800 of ₹1700/);
  });
});

describe('finding the right settlement', () => {
  const pending = settlement({ code: 'p', status: 'PENDING' });
  const done = settlement({ code: 'd', status: 'SUCCESS' });
  const failed = settlement({ code: 'f', status: 'FAILED' });

  it('finds the payment being waited on, newest first', () => {
    expect(pendingSettlement([pending, done])?.code).toBe('p');
    expect(pendingSettlement([done, failed])).toBeUndefined();
    expect(pendingSettlement(undefined)).toBeUndefined();
  });

  it('reports the latest payment only when it ended without the money arriving', () => {
    expect(lastAttemptThatDidNotPay([failed, done])?.code).toBe('f');
    expect(lastAttemptThatDidNotPay([done, failed])).toBeUndefined();
    expect(lastAttemptThatDidNotPay([pending])).toBeUndefined();
    expect(lastAttemptThatDidNotPay([])).toBeUndefined();
  });
});

describe('labels', () => {
  it('reads methods and kinds in plain words', () => {
    expect(methodLabel('CASH')).toBe('cash');
    expect(methodLabel('UPI')).toBe('UPI');
    expect(methodLabel('BANK_TRANSFER')).toBe('bank transfer');
    expect(kindLabel('COMBINED')).toBe('Whole table');
    expect(kindLabel('SEPARATE')).toBe('My bill');
  });

  it('makes a short receipt number from the code', () => {
    expect(shortReference('3f2b8c1e-aaaa-bbbb-cccc-1234567890ab')).toBe('3F2B8C1E');
  });
});

describe('newIdempotencyKey', () => {
  it('is always acceptable to the server and never repeats', () => {
    const seen = new Set<string>();
    for (let i = 0; i < 200; i++) {
      const key = newIdempotencyKey();
      expect(key).toMatch(/^[A-Za-z0-9._:-]{1,80}$/);
      seen.add(key);
    }
    expect(seen.size).toBe(200);
  });
});

describe('upiPayLink', () => {
  it('builds a UPI link with the payee and the server amount', () => {
    const link = upiPayLink('harud@upi', 'Harud Restaurant', 1250, 'Table 05 bill');
    expect(link).not.toBeNull();
    const url = new URL(link!);
    expect(url.protocol).toBe('upi:');
    expect(url.searchParams.get('pa')).toBe('harud@upi');
    expect(url.searchParams.get('pn')).toBe('Harud Restaurant');
    expect(url.searchParams.get('am')).toBe('1250.00');
    expect(url.searchParams.get('cu')).toBe('INR');
  });

  it('refuses an invalid UPI ID, so nothing odd ends up in a payment link', () => {
    expect(upiPayLink('not a upi id', null, 100, '')).toBeNull();
    expect(upiPayLink('a@b&am=1', null, 100, '')).toBeNull();
    expect(upiPayLink('harud@upi', null, 0, '')).toBeNull();
    expect(upiPayLink('harud@upi', null, Number.NaN, '')).toBeNull();
  });

  it('encodes the name and note instead of splicing them in', () => {
    const url = new URL(upiPayLink('harud@upi', 'Cafe & Bar=1', 99.5, 'x&am=1')!);
    expect(url.searchParams.get('pn')).toBe('Cafe & Bar=1');
    expect(url.searchParams.get('am')).toBe('99.50');
    expect(url.searchParams.get('tn')).toBe('x&am=1');
  });
});

describe('UPI input checks', () => {
  it('accepts name@handle and rejects the rest', () => {
    expect(isValidUpiId('harud@upi')).toBe(true);
    expect(isValidUpiId('harud.res-1@okbank')).toBe(true);
    expect(isValidUpiId('harud')).toBe(false);
    expect(isValidUpiId(null)).toBe(false);
  });

  it('accepts a blank or well-formed transaction reference only', () => {
    expect(isValidUpiReference('')).toBe(true);
    expect(isValidUpiReference('412345678901')).toBe(true);
    expect(isValidUpiReference('12')).toBe(false);
    expect(isValidUpiReference('<script>alert(1)</script>')).toBe(false);
  });
});

describe('billBreakdown and rejected UPI copy', () => {
  const order = (over: Partial<DinerBill['orders'][number]>): DinerBill['orders'][number] => ({
    orderNumber: 'ORD-1',
    publicToken: 't',
    status: 'CONFIRMED',
    placedAt: '2026-10-14T12:00:00+05:30',
    subtotal: 1000,
    tax: 50,
    serviceCharge: 100,
    discount: 0,
    total: 1150,
    paid: 0,
    inProgress: 0,
    due: 1150,
    awaitingConfirmation: 0,
    paymentStatus: null,
    payable: true,
    items: [],
    ...over,
  });

  it('adds up the server figures and leaves out cancelled orders', () => {
    const b = billBreakdown([
      order({}),
      order({ subtotal: 200, tax: 10, serviceCharge: 20, discount: 30, total: 200 }),
      order({ status: 'CANCELLED', subtotal: 999, total: 999 }),
    ]);
    expect(b).toEqual({ subtotal: 1200, tax: 60, serviceCharge: 120, discount: 30, total: 1350 });
  });

  it('says a rejected UPI payment was rejected, not that it failed to go through', () => {
    const copy = describeSettlement(settlement({ paymentMethod: 'UPI', status: 'FAILED', pending: 0 }), fmt);
    expect(copy.tone).toBe('bad');
    expect(copy.title).toBe('Payment rejected');
  });
});
