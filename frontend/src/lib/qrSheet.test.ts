import { describe, expect, it } from 'vitest';
import { chunk, layoutById, LAYOUTS, mapLimit, pageCount, sheetsText, toggled } from './qrSheet';

describe('laying out a sheet', () => {
  it('offers four layouts, each with a distinct id, a positive page size and column count', () => {
    expect(LAYOUTS).toHaveLength(4);
    expect(new Set(LAYOUTS.map((l) => l.id)).size).toBe(4);
    for (const l of LAYOUTS) {
      expect(l.perPage).toBeGreaterThan(0);
      expect(l.columns).toBeGreaterThan(0);
      expect(l.perPage % l.columns).toBe(0);
    }
  });

  it('asks for a sharper image the bigger a card is printed', () => {
    const px = (id: string) => layoutById(id).imagePixels;
    expect(px('one')).toBeGreaterThan(px('four'));
    expect(px('four')).toBeGreaterThan(px('six'));
    expect(px('six')).toBeGreaterThan(px('twelve'));
    for (const l of LAYOUTS) {
      expect(l.imagePixels).toBeGreaterThanOrEqual(200);
      expect(l.imagePixels).toBeLessThanOrEqual(1200);   // the server's own limits
    }
  });

  it('falls back to the first layout for an unknown id', () => {
    expect(layoutById('nonsense')).toBe(LAYOUTS[0]);
  });

  it('splits cards into pages, keeping order', () => {
    expect(chunk([1, 2, 3, 4, 5, 6, 7], 3)).toEqual([[1, 2, 3], [4, 5, 6], [7]]);
    expect(chunk([1, 2, 3, 4], 2)).toEqual([[1, 2], [3, 4]]);
    expect(chunk([], 6)).toEqual([]);
    expect(chunk([1, 2, 3], 6)).toEqual([[1, 2, 3]]);
  });

  it('treats a page size below one as one, never looping for ever', () => {
    expect(chunk([1, 2], 0)).toEqual([[1], [2]]);
    expect(chunk([1, 2], -3)).toEqual([[1], [2]]);
    expect(chunk([1, 2, 3], 2.9)).toEqual([[1, 2], [3]]);
  });

  it('counts sheets of paper', () => {
    expect(pageCount(0, 6)).toBe(0);
    expect(pageCount(1, 6)).toBe(1);
    expect(pageCount(6, 6)).toBe(1);
    expect(pageCount(7, 6)).toBe(2);
    expect(pageCount(13, 12)).toBe(2);
  });

  it('says it in words', () => {
    expect(sheetsText(0, 6)).toBe('Nothing to print');
    expect(sheetsText(1, 6)).toBe('1 sheet of paper');
    expect(sheetsText(20, 6)).toBe('4 sheets of paper');
  });
});

describe('selecting tables', () => {
  it('adds and removes without touching the original set', () => {
    const start = new Set([1, 2]);
    const added = toggled(start, 3);
    const removed = toggled(start, 2);
    expect([...added].sort()).toEqual([1, 2, 3]);
    expect([...removed]).toEqual([1]);
    expect([...start].sort()).toEqual([1, 2]);
  });
});

describe('fetching many images politely', () => {
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

  it('never has more than the limit in flight', async () => {
    let inFlight = 0;
    let peak = 0;
    await mapLimit([...Array(20).keys()], 4, async () => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      await sleep(2);
      inFlight--;
    });
    expect(peak).toBe(4);
  });

  it('keeps the results in the order of the items, even when later ones finish first', async () => {
    const results = await mapLimit([30, 1, 15, 1], 4, async (ms, i) => {
      await sleep(ms);
      return i;
    });
    expect(results.map((r) => (r.status === 'fulfilled' ? r.value : -1))).toEqual([0, 1, 2, 3]);
  });

  it('lets one failure leave the others untouched', async () => {
    const results = await mapLimit([1, 2, 3, 4], 2, async (n) => {
      if (n === 2) throw new Error('boom');
      return n * 10;
    });
    expect(results[0]).toEqual({ status: 'fulfilled', value: 10 });
    expect(results[1].status).toBe('rejected');
    expect(results[2]).toEqual({ status: 'fulfilled', value: 30 });
    expect(results[3]).toEqual({ status: 'fulfilled', value: 40 });
  });

  it('copes with nothing to do and a nonsense limit', async () => {
    expect(await mapLimit([], 4, async () => 1)).toEqual([]);
    const results = await mapLimit([1, 2, 3], 0, async (n) => n);
    expect(results.map((r) => (r.status === 'fulfilled' ? r.value : -1))).toEqual([1, 2, 3]);
    const nan = await mapLimit([1, 2], Number.NaN, async (n) => n);
    expect(nan).toHaveLength(2);
  });

  it('passes the index to the function', async () => {
    const seen: number[] = [];
    await mapLimit(['a', 'b', 'c'], 1, async (_, i) => {
      seen.push(i);
    });
    expect(seen).toEqual([0, 1, 2]);
  });
});
