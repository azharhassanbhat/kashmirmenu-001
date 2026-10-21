/*
 * Laying out a sheet of printable table cards. The QR images come from the server; this decides how many fit
 * on a page, how big an image to ask for, and how to fetch many without flooding the server.
 */

export interface SheetLayout {
  id: 'one' | 'four' | 'six' | 'twelve';
  label: string;
  perPage: number;
  columns: number;
  /** Pixels to ask the server for. Printed bigger needs a sharper image. */
  imagePixels: number;
  hint: string;
}

export const LAYOUTS: SheetLayout[] = [
  { id: 'six', label: '6 per page (2 × 3)', perPage: 6, columns: 2, imagePixels: 800, hint: 'A good size for a table card.' },
  { id: 'four', label: '4 per page (2 × 2)', perPage: 4, columns: 2, imagePixels: 900, hint: 'Larger cards; easy to scan from a seat.' },
  { id: 'twelve', label: '12 per page (3 × 4)', perPage: 12, columns: 3, imagePixels: 600, hint: 'Small cards or stickers.' },
  { id: 'one', label: '1 per page (large)', perPage: 1, columns: 1, imagePixels: 1200, hint: 'A table stand or a poster.' },
];

export const layoutById = (id: string): SheetLayout => LAYOUTS.find((l) => l.id === id) ?? LAYOUTS[0];

/** Splits a list into pages of at most `size`. A size below 1 is treated as 1. */
export function chunk<T>(items: readonly T[], size: number): T[][] {
  const n = Math.max(1, Math.floor(size));
  const pages: T[][] = [];
  for (let i = 0; i < items.length; i += n) pages.push(items.slice(i, i + n));
  return pages;
}

export const pageCount = (cards: number, perPage: number): number =>
  cards <= 0 ? 0 : Math.ceil(cards / Math.max(1, Math.floor(perPage)));

/**
 * Runs `fn` over every item with at most `limit` in flight at once, keeping the results in the ORDER of the
 * items. One failure never stops the others: every item gets a settled result.
 */
export async function mapLimit<T, R>(
  items: readonly T[],
  limit: number,
  fn: (item: T, index: number) => Promise<R>,
): Promise<PromiseSettledResult<R>[]> {
  const results: PromiseSettledResult<R>[] = new Array(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const index = next++;
      try {
        results[index] = { status: 'fulfilled', value: await fn(items[index], index) };
      } catch (reason) {
        results[index] = { status: 'rejected', reason };
      }
    }
  }
  const workers = Math.max(1, Math.min(Math.floor(limit) || 1, items.length));
  await Promise.all(Array.from({ length: workers }, worker));
  return results;
}

/** Adds the id if absent, removes it if present, without changing the set it was given. */
export function toggled(selected: ReadonlySet<number>, id: number): Set<number> {
  const next = new Set(selected);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  return next;
}

/** "3 sheets" / "1 sheet" for a number of cards on a layout; "Nothing to print" for none. */
export function sheetsText(cards: number, perPage: number): string {
  const n = pageCount(cards, perPage);
  return n === 0 ? 'Nothing to print' : `${n} ${n === 1 ? 'sheet' : 'sheets'} of paper`;
}
