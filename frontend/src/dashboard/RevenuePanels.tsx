import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueries, useQuery } from '@tanstack/react-query';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from 'recharts';
import { useReducedMotion } from 'framer-motion';
import { ArrowRight, ChevronDown, FileText, IndianRupee, MoreVertical, Radio } from 'lucide-react';
import { orderApi, paymentAnalyticsApi, tableApi } from '../api';
import type { DashboardSummary, PeriodPreset } from '../api/types';
import { compactMoney, niceScale } from '../lib/charts';
import { money } from '../lib/format';
import { orderBadge, placedAt, placedLabel, revenuePoints, type ChartMetric, type RevenuePoint } from './data';
import { Panel, PanelError, PanelSkeleton, Popover } from './kit';

export type DashboardPeriod = Extract<PeriodPreset, 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS'>;

export const PERIODS: { key: DashboardPeriod; tab: string; long: string }[] = [
  { key: 'TODAY', tab: 'Today', long: 'Today' },
  { key: 'LAST_7_DAYS', tab: '7 days', long: 'Last 7 days' },
  { key: 'LAST_30_DAYS', tab: '30 days', long: 'Last 30 days' },
];

const METRICS: { key: ChartMetric; label: string }[] = [
  { key: 'revenue', label: 'Revenue' },
  { key: 'orders', label: 'Payments' },
];

const NO_FILTERS = { methods: [], providers: [], statuses: [] };

/** The analytics behind both the chart and the date picker's figures. Refreshes on live payment events. */
export function useRevenueOverview(period: DashboardPeriod) {
  return useQuery({
    queryKey: ['payment-overview', 'dashboard', period],
    queryFn: () => paymentAnalyticsApi.overview({ period, ...NO_FILTERS }),
    staleTime: 30_000,
  });
}

function ChartTooltip({ active, payload, metric }: Partial<TooltipContentProps> & { metric: ChartMetric }) {
  if (!active || !payload || payload.length === 0) return null;
  const point = payload[0].payload as RevenuePoint;
  return (
    <div className="kd-tooltip">
      <strong>{metric === 'revenue' ? money(point.revenue) : `${point.orders} payment${point.orders === 1 ? '' : 's'}`}</strong>
      <span>{point.label}</span>
    </div>
  );
}

export function RevenueOverview({
  period,
  onPeriod,
  summary,
}: {
  period: DashboardPeriod;
  onPeriod: (p: DashboardPeriod) => void;
  summary: DashboardSummary | undefined;
}) {
  const [metric, setMetric] = useState<ChartMetric>('revenue');
  const reduce = useReducedMotion();
  const overview = useRevenueOverview(period);
  const points = useMemo(() => (overview.data ? revenuePoints(overview.data) : []), [overview.data]);

  const values = points.map((p) => (metric === 'revenue' ? p.revenue : p.orders));
  const scale = niceScale(Math.max(0, ...values), 4);

  // Today's figures are the same ones the cards show; other periods come from the analytics themselves.
  const o = overview.data;
  const revenue = period === 'TODAY' && summary ? summary.todayRevenue : (o?.totals.net ?? 0);
  const orders = period === 'TODAY' && summary ? summary.todayOrders : (o?.sales?.ordersPlaced ?? o?.totals.uniqueOrders ?? 0);
  const average = orders > 0 ? revenue / orders : 0;
  const metricLabel = METRICS.find((m) => m.key === metric)!.label;

  return (
    <Panel
      className="kd-revenue"
      tone="gold"
      icon={<IndianRupee />}
      title="Revenue Overview"
      subtitle={period === 'TODAY' ? "Today's revenue and order trend" : `${PERIODS.find((p) => p.key === period)!.long}: revenue and order trend`}
      action={
        <div className="kd-panel-controls">
          <div className="kd-tabs" role="tablist" aria-label="Period">
            {PERIODS.map((p) => (
              <button
                key={p.key}
                type="button"
                role="tab"
                aria-selected={period === p.key}
                className={period === p.key ? 'is-active' : ''}
                onClick={() => onPeriod(p.key)}
              >
                {p.tab}
              </button>
            ))}
          </div>
          <Popover
            button={({ open, toggle, panelId }) => (
              <button type="button" className="kd-select" aria-haspopup="listbox" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
                {metricLabel}
                <ChevronDown aria-hidden="true" />
              </button>
            )}
          >
            {(close) => (
              <ul className="kd-menu" role="listbox" aria-label="Chart metric">
                {METRICS.map((m) => (
                  <li key={m.key}>
                    <button
                      type="button"
                      role="option"
                      aria-selected={metric === m.key}
                      className={metric === m.key ? 'is-active' : ''}
                      onClick={() => {
                        setMetric(m.key);
                        close();
                      }}
                    >
                      {m.label}
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </Popover>
        </div>
      }
    >
      <div className="kd-revenue-body">
        <div className="kd-chart-scroll">
          <div className="kd-chart" aria-label={`${metricLabel} by ${o?.series.granularity === 'HOURLY' ? 'hour' : 'day'}`}>
            {overview.isLoading && <PanelSkeleton rows={4} />}
            {overview.isError && <PanelError message="The revenue chart could not be loaded." onRetry={() => void overview.refetch()} />}
            {o && (points.length === 0 || values.every((v) => v === 0)) && (
              <p className="kd-empty kd-chart-empty">No payments in this period yet. They will appear here as they come in.</p>
            )}
            {o && points.length > 0 && values.some((v) => v > 0) && (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={points} margin={{ top: 14, right: 12, bottom: 0, left: 0 }}>
                  <defs>
                    <linearGradient id="kd-area" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0" stopColor="#ff1744" stopOpacity={0.55} />
                      <stop offset="0.7" stopColor="#d90032" stopOpacity={0.12} />
                      <stop offset="1" stopColor="#d90032" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid stroke="var(--kd-line)" strokeDasharray="3 5" vertical={false} />
                  <XAxis dataKey="label" tickLine={false} axisLine={false} tick={{ fill: 'var(--kd-muted)', fontSize: 11 }} interval="preserveStartEnd" minTickGap={18} />
                  <YAxis
                    domain={[0, scale.max]}
                    ticks={scale.ticks}
                    tickLine={false}
                    axisLine={false}
                    width={metric === 'revenue' ? 46 : 30}
                    tick={{ fill: 'var(--kd-muted)', fontSize: 11 }}
                    tickFormatter={(v: number) => (metric === 'revenue' ? compactMoney(v) : String(v))}
                  />
                  <Tooltip
                    cursor={{ stroke: 'rgba(255,23,68,0.45)', strokeDasharray: '4 4' }}
                    content={(p: TooltipContentProps) => <ChartTooltip {...p} metric={metric} />}
                  />
                  <Area
                    type="monotone"
                    dataKey={metric === 'revenue' ? 'revenue' : 'orders'}
                    stroke="#ff1744"
                    strokeWidth={2.5}
                    fill="url(#kd-area)"
                    isAnimationActive={!reduce}
                    animationDuration={900}
                    dot={{ r: 3, fill: '#ff1744', stroke: 'rgba(255,23,68,0.35)', strokeWidth: 5 }}
                    activeDot={{ r: 5.5, fill: '#fff', stroke: '#ff1744', strokeWidth: 3 }}
                  />
                </AreaChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
        <dl className="kd-revenue-summary">
          <div>
            <dt>{period === 'TODAY' ? "Today's revenue" : 'Revenue'}</dt>
            <dd className="is-gold">{money(revenue)}</dd>
          </div>
          <div>
            <dt>Total orders</dt>
            <dd>{orders}</dd>
          </div>
          <div>
            <dt>Average order value</dt>
            <dd className="is-gold">{money(average)}</dd>
          </div>
        </dl>
      </div>
    </Panel>
  );
}

/* ---------- live orders ---------- */

export function LiveOrders() {
  const orders = useQuery({ queryKey: ['orders', 'dashboard-live'], queryFn: () => orderApi.list(0, 3), staleTime: 10_000 });
  const tables = useQuery({ queryKey: ['tables', 'dashboard'], queryFn: tableApi.list, staleTime: 5 * 60_000 });
  const rows = orders.data?.items ?? [];
  const histories = useQueries({
    queries: rows.map((o) => ({ queryKey: ['order-history', o.id], queryFn: () => orderApi.history(o.id), staleTime: 60_000 })),
  });
  const tableName = (id: number) => tables.data?.find((t) => t.id === id)?.tableName ?? `Table ${id}`;

  return (
    <Panel
      className="kd-live"
      tone="red"
      icon={<Radio />}
      title="Live Orders"
      subtitle="Latest orders from all tables"
      action={
        <Link to="/admin/orders" className="kd-btn kd-btn-sm">
          View all <ArrowRight aria-hidden="true" />
        </Link>
      }
    >
      {orders.isLoading && <PanelSkeleton rows={3} />}
      {orders.isError && <PanelError message="Orders could not be loaded." onRetry={() => void orders.refetch()} />}
      {orders.data && rows.length === 0 && <p className="kd-empty">No orders yet. New ones appear here the moment they are placed.</p>}
      <ul className="kd-order-list">
        {rows.map((o, i) => {
          const badge = orderBadge(o.status);
          const when = placedLabel(placedAt(histories[i]?.data));
          return (
            <li key={o.id} className="kd-order-row">
              <span className={`kd-order-icon kd-tone-${badge.tone}`} aria-hidden="true">
                <FileText />
              </span>
              <div className="kd-order-main">
                <strong>{o.orderNumber}</strong>
                <span>{tableName(o.restaurantTableId)}</span>
              </div>
              <div className="kd-order-meta">
                <span className="kd-order-time">{when}</span>
                <b className="is-gold">{money(o.total)}</b>
              </div>
              <span className={`kd-badge kd-badge-${badge.tone}`}>{badge.label}</span>
              <Popover
                className="kd-order-more"
                button={({ open, toggle, panelId }) => (
                  <button type="button" className="kd-icon-btn" aria-label={`Options for ${o.orderNumber}`} aria-haspopup="menu" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
                    <MoreVertical aria-hidden="true" />
                  </button>
                )}
              >
                {(close) => (
                  <div className="kd-menu" role="menu">
                    <Link role="menuitem" to="/admin/orders" onClick={close}>
                      Open in Orders
                    </Link>
                    <Link role="menuitem" to={`/admin/orders/${o.id}/receipt`} onClick={close}>
                      View receipt
                    </Link>
                  </div>
                )}
              </Popover>
            </li>
          );
        })}
      </ul>
    </Panel>
  );
}
