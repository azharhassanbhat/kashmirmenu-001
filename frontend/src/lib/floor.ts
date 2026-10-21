import type { SettlementKind, TablePayState, TableSessionSummary } from '../api/types';

/*
 * The rules behind the live-tables view, kept apart from the screen so they can be tested without a
 * browser. Nothing here talks to the server: every amount and state comes from it already worked out.
 */

const cents = (n: number) => Math.round(n * 100);

export function payStateLabel(state: TablePayState): string {
  switch (state) {
    case 'NO_ORDERS':
      return 'No orders';
    case 'UNPAID':
      return 'Unpaid';
    case 'PARTIALLY_PAID':
      return 'Partly paid';
    case 'PAID':
      return 'Paid';
  }
}

export type BadgeTone = 'amber' | 'blue' | 'green' | 'red' | 'gray';

export function payStateTone(state: TablePayState): BadgeTone {
  switch (state) {
    case 'PAID':
      return 'green';
    case 'PARTIALLY_PAID':
      return 'blue';
    case 'UNPAID':
      return 'amber';
    case 'NO_ORDERS':
      return 'gray';
  }
}

/** How a payment was made: a guest paying their own bill, one guest paying for the table, or at the counter. */
export function howPaid(kind: SettlementKind | null): string {
  if (kind === 'SEPARATE') return 'Own bill';
  if (kind === 'COMBINED') return 'Whole table';
  return 'At the counter';
}

type Owing = Pick<TableSessionSummary, 'due' | 'inProgress' | 'awaitingConfirmation'>;

/** Everything still owed: payable now, waiting for staff to confirm, and waiting for the kitchen. */
export function owed(s: Owing): number {
  return (cents(s.due) + cents(s.inProgress) + cents(s.awaitingConfirmation)) / 100;
}

/**
 * What to tell staff before they close a table. Closing is never blocked by money owing, but it should
 * never be a surprise either: the message says what is owed and what closing does and does not do.
 */
export function closeWarning(s: Owing, fmt: (amount: number) => string): string {
  const total = owed(s);
  if (total <= 0) {
    return 'Nothing is owed. Closing ends the guests’ sessions; the orders and payments stay on record.';
  }
  const waiting =
    s.inProgress > 0 ? ` ${fmt(s.inProgress)} of it is a payment waiting for you to confirm, which you can still do afterwards.` : '';
  return (
    `${fmt(total)} is still owed.${waiting} Closing does not delete or settle anything: the orders stay on ` +
    'record and the guests lose access.'
  );
}

/** Tables that need a person first, then the longest-open first. A copy: the list it is given is not changed. */
export function sortLive(list: TableSessionSummary[]): TableSessionSummary[] {
  return [...list].sort((a, b) => {
    if (a.needsStaff !== b.needsStaff) return a.needsStaff ? -1 : 1;
    return a.openedAt < b.openedAt ? -1 : a.openedAt > b.openedAt ? 1 : a.id - b.id;
  });
}

/** Why a table closed, in words. */
export function closeReasonLabel(reason: string | null): string {
  if (reason === 'IDLE_EXPIRED') return 'Closed automatically after being idle';
  if (reason === 'STAFF') return 'Closed by staff';
  return 'Closed';
}
