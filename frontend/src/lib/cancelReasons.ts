/*
 * Why an order may be cancelled. Mirrors the server's CancelReason list: the server rejects any code it
 * does not know, so adding one means adding it in both places. A reason is optional at the API (older
 * callers send none), but the screens always ask for one.
 */
export type CancelReasonCode =
  | 'OUT_OF_STOCK'
  | 'KITCHEN_CANNOT_MAKE'
  | 'GUEST_REQUEST'
  | 'DUPLICATE_ORDER'
  | 'ORDERED_BY_MISTAKE'
  | 'OTHER';

export const CANCEL_REASONS: { code: CancelReasonCode; label: string }[] = [
  { code: 'OUT_OF_STOCK', label: 'A dish is out of stock' },
  { code: 'KITCHEN_CANNOT_MAKE', label: 'The kitchen cannot make it right now' },
  { code: 'GUEST_REQUEST', label: 'The guest asked to cancel' },
  { code: 'DUPLICATE_ORDER', label: 'It was ordered twice' },
  { code: 'ORDERED_BY_MISTAKE', label: 'It was ordered by mistake' },
  { code: 'OTHER', label: 'Another reason' },
];

export const MAX_NOTE = 200;

/** What is wrong with the form, as a sentence, or null. Mirrors the server's rules. */
export function reasonProblem(reason: CancelReasonCode | null, note: string): string | null {
  if (reason === null) return 'Please choose a reason.';
  if (reason === 'OTHER' && !note.trim()) return 'Please say what the other reason was.';
  if (note.trim().length > MAX_NOTE) return `Please keep the note under ${MAX_NOTE} characters.`;
  return null;
}

const STATUS_WORDS: Record<string, string> = {
  PENDING: 'Placed',
  CONFIRMED: 'Confirmed',
  PREPARING: 'Preparing',
  READY: 'Ready',
  SERVED: 'Served',
  CANCELLED: 'Cancelled',
};

export const statusWord = (status: string): string => STATUS_WORDS[status] ?? status;

/** One history line: "Placed", "Confirmed", "Cancelled: A dish is out of stock". */
export function historyText(entry: { to: string; reasonLabel: string | null }): string {
  const base = statusWord(entry.to);
  return entry.to === 'CANCELLED' && entry.reasonLabel ? `${base}: ${entry.reasonLabel}` : base;
}
