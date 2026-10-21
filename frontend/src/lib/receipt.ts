import { parseServerTime } from './format';

/*
 * Words and small rules for printed receipts. The amounts are never worked out here: a receipt prints what the
 * order recorded. The registration-number rule mirrors the server's TaxRegistration (a check proves they agree).
 */

export const REGISTRATION_MIN = 5;
export const REGISTRATION_MAX = 30;

/** The number tidied the way the server tidies it: no spaces, upper case. */
export const tidyRegistration = (raw: string): string => raw.replace(/\s+/g, '').toUpperCase();

/** What is wrong with a registration number, as a sentence, or null when it is blank or fine. */
export function registrationProblem(raw: string): string | null {
  if (!raw.trim()) return null;
  const tidy = tidyRegistration(raw);
  if (tidy.length < REGISTRATION_MIN || tidy.length > REGISTRATION_MAX || !/^[A-Z0-9/-]+$/.test(tidy)) {
    return `A tax registration number is ${REGISTRATION_MIN} to ${REGISTRATION_MAX} letters, digits, hyphens or slashes, for example 29ABCDE1234F1Z5.`;
  }
  return null;
}

/** The address lines to print, leaving out anything blank: street, then "city, state". */
export function addressLines(r: { address?: string | null; city?: string | null; state?: string | null }): string[] {
  const lines: string[] = [];
  if (r.address?.trim()) lines.push(r.address.trim());
  const place = [r.city, r.state].map((p) => p?.trim()).filter(Boolean).join(', ');
  if (place) lines.push(place);
  return lines;
}

/** "GST No: 29ABCDE1234F1Z5", or null when there is no number. */
export function registrationLine(taxName: string | null | undefined, number: string | null | undefined): string | null {
  if (!number?.trim()) return null;
  return `${taxName?.trim() || 'Tax'} No: ${number.trim()}`;
}

/** "15 Oct 2026, 1:05 pm" in English (India), whatever the device language. */
export function receiptWhen(iso: string | null | undefined): string {
  const at = parseServerTime(iso);
  if (!at) return '';
  return at.toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' });
}

/** How the payment heading should look: money is settled, something is owed, or neither applies. */
export function headingTone(heading: string): 'ok' | 'owed' | 'neutral' {
  if (heading === 'PAID' || heading === 'NO PAYMENT DUE') return 'ok';
  if (heading === 'UNPAID' || heading === 'PART PAID') return 'owed';
  return 'neutral';
}

/** "2 × ₹400.00" for an item line. */
export const quantityLine = (quantity: number, unit: string): string => `${quantity} × ${unit}`;
