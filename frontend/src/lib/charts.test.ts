import { describe, expect, it } from 'vitest';
import {
  areaPath,
  bucketLabel,
  businessDateTime,
  compactMoney,
  donutSlices,
  hourLabel,
  intensity,
  labelEvery,
  linePath,
  niceScale,
  percent,
  points,
  signedPercent,
  trendOf,
  weekdayLabel,
} from './charts';

const METHODS = [
  { key: 'UPI', label: 'UPI', value: 111270 },
  { key: 'CASH', label: 'Cash', value: 46363 },
  { key: 'CARD', label: 'Card', value: 20000 },
  { key: 'WALLET', label: 'Wallet', value: 7817 },
];

const RING = { cx: 100, cy: 100, outerRadius: 80, innerRadius: 52 };

describe('donutSlices', () => {
  it('turns amounts into shares that add up to 100%', () => {
    const slices = donutSlices(METHODS, RING);
    expect(slices.map((s) => s.key)).toEqual(['UPI', 'CASH', 'CARD', 'WALLET']);
    expect(slices.map((s) => s.percent)).toEqual([60, 25, 10.8, 4.2]);
    expect(slices.reduce((sum, s) => sum + s.percent, 0)).toBeCloseTo(100, 1);
  });

  it('drops methods with no money so there are no invisible slivers', () => {
    const slices = donutSlices([...METHODS, { key: 'CARD2', label: 'Other', value: 0 }], RING);
    expect(slices).toHaveLength(4);
  });

  it('draws a closed ring when one method took everything', () => {
    const slices = donutSlices([{ key: 'CASH', label: 'Cash', value: 500 }], RING);
    expect(slices).toHaveLength(1);
    expect(slices[0].percent).toBe(100);
    // Two arcs for the outer edge and two for the inner: a single arc back to its start draws nothing.
    expect(slices[0].d.match(/A /g)).toHaveLength(4);
    expect(slices[0].d.endsWith('Z')).toBe(true);
  });

  it('has nothing to draw with no data', () => {
    expect(donutSlices([], RING)).toEqual([]);
    expect(donutSlices([{ key: 'CASH', label: 'Cash', value: 0 }], RING)).toEqual([]);
  });

  it('starts at the top and runs clockwise', () => {
    const [first] = donutSlices([
      { key: 'A', label: 'A', value: 25 },
      { key: 'B', label: 'B', value: 75 },
    ], RING);
    // A quarter slice from 12 o'clock has its midpoint up and to the right of the centre.
    expect(first.mid.x).toBeGreaterThan(RING.cx);
    expect(first.mid.y).toBeLessThan(RING.cy);
  });
});

describe('niceScale', () => {
  it('rounds the axis top up to a readable number', () => {
    expect(niceScale(4973).max).toBe(5000);
    expect(niceScale(1850).max).toBe(2000);
    expect(niceScale(111270).max).toBe(125000);
    expect(niceScale(7).max).toBe(8);
  });

  it('always covers the largest value and starts at zero', () => {
    for (const value of [1, 9, 99, 1234, 185450, 9_999_999]) {
      const scale = niceScale(value);
      expect(scale.max).toBeGreaterThanOrEqual(value);
      expect(scale.ticks[0]).toBe(0);
      expect(scale.ticks[scale.ticks.length - 1]).toBe(scale.max);
    }
  });

  it('survives an empty chart', () => {
    expect(niceScale(0)).toEqual({ max: 1, ticks: [0, 1] });
    expect(niceScale(Number.NaN).max).toBe(1);
  });
});

describe('points and paths', () => {
  it('maps values into the plot box with zero at the bottom', () => {
    const pts = points([0, 50, 100], { width: 200, height: 100, max: 100 });
    expect(pts).toEqual([
      { x: 0, y: 100 },
      { x: 100, y: 50 },
      { x: 200, y: 0 },
    ]);
  });

  it('respects the plot offset', () => {
    const [p] = points([100], { width: 200, height: 100, max: 100, left: 40, top: 10 });
    expect(p).toEqual({ x: 40, y: 10 });
  });

  it('draws a visible mark for a single bucket', () => {
    expect(linePath(points([5], { width: 200, height: 100, max: 10 }))).toBe('M 0 50 L 0 50');
    expect(linePath([])).toBe('');
  });

  it('closes an area down to the baseline', () => {
    const d = areaPath(points([10, 0], { width: 100, height: 50, max: 10 }), 50);
    expect(d.startsWith('M 0 0 L 100 50')).toBe(true);
    expect(d.endsWith('L 100 50 L 0 50 Z')).toBe(true);
  });
});

describe('intensity', () => {
  it('leaves empty cells blank and never exceeds full', () => {
    expect(intensity(0, 100)).toBe(0);
    expect(intensity(100, 100)).toBe(1);
    expect(intensity(500, 100)).toBe(1);
    expect(intensity(5, 0)).toBe(0);
  });

  it('keeps small hours visible next to a dominant one', () => {
    // Linear shading would make this 1% and effectively invisible.
    expect(intensity(1, 100)).toBe(0.1);
  });
});

describe('compactMoney', () => {
  it('uses lakh and crore for rupees', () => {
    expect(compactMoney(850)).toBe('₹850');
    expect(compactMoney(1850)).toBe('₹1.9k');
    expect(compactMoney(185000)).toBe('₹1.9L');
    expect(compactMoney(20_000_000)).toBe('₹2Cr');
    expect(compactMoney(-1500)).toBe('-₹1.5k');
    expect(compactMoney(0)).toBe('₹0');
  });

  it('uses thousands and millions elsewhere', () => {
    expect(compactMoney(1_500_000, 'USD')).toBe('USD 1.5M');
    expect(compactMoney(250, 'USD')).toBe('USD 250');
  });
});

describe('percentages and trends', () => {
  it('drops trailing zeros', () => {
    expect(percent(64)).toBe('64%');
    expect(percent(42.5)).toBe('42.5%');
    expect(percent(0)).toBe('0%');
  });

  it('says so when there is nothing to compare against', () => {
    expect(percent(null)).toBe('—');
    expect(signedPercent(null)).toBe('No comparison');
    expect(trendOf(null)).toBe('none');
  });

  it('signs a change and names its direction', () => {
    expect(signedPercent(18)).toBe('+18%');
    expect(signedPercent(-25.4)).toBe('-25.4%');
    expect(signedPercent(0)).toBe('No change');
    expect(trendOf(18)).toBe('up');
    expect(trendOf(-18)).toBe('down');
    expect(trendOf(0.01)).toBe('flat');
  });
});

describe('axis labels', () => {
  it('labels buckets the way the grouping reads', () => {
    expect(bucketLabel('2026-10-13T20:00:00', 'HOURLY')).toBe('8 PM');
    expect(bucketLabel('2026-10-13T00:00:00', 'DAILY')).toBe('13 Oct');
    expect(bucketLabel('2026-10-12T00:00:00', 'WEEKLY')).toBe('w/c 12 Oct');
    expect(bucketLabel('2026-10-01T00:00:00', 'MONTHLY')).toBe('Oct 26');
    expect(bucketLabel('2026-01-01T00:00:00', 'YEARLY')).toBe('2026');
  });

  it('shows the business hour the server grouped by, not the viewer local hour', () => {
    // A value with no offset must not be shifted by the device time zone.
    expect(bucketLabel('2026-10-13T20:00:00', 'HOURLY')).toBe('8 PM');
    expect(businessDateTime('2026-10-13T20:30:00')).toBe('13 Oct 2026, 20:30');
  });

  it('reads the hour the restaurant saw when the server sends its offset', () => {
    // The server now sends the business offset. 8 PM in Kolkata must still read as 8 PM, and must
    // not be re-rendered in the viewer's zone - the mistake that made a fresh order look hours old.
    expect(bucketLabel('2026-10-13T20:00:00+05:30', 'HOURLY')).toBe('8 PM');
    expect(bucketLabel('2026-10-13T00:30:00+05:30', 'HOURLY')).toBe('12 AM');
    expect(bucketLabel('2026-10-13T00:30:00+05:30', 'DAILY')).toBe('13 Oct');
    expect(businessDateTime('2026-10-13T20:30:00+05:30')).toBe('13 Oct 2026, 20:30');
    expect(bucketLabel('2026-01-01T00:00:00+05:30', 'YEARLY')).toBe('2026');
  });

  it('falls back to the raw value it cannot read', () => {
    expect(bucketLabel('not-a-date', 'DAILY')).toBe('not-a-date');
  });

  it('names hours and weekdays', () => {
    expect(hourLabel(0)).toBe('12 AM');
    expect(hourLabel(12)).toBe('12 PM');
    expect(hourLabel(23)).toBe('11 PM');
    expect(weekdayLabel(0)).toBe('Mon');
    expect(weekdayLabel(6)).toBe('Sun');
  });

  it('thins crowded axes', () => {
    expect(labelEvery(7, 7)).toBe(1);
    expect(labelEvery(30, 7)).toBe(5);
    expect(labelEvery(24, 6)).toBe(4);
  });
});
