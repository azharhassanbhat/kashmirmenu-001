const currency = import.meta.env.VITE_CURRENCY ?? 'INR';

// The app is English only, and every date, time and number is shown in English (India) whatever language
// the device is set to. Passing `undefined` here would follow the device, so a guest whose phone is set to
// Hindi, Urdu or Arabic could see other numerals or date styles.
const formatter = new Intl.NumberFormat('en-IN', { style: 'currency', currency });

export function money(value: number | null | undefined): string {
  return formatter.format(value ?? 0);
}

/**
 * The currency's symbol on its own ("₹"), for form labels like "Minimum order value (₹)".
 * Taken from the same formatter as every amount, so a different VITE_CURRENCY relabels the forms too.
 */
export function currencySymbol(): string {
  return formatter.formatToParts(0).find((p) => p.type === 'currency')?.value ?? currency;
}

/**
 * Parses a timestamp from the API.
 *
 * The server sends an offset ("2026-10-13T14:35:00+05:30"), which is an unambiguous instant. Older
 * responses (and anything cached from before that change) send a bare local time with no offset; the
 * browser would read those in its own zone, so a device set to a different zone than the restaurant
 * showed times out by exactly that difference. Those are pinned to UTC instead, which at least keeps
 * every such value consistent with the others rather than silently shifted.
 */
export function parseServerTime(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const hasZone = /(?:Z|[+-]\d{2}:?\d{2})$/.test(iso);
  const value = hasZone ? iso : `${iso.length === 16 ? `${iso}:00` : iso}Z`;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

export function timeAgo(iso: string, now = Date.now()): string {
  const at = parseServerTime(iso);
  if (!at) return '';
  const minutes = Math.max(0, Math.floor((now - at.getTime()) / 60_000));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${minutes % 60}m ago`;
}

/** Clock time for a diner: "2:35 PM". Empty string if the value cannot be read. */
export function timeOfDay(iso: string | null | undefined): string {
  const at = parseServerTime(iso);
  if (!at) return '';
  return new Intl.DateTimeFormat('en-IN', { hour: 'numeric', minute: '2-digit' }).format(at);
}

export function toLocalInput(iso: string | undefined): string {
  return iso ? iso.slice(0, 16) : '';
}

// The backend expects a zone-less LocalDateTime ("2026-01-31T10:00:00").
export function toLocalDateTime(inputValue: string): string {
  return inputValue.length === 16 ? `${inputValue}:00` : inputValue;
}

export function titleCase(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ');
}

const menuWhole = new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 });
const menuExact = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency,
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

/** Customer-facing price: whole rupees print as "₹320", anything with paise keeps two decimals. */
export function price(value: number | null | undefined): string {
  const n = value ?? 0;
  return Number.isInteger(n) ? menuWhole.format(n) : menuExact.format(n);
}
