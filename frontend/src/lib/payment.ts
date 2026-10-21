import type { DinerBill, Settlement, SettlementKind, TableBill, TableBillGuest } from '../api/types';

/*
 * The rules behind the pay screen, kept apart from the screen so they can be tested without a browser.
 * Nothing here talks to the server. The server decides what is owed; this decides what to show.
 */

const cents = (n: number) => Math.round(n * 100);

/**
 * A fresh key for one payment attempt. The server treats the same key from the same guest as the same
 * attempt, so a retry after a lost response returns the payment already made instead of making another.
 *
 * crypto.randomUUID needs a secure context (https or localhost); a menu opened over plain http on a LAN
 * does not have it, so there is a fallback. Both stay within what the server accepts: letters, digits and . _ : -
 */
export function newIdempotencyKey(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (c && typeof c.randomUUID === 'function') return c.randomUUID();
  return `k${Date.now().toString(36)}${Math.random().toString(36).slice(2, 12)}`;
}

export interface PayChoices {
  /** "Pay My Bill": the guest's own items only. */
  mine: { due: number; available: boolean };
  /** "Pay Entire Table Bill": what the whole table still owes, so one guest can pay for everyone. */
  table: {
    due: number;
    /** The part of the table's bill that is not this guest's own. */
    othersDue: number;
    available: boolean;
    /** Guests with something still to pay, or a payment already in progress. Never anyone's items. */
    guests: TableBillGuest[];
    /** Someone else already has a payment in progress, so part of the table is already being dealt with. */
    othersInProgress: boolean;
  };
  /** Owed by this guest but not payable yet: the kitchen has not confirmed those orders. */
  waitingForKitchen: number;
  /** This guest's own money already on its way, waiting for staff. */
  inProgress: number;
  /** Either option can be chosen. */
  anyAvailable: boolean;
}

/** What the guest can choose between, from the two bills. Pure arithmetic on what the server sent. */
export function payChoices(bill: DinerBill, table: TableBill): PayChoices {
  const othersDue = Math.max(0, cents(table.due) - cents(bill.due)) / 100;
  const guests = table.guests.filter((g) => g.due > 0 || g.inProgress > 0);
  const mineAvailable = bill.due > 0;
  const tableAvailable = table.due > 0;

  return {
    mine: { due: bill.due, available: mineAvailable },
    table: {
      due: table.due,
      othersDue,
      available: tableAvailable,
      guests,
      othersInProgress: table.guests.some((g) => !g.you && g.inProgress > 0),
    },
    waitingForKitchen: bill.awaitingConfirmation,
    inProgress: bill.inProgress,
    anyAvailable: mineAvailable || tableAvailable,
  };
}

/** Why an option cannot be chosen, in words the guest can act on. Null when it can be chosen. */
export function unavailableReason(kind: SettlementKind, c: PayChoices): string | null {
  if (kind === 'SEPARATE') {
    if (c.mine.available) return null;
    if (c.waitingForKitchen > 0) return 'You can pay once the kitchen has confirmed your order.';
    if (c.inProgress > 0) return 'Your payment is already waiting for staff.';
    return 'You have nothing left to pay.';
  }
  if (c.table.available) return null;
  if (c.table.othersInProgress || c.inProgress > 0) return 'The table’s payment is already waiting for staff.';
  return 'Nobody at the table has anything left to pay right now.';
}

/** The state of one of the guest's orders on their bill, as a short label. */
export function orderPayLabel(o: {
  due: number;
  inProgress: number;
  awaitingConfirmation: number;
}): string {
  if (o.due > 0) return 'To pay';
  if (o.inProgress > 0) return 'Payment waiting for staff';
  if (o.awaitingConfirmation > 0) return 'Waiting for the kitchen';
  return 'Paid';
}

/** "cash", "UPI", "card": the method as it reads inside a sentence. */
export function methodLabel(method: string): string {
  switch (method) {
    case 'CASH':
      return 'cash';
    case 'UPI':
      return 'UPI';
    case 'CARD':
      return 'card';
    case 'WALLET':
      return 'wallet';
    default:
      return method.toLowerCase().replace(/_/g, ' ');
  }
}

export function kindLabel(kind: SettlementKind): string {
  return kind === 'COMBINED' ? 'Whole table' : 'My bill';
}

export type Tone = 'wait' | 'ok' | 'bad' | 'muted';

export interface StatusCopy {
  tone: Tone;
  title: string;
  detail: string;
}

/**
 * What to tell the guest about a payment. Nothing is paid until staff confirm it, so PENDING always
 * says so. {@code fmt} formats an amount, passed in so this stays free of locale and currency settings.
 */
export function describeSettlement(s: Settlement, fmt: (amount: number) => string): StatusCopy {
  switch (s.status) {
    case 'PENDING':
      if (s.paymentMethod === 'UPI') {
        return s.customerConfirmedAt
          ? {
              tone: 'wait',
              title: 'Verification pending',
              detail:
                'Your payment confirmation has been submitted. The restaurant will verify your payment shortly. ' +
                'This page updates by itself.',
            }
          : {
              tone: 'wait',
              title: 'Complete your UPI payment',
              detail: `Pay ${fmt(s.pending > 0 ? s.pending : s.total)} with any UPI app, then tell us you have paid.`,
            };
      }
      return {
        tone: 'wait',
        title: 'Waiting for staff to confirm',
        detail:
          `Please pay ${fmt(s.pending > 0 ? s.pending : s.total)} by ${methodLabel(s.paymentMethod)} ` +
          'to a member of staff. This page updates by itself as soon as they confirm.',
      };
    case 'SUCCESS':
      return { tone: 'ok', title: 'Payment received', detail: 'Thank you. This payment is confirmed.' };
    case 'FAILED':
      if (s.paymentMethod === 'UPI') {
        return {
          tone: 'bad',
          title: 'Payment rejected',
          detail:
            'The restaurant could not find this payment, so it was not accepted. ' +
            'If you did pay, please speak to a member of staff. You can also try again.',
        };
      }
      return {
        tone: 'bad',
        title: 'Payment didn’t go through',
        detail: 'Nothing was taken. You can try again.',
      };
    case 'CANCELLED':
      return {
        tone: 'muted',
        title: 'Payment cancelled',
        detail: 'Nothing was taken. You can start again whenever you are ready.',
      };
    case 'PARTIAL':
      return {
        tone: 'bad',
        title: 'Payment partly received',
        detail: `${fmt(s.confirmed)} of ${fmt(s.total)} was received. Please speak to a member of staff about the rest.`,
      };
  }
}

/** The payment the guest is waiting on, if any. The list is newest first. */
export function pendingSettlement(list: Settlement[] | undefined): Settlement | undefined {
  return list?.find((s) => s.status === 'PENDING');
}

/** The latest payment, if it ended without the money arriving: worth telling the guest before they try again. */
export function lastAttemptThatDidNotPay(list: Settlement[] | undefined): Settlement | undefined {
  const latest = list?.[0];
  if (!latest) return undefined;
  return latest.status === 'FAILED' || latest.status === 'CANCELLED' || latest.status === 'PARTIAL'
    ? latest
    : undefined;
}

/** A short, readable receipt number from the payment's code. */
export function shortReference(code: string): string {
  return code.replace(/-/g, '').slice(0, 8).toUpperCase();
}

/** A UPI ID as the server accepts it: name@handle. Anything else is never put into a payment link. */
const UPI_ID = /^[A-Za-z0-9._-]{2,64}@[A-Za-z][A-Za-z0-9]{1,30}$/;

export function isValidUpiId(id: string | null | undefined): id is string {
  return typeof id === 'string' && UPI_ID.test(id.trim());
}

/**
 * A UPI deep link (upi://pay) that opens the guest's UPI app with the payee and the amount filled in.
 * Null when the UPI ID is not valid or the amount is not a positive number. The amount is the server's
 * figure for the bill, never one typed by the guest; the uploaded static QR itself carries no amount.
 */
export function upiPayLink(upiId: string, recipientName: string | null, amount: number, note: string): string | null {
  if (!isValidUpiId(upiId) || !Number.isFinite(amount) || amount <= 0) return null;
  const params = new URLSearchParams();
  params.set('pa', upiId.trim());
  if (recipientName && recipientName.trim()) params.set('pn', recipientName.trim());
  params.set('am', (Math.round(amount * 100) / 100).toFixed(2));
  params.set('cu', 'INR');
  if (note.trim()) params.set('tn', note.trim().slice(0, 50));
  return `upi://pay?${params.toString()}`;
}

/** Whether a typed UPI transaction reference is acceptable (blank is fine): matches the server's rule. */
export function isValidUpiReference(ref: string): boolean {
  const r = ref.trim();
  return r === '' || /^[A-Za-z0-9_/-]{6,35}$/.test(r);
}

export interface BillBreakdown {
  subtotal: number;
  tax: number;
  serviceCharge: number;
  discount: number;
  total: number;
}

/** The server's own figures for the orders on a bill, added up. Cancelled orders are not part of the bill. */
export function billBreakdown(orders: DinerBill['orders']): BillBreakdown {
  const sum = (pick: (o: DinerBill['orders'][number]) => number) =>
    orders.filter((o) => o.status !== 'CANCELLED').reduce((n, o) => n + cents(pick(o)), 0) / 100;
  return {
    subtotal: sum((o) => o.subtotal),
    tax: sum((o) => o.tax),
    serviceCharge: sum((o) => o.serviceCharge),
    discount: sum((o) => o.discount),
    total: sum((o) => o.total),
  };
}

/** A payment method as a title: "Cash", "Card", and "UPI" (not "Upi"). */
export function methodTitle(method: string): string {
  if (method === 'UPI') return 'UPI';
  const text = method.toLowerCase().replace(/_/g, ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}
