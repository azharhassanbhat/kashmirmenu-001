/**
 * Geometry and formatting for the hand-drawn SVG charts on the Payments & Revenue page.
 *
 * Everything here is pure, so the chart maths is unit tested without a browser. The page draws its
 * own SVG rather than pulling in a chart library: the bundle is served to diners as well as staff,
 * and a library would be a large dependency for six charts.
 */

export interface DonutSlice {
  key: string;
  label: string;
  value: number;
  /** Path for a doughnut segment, ready for a <path d>. */
  d: string;
  /** Mid-angle point on the ring, for a label or tooltip anchor. */
  mid: { x: number; y: number };
  percent: number;
}

const TAU = Math.PI * 2;

function pointOnCircle(cx: number, cy: number, r: number, fraction: number) {
  // Start at 12 o'clock and run clockwise, which is how people read a share chart.
  const angle = fraction * TAU - Math.PI / 2;
  return { x: cx + r * Math.cos(angle), y: cy + r * Math.sin(angle) };
}

function round(n: number): number {
  return Math.round(n * 100) / 100;
}

/**
 * Doughnut segments for the payment-method split. Values of zero are dropped, so a method with no
 * money never leaves an invisible sliver or a stray label.
 */
export function donutSlices(
  data: { key: string; label: string; value: number }[],
  opts: { cx: number; cy: number; outerRadius: number; innerRadius: number; gap?: number },
): DonutSlice[] {
  const { cx, cy, outerRadius: ro, innerRadius: ri } = opts;
  const gap = opts.gap ?? 0;
  const usable = data.filter((d) => d.value > 0);
  const total = usable.reduce((sum, d) => sum + d.value, 0);
  if (total <= 0) return [];

  // A single slice would be a full circle: an arc from a point back to itself draws nothing, so it
  // is built from two half arcs instead.
  const slices: DonutSlice[] = [];
  let cursor = 0;

  for (const d of usable) {
    const fraction = d.value / total;
    const start = cursor;
    const end = cursor + fraction;
    cursor = end;

    // Trim both ends by half the gap, but never past the middle of a thin slice.
    const gapFraction = Math.min(gap / 360, fraction / 4);
    const a0 = start + gapFraction;
    const a1 = end - gapFraction;
    const mid = pointOnCircle(cx, cy, (ro + ri) / 2, (start + end) / 2);

    let d_: string;
    if (usable.length === 1) {
      const outerTop = pointOnCircle(cx, cy, ro, 0);
      const outerBottom = pointOnCircle(cx, cy, ro, 0.5);
      const innerTop = pointOnCircle(cx, cy, ri, 0);
      const innerBottom = pointOnCircle(cx, cy, ri, 0.5);
      d_ = [
        `M ${round(outerTop.x)} ${round(outerTop.y)}`,
        `A ${ro} ${ro} 0 1 1 ${round(outerBottom.x)} ${round(outerBottom.y)}`,
        `A ${ro} ${ro} 0 1 1 ${round(outerTop.x)} ${round(outerTop.y)}`,
        `M ${round(innerTop.x)} ${round(innerTop.y)}`,
        `A ${ri} ${ri} 0 1 0 ${round(innerBottom.x)} ${round(innerBottom.y)}`,
        `A ${ri} ${ri} 0 1 0 ${round(innerTop.x)} ${round(innerTop.y)}`,
        'Z',
      ].join(' ');
    } else {
      const o0 = pointOnCircle(cx, cy, ro, a0);
      const o1 = pointOnCircle(cx, cy, ro, a1);
      const i1 = pointOnCircle(cx, cy, ri, a1);
      const i0 = pointOnCircle(cx, cy, ri, a0);
      const largeArc = a1 - a0 > 0.5 ? 1 : 0;
      d_ = [
        `M ${round(o0.x)} ${round(o0.y)}`,
        `A ${ro} ${ro} 0 ${largeArc} 1 ${round(o1.x)} ${round(o1.y)}`,
        `L ${round(i1.x)} ${round(i1.y)}`,
        `A ${ri} ${ri} 0 ${largeArc} 0 ${round(i0.x)} ${round(i0.y)}`,
        'Z',
      ].join(' ');
    }

    slices.push({
      key: d.key,
      label: d.label,
      value: d.value,
      d: d_,
      mid: { x: round(mid.x), y: round(mid.y) },
      percent: Math.round(fraction * 1000) / 10,
    });
  }
  return slices;
}

/**
 * A "nice" axis maximum and its tick values: rounded up to 1, 2, 2.5 or 5 times a power of ten so
 * the gridlines land on readable amounts instead of 4,973.
 */
export function niceScale(max: number, ticks = 4): { max: number; ticks: number[] } {
  if (!Number.isFinite(max) || max <= 0) {
    return { max: 1, ticks: [0, 1] };
  }
  // Candidate steps are the readable ones (1, 2, 2.5, 5 and their powers of ten). Of those, take the
  // step whose gridline count is closest to the requested one, preferring the tighter axis on a tie
  // so a 4,973 peak gets a 5,000 axis rather than a 6,000 one.
  const magnitude = 10 ** Math.floor(Math.log10(max / ticks));
  let step = magnitude;
  let top = Math.ceil(max / step) * step;
  let best = Number.POSITIVE_INFINITY;

  for (const multiple of [1, 2, 2.5, 5, 10, 20]) {
    const candidate = multiple * magnitude;
    const candidateTop = Math.ceil(max / candidate) * candidate;
    const intervals = Math.round(candidateTop / candidate);
    if (intervals < 2) continue;
    const score = Math.abs(intervals - ticks);
    if (score < best || (score === best && candidateTop < top)) {
      best = score;
      step = candidate;
      top = candidateTop;
    }
  }
  const out: number[] = [];
  for (let v = 0; v <= top + step / 1000; v += step) {
    out.push(round(v));
  }
  return { max: round(top), ticks: out };
}

/** Maps a series to x/y points inside a plot box. A single point sits at the left edge. */
export function points(
  values: number[],
  opts: { width: number; height: number; max: number; left?: number; top?: number },
): { x: number; y: number }[] {
  const left = opts.left ?? 0;
  const top = opts.top ?? 0;
  const max = opts.max > 0 ? opts.max : 1;
  const step = values.length > 1 ? opts.width / (values.length - 1) : 0;
  return values.map((v, i) => ({
    x: round(left + i * step),
    y: round(top + opts.height - (Math.max(0, v) / max) * opts.height),
  }));
}

export function linePath(pts: { x: number; y: number }[]): string {
  if (pts.length === 0) return '';
  if (pts.length === 1) return `M ${pts[0].x} ${pts[0].y} L ${pts[0].x} ${pts[0].y}`;
  return pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x} ${p.y}`).join(' ');
}

/** Closes a line down to the baseline so it can be filled as an area. */
export function areaPath(pts: { x: number; y: number }[], baselineY: number): string {
  if (pts.length === 0) return '';
  const first = pts[0];
  const last = pts[pts.length - 1];
  return `${linePath(pts)} L ${last.x} ${round(baselineY)} L ${first.x} ${round(baselineY)} Z`;
}

/**
 * Heatmap intensity from 0 to 1. Uses a square root so a single huge hour does not flatten every
 * other cell to invisibility, and returns 0 for an empty cell so it stays blank rather than tinted.
 */
export function intensity(value: number, max: number): number {
  if (value <= 0 || max <= 0) return 0;
  return round(Math.min(1, Math.sqrt(value / max)));
}

/** Short money for axis ticks and dense labels: 1850 -> ₹1.9k, 185000 -> ₹1.9L, 2e7 -> ₹2Cr. */
export function compactMoney(value: number, currency = 'INR'): string {
  const symbol = currency === 'INR' ? '₹' : '';
  const prefix = symbol || `${currency} `;
  const n = Math.abs(value);
  const sign = value < 0 ? '-' : '';
  const trim = (x: number) => String(Math.round(x * 10) / 10);

  if (currency === 'INR') {
    // Indian units, as an Indian restaurant owner reads them.
    if (n >= 1e7) return `${sign}${prefix}${trim(n / 1e7)}Cr`;
    if (n >= 1e5) return `${sign}${prefix}${trim(n / 1e5)}L`;
    if (n >= 1000) return `${sign}${prefix}${trim(n / 1000)}k`;
  } else {
    if (n >= 1e9) return `${sign}${prefix}${trim(n / 1e9)}B`;
    if (n >= 1e6) return `${sign}${prefix}${trim(n / 1e6)}M`;
    if (n >= 1000) return `${sign}${prefix}${trim(n / 1000)}k`;
  }
  return `${sign}${prefix}${Math.round(n)}`;
}

/** "64%", "42.5%", "0%". Trailing zeros are dropped so shares read cleanly. */
export function percent(value: number | null | undefined): string {
  if (value == null) return '—';
  const rounded = Math.round(value * 10) / 10;
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)}%`;
}

/** A signed change for a trend indicator. null (no comparison base) reads as "no comparison". */
export function signedPercent(value: number | null | undefined): string {
  if (value == null) return 'No comparison';
  const rounded = Math.round(value * 10) / 10;
  const body = Number.isInteger(rounded) ? String(Math.abs(rounded)) : Math.abs(rounded).toFixed(1);
  if (rounded === 0) return 'No change';
  return `${rounded > 0 ? '+' : '-'}${body}%`;
}

export type Trend = 'up' | 'down' | 'flat' | 'none';

export function trendOf(value: number | null | undefined): Trend {
  if (value == null) return 'none';
  if (Math.abs(value) < 0.05) return 'flat';
  return value > 0 ? 'up' : 'down';
}

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

export function weekdayLabel(index: number): string {
  return WEEKDAYS[index] ?? '';
}

/** 0 -> "12 AM", 13 -> "1 PM". Matches the wording the server uses in its insights. */
export function hourLabel(hour: number): string {
  const h = ((hour % 24) + 24) % 24;
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12} ${h < 12 ? 'AM' : 'PM'}`;
}

/**
 * Axis label for a bucket, phrased for the grouping the server used.
 *
 * Buckets are business-local wall-clock values: an "8 PM" bucket must read as 8 PM whatever zone the
 * viewer's device is in, because that is the hour the server grouped by. So the label is formatted
 * in the bucket's own zone rather than the device's.
 */
export function bucketLabel(iso: string, granularity: string): string {
  const parsed = bucketParts(iso);
  if (!parsed) return iso;
  const { date, zone } = parsed;
  const opts: Intl.DateTimeFormatOptions = { timeZone: zone };
  switch (granularity) {
    case 'HOURLY':
      return hourLabel(Number(new Intl.DateTimeFormat('en-GB', { ...opts, hour: '2-digit', hour12: false }).format(date)));
    case 'WEEKLY':
      return `w/c ${new Intl.DateTimeFormat('en-GB', { ...opts, day: 'numeric', month: 'short' }).format(date)}`;
    case 'MONTHLY':
      return new Intl.DateTimeFormat('en-GB', { ...opts, month: 'short', year: '2-digit' }).format(date);
    case 'YEARLY':
      return new Intl.DateTimeFormat('en-GB', { ...opts, year: 'numeric' }).format(date);
    default:
      return new Intl.DateTimeFormat('en-GB', { ...opts, day: 'numeric', month: 'short' }).format(date);
  }
}

/** Full date and time for a transaction row, shown in the restaurant's own time. */
export function businessDateTime(iso: string): string {
  const parsed = bucketParts(iso);
  if (!parsed) return iso;
  return new Intl.DateTimeFormat('en-GB', {
    timeZone: parsed.zone,
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(parsed.date);
}

/**
 * Resolves a server timestamp to an instant plus the zone its wall-clock reading belongs to.
 *
 * With an offset ("2026-10-13T20:00:00+05:30") the instant is exact, and the zone is that fixed
 * offset, so formatting reproduces the server's own 8 PM. Without one, the value is pinned to UTC
 * and read back in UTC, which has the same effect.
 */
function bucketParts(iso: string): { date: Date; zone: string } | null {
  const match = /([+-])(\d{2}):?(\d{2})$/.exec(iso);
  const hasZone = match != null || iso.endsWith('Z');
  const value = hasZone ? iso : `${iso.length === 16 ? `${iso}:00` : iso}Z`;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  // Intl accepts a fixed-offset zone, which is what an offset-carrying timestamp denotes.
  const zone = match ? `${match[1]}${match[2]}:${match[3]}` : 'UTC';
  return { date, zone };
}

/** Thins axis labels so they never collide on a narrow screen. */
export function labelEvery(count: number, maxLabels: number): number {
  if (count <= maxLabels || maxLabels <= 0) return 1;
  return Math.ceil(count / maxLabels);
}
