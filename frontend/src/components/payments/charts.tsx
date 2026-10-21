import { useId, useState, type ReactNode } from 'react';
import {
  areaPath,
  bucketLabel,
  compactMoney,
  donutSlices,
  hourLabel,
  intensity,
  labelEvery,
  linePath,
  niceScale,
  percent,
  points,
  weekdayLabel,
} from '../../lib/charts';
import { money } from '../../lib/format';

/**
 * Charts for the Payments & Revenue page, drawn as plain SVG.
 *
 * They are deliberately dependency-free: this bundle is also served to diners scanning a QR code, so
 * a charting library would be a large download for six charts. Each chart scales with its container
 * (viewBox + preserveAspectRatio) and stays usable without a pointer: values are in the markup as
 * text or title elements, series toggles are real buttons, and the colour of a method is always
 * paired with its label rather than carrying meaning alone.
 */

/** Colour per payment method, assigned from a fixed palette with a stable fallback for new methods. */
export function methodColor(key: string): string {
  const named: Record<string, string> = {
    CASH: 'var(--pm-cash)',
    UPI: 'var(--pm-upi)',
    CARD: 'var(--pm-card)',
    WALLET: 'var(--pm-wallet)',
  };
  if (named[key]) return named[key];
  // Methods the server added later (vouchers, bank transfer, ...) cycle through the spare ramp.
  const spares = ['var(--pm-other-1)', 'var(--pm-other-2)', 'var(--pm-other-3)'];
  let hash = 0;
  for (let i = 0; i < key.length; i++) hash = (hash * 31 + key.charCodeAt(i)) % 997;
  return spares[hash % spares.length];
}

/** Providers are shaded from their parent method, so a UPI app always reads as UPI. */
export function providerColor(method: string, index: number, total: number): string {
  const base = methodColor(method);
  const mix = total <= 1 ? 100 : 100 - Math.round((index / total) * 55);
  return `color-mix(in srgb, ${base} ${mix}%, var(--surface))`;
}

export function ChartFrame({
  title,
  hint,
  children,
  actions,
  empty,
}: {
  title: string;
  hint?: string;
  children: ReactNode;
  actions?: ReactNode;
  empty?: boolean;
}) {
  return (
    <section className="pay-chart card" aria-label={title}>
      <header className="pay-chart-head">
        <div>
          <h3>{title}</h3>
          {hint && <p className="muted">{hint}</p>}
        </div>
        {actions}
      </header>
      {empty ? <p className="pay-chart-empty muted">No payments in this period yet.</p> : children}
    </section>
  );
}

/* ------------------------------------------------------------------ 1. method split */

export function MethodDonut({
  rows,
  total,
  currency,
  selected,
  onSelect,
}: {
  rows: { key: string; label: string; gross: number; shareOfCollected: number; transactions: number }[];
  total: number;
  currency: string;
  selected: string[];
  onSelect: (key: string) => void;
}) {
  const slices = donutSlices(
    rows.map((r) => ({ key: r.key, label: r.label, value: r.gross })),
    { cx: 110, cy: 110, outerRadius: 96, innerRadius: 62, gap: 2 },
  );

  return (
    <div className="pay-donut">
      <svg viewBox="0 0 220 220" className="pay-donut-svg" role="img"
        aria-label={`Collected ${money(total)} split by payment method`}>
        {slices.map((s) => {
          const dimmed = selected.length > 0 && !selected.includes(s.key);
          return (
            <path
              key={s.key}
              d={s.d}
              fill={methodColor(s.key)}
              className={`pay-slice${dimmed ? ' dimmed' : ''}`}
              opacity={dimmed ? 0.25 : 1}
            >
              <title>{`${s.label}: ${money(s.value)} (${percent(s.percent)})`}</title>
            </path>
          );
        })}
        <text x="110" y="104" className="pay-donut-value" textAnchor="middle">
          {compactMoney(total, currency)}
        </text>
        <text x="110" y="126" className="pay-donut-label" textAnchor="middle">
          collected
        </text>
      </svg>

      <ul className="pay-legend">
        {rows.map((r) => {
          const active = selected.includes(r.key);
          return (
            <li key={r.key}>
              <button
                type="button"
                className={`pay-legend-row${active ? ' active' : ''}`}
                aria-pressed={active}
                onClick={() => onSelect(r.key)}
              >
                <span className="pay-swatch" style={{ background: methodColor(r.key) }} aria-hidden="true" />
                <span className="pay-legend-name">{r.label}</span>
                <span className="pay-legend-value">{money(r.gross)}</span>
                <span className="pay-legend-share muted">{percent(r.shareOfCollected)}</span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

interface ProviderRowData {
  key: string;
  label: string;
  known: boolean;
  gross: number;
  transactions: number;
  shareOfMethod: number;
}

function ProviderRowButton({
  row,
  active,
  onSelect,
  fill,
  max,
  currency,
}: {
  row: ProviderRowData;
  active: boolean;
  onSelect: (key: string) => void;
  fill: string;
  max: number;
  currency: string;
}) {
  return (
    <li>
      <button
        type="button"
        className={`pay-provider-row${active ? ' active' : ''}`}
        aria-pressed={active}
        onClick={() => onSelect(row.key)}
        title={`${row.transactions} ${row.transactions === 1 ? 'payment' : 'payments'}`}
      >
        <span className={`pay-provider-name${row.known ? '' : ' unknown'}`}>{row.label}</span>
        <span className="pay-provider-track" aria-hidden="true">
          <span
            className="pay-provider-fill"
            style={{ width: max > 0 ? `${Math.max(2, (row.gross / max) * 100)}%` : '2%', background: fill }}
          />
        </span>
        <span className="pay-provider-value">
          {compactMoney(row.gross, currency)}
          <span className="muted"> · {percent(row.shareOfMethod)}</span>
        </span>
      </button>
    </li>
  );
}

/* ------------------------------------------------------------------ 2. provider breakdown */

export function ProviderBars({
  rows,
  currency,
  onSelect,
  selected,
}: {
  rows: {
    key: string;
    label: string;
    method: string;
    methodLabel: string;
    known: boolean;
    gross: number;
    transactions: number;
    shareOfMethod: number;
    pendingCount: number;
    failedCount: number;
  }[];
  currency: string;
  onSelect: (key: string) => void;
  selected: string[];
}) {
  const groups = Array.from(new Set(rows.map((r) => r.method)));

  return (
    <div className="pay-providers">
      {groups.map((method) => {
        const inGroup = rows.filter((r) => r.method === method);
        const max = Math.max(...inGroup.map((r) => r.gross), 0);
        const label = inGroup[0]?.methodLabel ?? method;
        // An app with no money but a pending or failed payment is still worth seeing.
        const used = inGroup.filter((r) => r.gross > 0 || r.transactions > 0 || r.pendingCount > 0 || r.failedCount > 0);
        const unused = inGroup.filter((r) => !used.includes(r));
        return (
          <div className="pay-provider-group" key={method}>
            <h4>
              {label}
              <span className="muted"> · {money(inGroup.reduce((sum, r) => sum + r.gross, 0))}</span>
            </h4>
            <ul>
              {used.map((r, i) => (
                <ProviderRowButton
                  key={r.key}
                  row={r}
                  active={selected.includes(r.key)}
                  onSelect={onSelect}
                  fill={providerColor(method, i, inGroup.length)}
                  max={max}
                  currency={currency}
                />
              ))}
            </ul>

            {unused.length > 0 && (
              <details className="pay-provider-unused">
                <summary>
                  {unused.length} {unused.length === 1 ? 'app took' : 'apps took'} nothing in this period
                </summary>
                <ul>
                  {unused.map((r) => (
                    <ProviderRowButton
                      key={r.key}
                      row={r}
                      active={selected.includes(r.key)}
                      onSelect={onSelect}
                      fill={providerColor(method, inGroup.length - 1, inGroup.length)}
                      max={max}
                      currency={currency}
                    />
                  ))}
                </ul>
              </details>
            )}
            {inGroup.some((r) => !r.known && r.gross > 0) && (
              <p className="pay-note muted">
                Payments with no app recorded stay unknown rather than being guessed. Pick the app when you
                confirm a payment to fill this in.
              </p>
            )}
          </div>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ 3 & 5. revenue and volume over time */

const PLOT = { left: 54, top: 12, width: 586, height: 196 };

export function TrendChart({
  buckets,
  rows,
  granularity,
  currency,
  mode,
}: {
  buckets: string[];
  rows: { key: string; label: string; amounts: number[]; counts: number[] }[];
  granularity: string;
  currency: string;
  mode: 'amount' | 'count';
}) {
  const [hidden, setHidden] = useState<string[]>([]);
  const gradientId = useId();
  const visible = rows.filter((r) => !hidden.includes(r.key));
  const series = visible.map((r) => ({ ...r, values: mode === 'amount' ? r.amounts : r.counts }));

  const peak = Math.max(0, ...series.flatMap((s) => s.values));
  const scale = niceScale(peak);
  const every = labelEvery(buckets.length, 8);
  const baseline = PLOT.top + PLOT.height;
  const xAt = (i: number) =>
    PLOT.left + (buckets.length > 1 ? (i * PLOT.width) / (buckets.length - 1) : 0);

  return (
    <div className="pay-trend">
      <ul className="pay-series-toggles">
        {rows.map((r) => {
          const on = !hidden.includes(r.key);
          return (
            <li key={r.key}>
              <button
                type="button"
                className={`pay-toggle${on ? ' on' : ''}`}
                aria-pressed={on}
                onClick={() => setHidden((h) => (on ? [...h, r.key] : h.filter((k) => k !== r.key)))}
              >
                <span className="pay-swatch" style={{ background: methodColor(r.key) }} aria-hidden="true" />
                {r.label}
              </button>
            </li>
          );
        })}
      </ul>

      <svg
        viewBox="0 0 660 250"
        className="pay-svg"
        role="img"
        aria-label={`${mode === 'amount' ? 'Collected amount' : 'Number of payments'} per ${granularity.toLowerCase()} bucket, by payment method`}
      >
        <defs>
          {series.map((s) => (
            <linearGradient key={s.key} id={`${gradientId}-${s.key}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={methodColor(s.key)} stopOpacity="0.28" />
              <stop offset="100%" stopColor={methodColor(s.key)} stopOpacity="0" />
            </linearGradient>
          ))}
        </defs>

        {scale.ticks.map((t) => {
          const y = baseline - (t / scale.max) * PLOT.height;
          return (
            <g key={t}>
              <line x1={PLOT.left} y1={y} x2={PLOT.left + PLOT.width} y2={y} className="pay-grid" />
              <text x={PLOT.left - 8} y={y + 4} className="pay-axis" textAnchor="end">
                {mode === 'amount' ? compactMoney(t, currency) : Math.round(t)}
              </text>
            </g>
          );
        })}

        {series.map((s) => {
          const pts = points(s.values, { ...PLOT, max: scale.max });
          return (
            <g key={s.key}>
              {series.length <= 2 && (
                <path d={areaPath(pts, baseline)} fill={`url(#${gradientId}-${s.key})`} />
              )}
              <path d={linePath(pts)} fill="none" stroke={methodColor(s.key)} strokeWidth="2.5"
                strokeLinejoin="round" strokeLinecap="round" />
              {pts.map((p, i) => (
                <circle key={i} cx={p.x} cy={p.y} r={buckets.length > 40 ? 0 : 3}
                  fill="var(--surface)" stroke={methodColor(s.key)} strokeWidth="2">
                  <title>
                    {`${bucketLabel(buckets[i], granularity)} · ${s.label}: ${
                      mode === 'amount' ? money(s.values[i]) : `${s.values[i]} payments`
                    }`}
                  </title>
                </circle>
              ))}
            </g>
          );
        })}

        {buckets.map((b, i) =>
          i % every === 0 ? (
            <text key={b} x={xAt(i)} y={baseline + 22} className="pay-axis" textAnchor="middle">
              {bucketLabel(b, granularity)}
            </text>
          ) : null,
        )}
      </svg>
    </div>
  );
}

/* ------------------------------------------------------------------ 4. this period vs last */

export function ComparisonBars({
  rows,
  currency,
  previousLabel,
}: {
  rows: { key: string; label: string; gross: number; previousGross: number }[];
  currency: string;
  previousLabel: string;
}) {
  const max = Math.max(0, ...rows.flatMap((r) => [r.gross, r.previousGross]));
  const scale = niceScale(max);
  const baseline = PLOT.top + PLOT.height;
  const slot = rows.length > 0 ? PLOT.width / rows.length : PLOT.width;
  const barWidth = Math.min(34, (slot - 24) / 2);

  return (
    <div className="pay-compare">
      <p className="pay-compare-key">
        <span className="pay-swatch solid" aria-hidden="true" /> This period
        <span className="pay-swatch hatched" aria-hidden="true" /> {previousLabel}
      </p>
      <svg viewBox="0 0 660 250" className="pay-svg" role="img"
        aria-label="Collected amount per payment method, this period against the previous one">
        <defs>
          <pattern id="pay-hatch" width="6" height="6" patternTransform="rotate(45)" patternUnits="userSpaceOnUse">
            <rect width="6" height="6" fill="var(--surface-2)" />
            <line x1="0" y1="0" x2="0" y2="6" stroke="var(--border-strong)" strokeWidth="3" />
          </pattern>
        </defs>

        {scale.ticks.map((t) => {
          const y = baseline - (t / scale.max) * PLOT.height;
          return (
            <g key={t}>
              <line x1={PLOT.left} y1={y} x2={PLOT.left + PLOT.width} y2={y} className="pay-grid" />
              <text x={PLOT.left - 8} y={y + 4} className="pay-axis" textAnchor="end">
                {compactMoney(t, currency)}
              </text>
            </g>
          );
        })}

        {rows.map((r, i) => {
          const centre = PLOT.left + slot * i + slot / 2;
          const h = (v: number) => (scale.max > 0 ? (Math.max(0, v) / scale.max) * PLOT.height : 0);
          const current = h(r.gross);
          const previous = h(r.previousGross);
          return (
            <g key={r.key}>
              <rect x={centre - barWidth - 3} y={baseline - current} width={barWidth} height={current}
                rx="4" fill={methodColor(r.key)}>
                <title>{`${r.label}, this period: ${money(r.gross)}`}</title>
              </rect>
              <rect x={centre + 3} y={baseline - previous} width={barWidth} height={previous}
                rx="4" fill="url(#pay-hatch)" stroke="var(--border-strong)">
                <title>{`${r.label}, ${previousLabel}: ${money(r.previousGross)}`}</title>
              </rect>
              <text x={centre} y={baseline + 22} className="pay-axis" textAnchor="middle">
                {r.label}
              </text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

/* ------------------------------------------------------------------ 6. peak hours */

export function PeakHours({
  amounts,
  counts,
  hourlyAmounts,
  currency,
}: {
  amounts: number[][];
  counts: number[][];
  hourlyAmounts: number[];
  currency: string;
}) {
  const max = Math.max(0, ...amounts.flat());
  const busiestHour = hourlyAmounts.indexOf(Math.max(...hourlyAmounts));

  return (
    <div className="pay-heat">
      <div className="pay-heat-grid" role="table" aria-label="Collected amount by weekday and hour">
        <div className="pay-heat-hours" aria-hidden="true">
          {Array.from({ length: 24 }, (_, h) =>
            h % 3 === 0 ? (
              <span key={h} style={{ gridColumn: h + 1 }}>
                {hourLabel(h)}
              </span>
            ) : null,
          )}
        </div>
        {amounts.map((row, day) => (
          <div className="pay-heat-row" role="row" key={day}>
            <span className="pay-heat-day" role="rowheader">
              {weekdayLabel(day)}
            </span>
            {row.map((value, hour) => {
              const strength = intensity(value, max);
              return (
                <span
                  key={hour}
                  role="cell"
                  className={`pay-heat-cell${value > 0 ? ' filled' : ''}`}
                  style={strength > 0 ? { background: `color-mix(in srgb, var(--pm-heat) ${strength * 100}%, transparent)` } : undefined}
                  title={`${weekdayLabel(day)} ${hourLabel(hour)}: ${money(value)} from ${counts[day][hour]} ${
                    counts[day][hour] === 1 ? 'payment' : 'payments'
                  }`}
                >
                  <span className="sr-only">{`${weekdayLabel(day)} ${hourLabel(hour)}: ${money(value)}`}</span>
                </span>
              );
            })}
          </div>
        ))}
      </div>
      {max > 0 && (
        <p className="pay-note muted">
          Busiest hour across the period: {hourLabel(busiestHour)} to {hourLabel(busiestHour + 1)}, taking{' '}
          {compactMoney(hourlyAmounts[busiestHour], currency)}.
        </p>
      )}
    </div>
  );
}
