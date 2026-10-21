import { useCallback, useMemo, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { paymentAnalyticsApi } from '../../api';
import type { AnalyticsFilters } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { EmptyState, ErrorMessage } from '../../components/ui';
import {
  ChartFrame,
  ComparisonBars,
  MethodDonut,
  PeakHours,
  ProviderBars,
  TrendChart,
} from '../../components/payments/charts';
import {
  Definitions,
  Filters,
  Headline,
  Insights,
  KpiCards,
  PaySkeleton,
} from '../../components/payments/panels';
import { ReconciliationPanel, TransactionHistory } from '../../components/payments/transactions';
import { useRestaurantTopic } from '../../lib/useRestaurantTopic';

/**
 * Payments & Revenue Analytics.
 *
 * Reads from one overview endpoint so every panel on the page is derived from the same aggregation
 * and cannot disagree with itself. Clicking a method or an app narrows the whole page, including the
 * history table below, rather than only the chart that was clicked.
 */

function today(): string {
  // The server reports its own business day; this is only the date input's ceiling.
  return new Date().toISOString().slice(0, 10);
}

const DEFAULTS: AnalyticsFilters = {
  period: 'LAST_7_DAYS',
  methods: [],
  providers: [],
  statuses: [],
  staffId: null,
};

export default function PaymentAnalyticsPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [filters, setFilters] = useState<AnalyticsFilters>(DEFAULTS);
  const [countMode, setCountMode] = useState(false);

  const meta = useQuery({ queryKey: ['payment-meta'], queryFn: paymentAnalyticsApi.meta });

  // A custom range only becomes a valid request once both ends are set.
  const ready = filters.period !== 'CUSTOM' || Boolean(filters.from && filters.to);

  const overview = useQuery({
    queryKey: ['payment-overview', filters],
    queryFn: () => paymentAnalyticsApi.overview(filters),
    enabled: ready,
    refetchInterval: filters.period === 'TODAY' ? 60_000 : false,
  });

  // A payment confirmed elsewhere in the restaurant should show up here without a reload.
  useRestaurantTopic(user?.restaurantId ?? null, () => {
    void queryClient.invalidateQueries({ queryKey: ['payment-overview'] });
    void queryClient.invalidateQueries({ queryKey: ['payment-transactions'] });
    void queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
  });

  const change = useCallback((next: Partial<AnalyticsFilters>) => {
    setFilters((f) => ({ ...f, ...next }));
  }, []);

  const reset = useCallback(() => {
    setFilters((f) => ({ ...DEFAULTS, period: f.period, from: f.from, to: f.to }));
  }, []);

  const activeCount =
    filters.methods.length +
    filters.providers.length +
    filters.statuses.length +
    (filters.staffId != null ? 1 : 0) +
    (filters.granularity ? 1 : 0);

  const data = overview.data;
  const previousLabel = useMemo(() => {
    if (!data) return 'Previous period';
    const { previousFrom, previousTo } = data.period;
    return previousFrom === previousTo ? previousFrom : `${previousFrom} to ${previousTo}`;
  }, [data]);

  return (
    <>
      <div className="page-head">
        <div>
          <h2>Payments &amp; revenue</h2>
          {data && (
            <p className="muted">
              {data.period.from === data.period.to
                ? data.period.from
                : `${data.period.from} to ${data.period.to}`}{' '}
              · compared with {previousLabel}
            </p>
          )}
        </div>
      </div>

      <ErrorMessage error={meta.error} />

      <Filters
        filters={filters}
        meta={meta.data}
        onChange={change}
        onReset={reset}
        activeCount={activeCount}
      />

      {!ready && (
        <EmptyState icon="card">Choose both ends of the date range to see the figures.</EmptyState>
      )}

      {ready && overview.isLoading && <PaySkeleton />}
      <ErrorMessage error={overview.error} />

      {ready && data && (
        <>
          <Headline data={data} />
          <KpiCards data={data} />

          {data.totals.transactions === 0 && data.open.pendingCount === 0 ? (
            <EmptyState icon="card">
              No payments were recorded in this period. Try a longer range, or clear the filters.
            </EmptyState>
          ) : (
            <>
              <Insights data={data} />

              <div className="pay-chart-grid">
                <ChartFrame
                  title="How people paid"
                  hint="Tap a method to narrow the whole page."
                  empty={data.methods.every((m) => m.gross === 0)}
                >
                  <MethodDonut
                    rows={data.methods}
                    total={data.totals.gross}
                    currency={data.period.currency}
                    selected={filters.methods}
                    onSelect={(key) =>
                      change({
                        methods: filters.methods.includes(key)
                          ? filters.methods.filter((k) => k !== key)
                          : [...filters.methods, key],
                      })
                    }
                  />
                </ChartFrame>

                <ChartFrame
                  title="UPI apps and wallets"
                  hint="Recorded when the payment is confirmed; never inferred from a reference."
                  empty={data.providers.length === 0}
                >
                  <ProviderBars
                    rows={data.providers}
                    currency={data.period.currency}
                    selected={filters.providers}
                    onSelect={(key) =>
                      change({
                        providers: filters.providers.includes(key)
                          ? filters.providers.filter((k) => k !== key)
                          : [...filters.providers, key],
                      })
                    }
                  />
                </ChartFrame>
              </div>

              <ChartFrame
                title={countMode ? 'Payments over time' : 'Revenue over time'}
                hint={`Grouped ${data.series.granularity.toLowerCase()}. Tap a method in the key to hide it.`}
                empty={data.series.rows.length === 0}
                actions={
                  <div className="pay-chips" role="group" aria-label="Show">
                    <button
                      type="button"
                      className={`pay-chip${!countMode ? ' on' : ''}`}
                      aria-pressed={!countMode}
                      onClick={() => setCountMode(false)}
                    >
                      Amount
                    </button>
                    <button
                      type="button"
                      className={`pay-chip${countMode ? ' on' : ''}`}
                      aria-pressed={countMode}
                      onClick={() => setCountMode(true)}
                    >
                      Payments
                    </button>
                  </div>
                }
              >
                <TrendChart
                  buckets={data.series.buckets}
                  rows={data.series.rows}
                  granularity={data.series.granularity}
                  currency={data.period.currency}
                  mode={countMode ? 'count' : 'amount'}
                />
              </ChartFrame>

              <div className="pay-chart-grid">
                <ChartFrame
                  title="This period against the last"
                  hint={`Previous period: ${previousLabel}.`}
                  empty={data.methods.length === 0}
                >
                  <ComparisonBars
                    rows={data.methods}
                    currency={data.period.currency}
                    previousLabel="Previous"
                  />
                </ChartFrame>

                <ChartFrame
                  title="When the money comes in"
                  hint="Collected amount by weekday and hour, in the restaurant's own time."
                  empty={data.totals.gross === 0}
                >
                  <PeakHours
                    amounts={data.heatmap.amounts}
                    counts={data.heatmap.counts}
                    hourlyAmounts={data.heatmap.hourlyAmounts}
                    currency={data.period.currency}
                  />
                </ChartFrame>
              </div>
            </>
          )}

          <TransactionHistory filters={filters} />
          <ReconciliationPanel today={today()} />
          <Definitions data={data} />
        </>
      )}
    </>
  );
}
