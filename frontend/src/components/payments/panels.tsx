import { useState, type ReactNode } from 'react';
import type {
  AnalyticsFilters,
  AnalyticsOverview,
  Granularity,
  PaymentMeta,
  PeriodPreset,
} from '../../api/types';
import { Icon } from '../ui';
import { percent, signedPercent, trendOf } from '../../lib/charts';
import { money, price } from '../../lib/format';
import { methodColor } from './charts';

/* ------------------------------------------------------------------ headline */

/**
 * The page opens on one number — what the restaurant actually collected — with the method split
 * directly beneath it as a single proportional bar, so "how much, and how did people pay" is
 * answered before any chart loads.
 */
export function Headline({ data }: { data: AnalyticsOverview }) {
  const { totals, deltas, period, methods } = data;
  const trend = trendOf(deltas.gross.percent);
  const withMoney = methods.filter((m) => m.gross > 0);

  return (
    <section className="pay-headline" aria-label="Collected this period">
      <div className="pay-headline-main">
        <p className="pay-headline-label">Collected {periodWords(period.preset)}</p>
        <p className="pay-headline-value">{price(totals.gross)}</p>
        <p className={`pay-delta ${trend}`}>
          <TrendMark trend={trend} />
          {signedPercent(deltas.gross.percent)}
          <span className="muted"> vs {money(data.previousTotals.gross)} in the previous period</span>
        </p>
      </div>

      {withMoney.length > 0 && (
        <div className="pay-split">
          <div className="pay-split-bar" role="img" aria-label="Share of collections by payment method">
            {withMoney.map((m) => (
              <span
                key={m.key}
                style={{ width: `${m.shareOfCollected}%`, background: methodColor(m.key) }}
                title={`${m.label}: ${money(m.gross)} (${percent(m.shareOfCollected)})`}
              />
            ))}
          </div>
          <ul className="pay-split-key">
            {withMoney.map((m) => (
              <li key={m.key}>
                <span className="pay-swatch" style={{ background: methodColor(m.key) }} aria-hidden="true" />
                <strong>{m.label}</strong> {money(m.gross)}
                <span className="muted"> · {percent(m.shareOfCollected)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}

function TrendMark({ trend }: { trend: ReturnType<typeof trendOf> }) {
  if (trend === 'none') return null;
  const glyph = trend === 'up' ? 'M3 10l4-5 4 5z' : trend === 'down' ? 'M3 4l4 5 4-5z' : 'M3 7h8';
  return (
    <svg viewBox="0 0 14 14" className="pay-trend-mark" aria-hidden="true" focusable="false">
      <path d={glyph} />
    </svg>
  );
}

/* ------------------------------------------------------------------ KPI cards */

export function KpiCards({ data }: { data: AnalyticsOverview }) {
  const { totals, previousTotals, deltas, sales, open, currency } = {
    ...data,
    currency: data.period.currency,
  };

  const cards: {
    key: string;
    label: string;
    value: string;
    note?: ReactNode;
    percent?: number | null;
    tone?: 'plain' | 'warn';
    /** Shows the change without colouring it, where "up" is not plainly good or bad. */
    neutralDelta?: boolean;
  }[] = [
    {
      key: 'net',
      label: 'Net collections',
      value: money(totals.net),
      note: <>after {money(totals.refunds)} refunded</>,
      percent: deltas.net.percent,
    },
    {
      key: 'transactions',
      label: 'Successful payments',
      value: String(totals.transactions),
      note: (
        <>
          across {totals.uniqueOrders} {totals.uniqueOrders === 1 ? 'order' : 'orders'}
        </>
      ),
      percent: deltas.transactions.percent,
    },
    {
      key: 'average',
      label: 'Average payment',
      value: money(totals.averageTransaction),
      note: <>previously {money(previousTotals.averageTransaction)}</>,
      percent: deltas.averageTransaction.percent,
    },
    {
      key: 'refunds',
      label: 'Refunds & reversals',
      value: money(totals.refunds),
      note: (
        <>
          {totals.refundCount} {totals.refundCount === 1 ? 'refund' : 'refunds'} issued
        </>
      ),
      percent: deltas.refunds.percent,
      neutralDelta: true,
      tone: totals.refunds > 0 ? 'warn' : 'plain',
    },
    {
      key: 'pending',
      label: 'Awaiting confirmation',
      value: money(open.pendingAmount),
      note: (
        <>
          {open.pendingCount} pending · not counted as collected
        </>
      ),
      tone: open.pendingCount > 0 ? 'warn' : 'plain',
    },
    sales
      ? {
          key: 'sales',
          label: 'Gross sales billed',
          value: money(sales.grossSales),
          note: (
            <>
              {sales.ordersPlaced} {sales.ordersPlaced === 1 ? 'order' : 'orders'} placed
              {sales.taxCollected + sales.taxIncluded > 0 && <> · includes {money(sales.taxCollected + sales.taxIncluded)} tax</>}
            </>
          ),
        }
      : {
          key: 'sales',
          label: 'Gross sales billed',
          value: '—',
          note: <>Orders cannot be filtered by payment method</>,
        },
    // Tax is owed onward and is not income: when any was billed, show what the sales were without it.
    ...(sales && sales.taxCollected + sales.taxIncluded > 0
      ? [
          {
            key: 'salesExTax',
            label: 'Sales excluding tax',
            value: money(sales.salesExcludingTax),
            note: <>Gross sales less tax{sales.serviceCharges > 0 && <> · includes {money(sales.serviceCharges)} service charge</>}</>,
          },
        ]
      : []),
  ];

  return (
    <div className="pay-kpis">
      {cards.map((c) => {
        const trend = c.percent === undefined || c.neutralDelta ? 'none' : trendOf(c.percent);
        return (
          <article className={`card pay-kpi${c.tone === 'warn' ? ' warn' : ''}`} key={c.key}>
            <h3>{c.label}</h3>
            <p className="pay-kpi-value">{c.value}</p>
            <p className="pay-kpi-note muted">{c.note}</p>
            {c.percent !== undefined && (
              <p className={`pay-delta small ${trend}`}>
                <TrendMark trend={trend} />
                {signedPercent(c.percent)}
              </p>
            )}
          </article>
        );
      })}
      <p className="pay-currency-note muted">
        All amounts in {currency}, grouped by {data.period.timezone} days and hours.
      </p>
    </div>
  );
}

/* ------------------------------------------------------------------ insights */

export function Insights({ data }: { data: AnalyticsOverview }) {
  if (data.insights.length === 0) {
    return (
      <section className="card pay-insights" aria-label="Payment insights">
        <h3>Payment insights</h3>
        <p className="muted">
          Observations appear once there are enough payments in the period to support them. Keep recording
          payments and this fills in on its own.
        </p>
      </section>
    );
  }
  return (
    <section className="card pay-insights" aria-label="Payment insights">
      <h3>Payment insights</h3>
      <ul>
        {data.insights.map((i) => (
          <li key={i.id} className={`pay-insight ${i.tone}`}>
            {i.text}
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Collapsed by default: useful when a figure is questioned, noise the rest of the time. */
export function Definitions({ data }: { data: AnalyticsOverview }) {
  const [open, setOpen] = useState(false);
  return (
    <details className="pay-definitions card" open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary>How each figure is worked out</summary>
      <dl>
        {data.definitions.map((d) => (
          <div key={d.key}>
            <dt>{d.label}</dt>
            <dd>{d.formula}</dd>
          </div>
        ))}
      </dl>
    </details>
  );
}

/* ------------------------------------------------------------------ filters */

const PERIODS: { key: PeriodPreset; label: string }[] = [
  { key: 'TODAY', label: 'Today' },
  { key: 'YESTERDAY', label: 'Yesterday' },
  { key: 'LAST_7_DAYS', label: 'Last 7 days' },
  { key: 'LAST_30_DAYS', label: 'Last 30 days' },
  { key: 'THIS_MONTH', label: 'This month' },
  { key: 'LAST_MONTH', label: 'Last month' },
  { key: 'CUSTOM', label: 'Custom range' },
];

const GRANULARITIES: { key: Granularity; label: string }[] = [
  { key: 'HOURLY', label: 'Hourly' },
  { key: 'DAILY', label: 'Daily' },
  { key: 'WEEKLY', label: 'Weekly' },
  { key: 'MONTHLY', label: 'Monthly' },
  { key: 'YEARLY', label: 'Yearly' },
];

export function Filters({
  filters,
  meta,
  onChange,
  onReset,
  activeCount,
}: {
  filters: AnalyticsFilters;
  meta: PaymentMeta | undefined;
  onChange: (next: Partial<AnalyticsFilters>) => void;
  onReset: () => void;
  activeCount: number;
}) {
  const [showMore, setShowMore] = useState(false);
  const providerOptions = (meta?.methods ?? []).filter((m) => m.providers.length > 0);

  return (
    <section className="pay-filters" aria-label="Filters">
      <div className="pay-period-row">
        <div className="pay-chips" role="group" aria-label="Period">
          {PERIODS.map((p) => (
            <button
              key={p.key}
              type="button"
              className={`pay-chip${filters.period === p.key ? ' on' : ''}`}
              aria-pressed={filters.period === p.key}
              onClick={() => onChange({ period: p.key })}
            >
              {p.label}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="btn btn-sm"
          aria-expanded={showMore}
          onClick={() => setShowMore((v) => !v)}
        >
          <Icon name="tag" />
          {activeCount > 0 ? `Filters (${activeCount})` : 'More filters'}
        </button>
      </div>

      {filters.period === 'CUSTOM' && (
        <div className="pay-custom-range">
          <label className="field">
            <span>From</span>
            <input
              type="date"
              value={filters.from ?? ''}
              max={filters.to || undefined}
              onChange={(e) => onChange({ from: e.target.value })}
            />
          </label>
          <label className="field">
            <span>To</span>
            <input
              type="date"
              value={filters.to ?? ''}
              min={filters.from || undefined}
              onChange={(e) => onChange({ to: e.target.value })}
            />
          </label>
        </div>
      )}

      {showMore && (
        <div className="pay-filter-panel card">
          <fieldset>
            <legend>Payment method</legend>
            <div className="pay-chips">
              {(meta?.methods ?? []).map((m) => (
                <button
                  key={m.key}
                  type="button"
                  className={`pay-chip${filters.methods.includes(m.key) ? ' on' : ''}`}
                  aria-pressed={filters.methods.includes(m.key)}
                  onClick={() => onChange({ methods: toggle(filters.methods, m.key) })}
                >
                  <span className="pay-swatch" style={{ background: methodColor(m.key) }} aria-hidden="true" />
                  {m.label}
                </button>
              ))}
            </div>
          </fieldset>

          {providerOptions.map((m) => (
            <fieldset key={m.key}>
              <legend>{m.label} app</legend>
              <div className="pay-chips">
                {m.providers.map((p) => (
                  <button
                    key={p.key}
                    type="button"
                    className={`pay-chip${filters.providers.includes(p.key) ? ' on' : ''}`}
                    aria-pressed={filters.providers.includes(p.key)}
                    onClick={() => onChange({ providers: toggle(filters.providers, p.key) })}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            </fieldset>
          ))}

          <fieldset>
            <legend>Status</legend>
            <div className="pay-chips">
              {(meta?.statuses ?? []).map((s) => (
                <button
                  key={s.key}
                  type="button"
                  className={`pay-chip${filters.statuses.includes(s.key) ? ' on' : ''}`}
                  aria-pressed={filters.statuses.includes(s.key)}
                  onClick={() => onChange({ statuses: toggle(filters.statuses, s.key) })}
                >
                  {s.label}
                </button>
              ))}
            </div>
          </fieldset>

          <div className="pay-filter-row">
            <label className="field">
              <span>Recorded by</span>
              <select
                value={filters.staffId ?? ''}
                onChange={(e) => onChange({ staffId: e.target.value ? Number(e.target.value) : null })}
              >
                <option value="">Anyone</option>
                {(meta?.staff ?? []).map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>Group time by</span>
              <select
                value={filters.granularity ?? ''}
                onChange={(e) => onChange({ granularity: (e.target.value || undefined) as Granularity })}
              >
                <option value="">Choose for me</option>
                {GRANULARITIES.map((g) => (
                  <option key={g.key} value={g.key}>
                    {g.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="actions">
            <button type="button" className="btn btn-sm" onClick={onReset} disabled={activeCount === 0}>
              Clear filters
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function toggle<T>(list: T[], value: T): T[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

export function periodWords(preset: PeriodPreset): string {
  switch (preset) {
    case 'TODAY':
      return 'today';
    case 'YESTERDAY':
      return 'yesterday';
    case 'LAST_7_DAYS':
      return 'in the last 7 days';
    case 'LAST_30_DAYS':
      return 'in the last 30 days';
    case 'THIS_MONTH':
      return 'this month';
    case 'LAST_MONTH':
      return 'last month';
    default:
      return 'in the selected range';
  }
}

/** Compact header strip shown while data is loading, so the layout does not jump. */
export function PaySkeleton() {
  return (
    <div className="pay-skeleton" role="status" aria-label="Loading payment figures">
      <div className="skeleton pay-sk-headline" />
      <div className="pay-sk-cards">
        {Array.from({ length: 6 }, (_, i) => (
          <div className="skeleton pay-sk-card" key={i} />
        ))}
      </div>
      <div className="skeleton pay-sk-chart" />
    </div>
  );
}
