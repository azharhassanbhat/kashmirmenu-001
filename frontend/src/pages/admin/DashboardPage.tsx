import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarDays, ChevronDown } from 'lucide-react';
import { dashboardApi } from '../../api';
import { useAuth } from '../../auth/AuthContext';
import { OnboardingChecklist } from '../../components/admin/OnboardingChecklist';
import { HeroArt } from '../../dashboard/art';
import { AnalyticsPanels } from '../../dashboard/AnalyticsRow';
import { greeting } from '../../dashboard/data';
import { KpiCards } from '../../dashboard/KpiCards';
import { PanelError, Skeleton, Popover } from '../../dashboard/kit';
import { NotificationsPanel } from '../../dashboard/NotificationsPanel';
import { LiveOrders, PERIODS, RevenueOverview, useRevenueOverview, type DashboardPeriod } from '../../dashboard/RevenuePanels';
import { useRestaurantTopic } from '../../lib/useRestaurantTopic';

export default function DashboardPage() {
  const { user } = useAuth();
  const restaurantId = user?.restaurantId ?? null;
  const queryClient = useQueryClient();
  const [period, setPeriod] = useState<DashboardPeriod>('TODAY');

  const summary = useQuery({ queryKey: ['dashboard'], queryFn: dashboardApi.summary, refetchInterval: 30_000 });
  // Yesterday's comparison for the revenue card always uses the "today" analytics.
  const todayOverview = useRevenueOverview('TODAY');

  useRestaurantTopic(restaurantId, () => {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  });

  const now = new Date();
  const firstName = (user?.name ?? 'there').trim().split(/\s+/)[0];
  const dateText = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric' }).format(now);
  const periodLabel = period === 'TODAY' ? `Today, ${dateText}` : PERIODS.find((p) => p.key === period)!.long;
  const s = summary.data;

  return (
    <div className="kd-page">
      <div className="kd-hero-art">
        <HeroArt />
      </div>

      <section className="kd-section kd-hero">
        <div>
          <h1>
            {greeting(now)}, <span className="kd-accent">{firstName}!</span> <span aria-hidden="true">👋</span>
          </h1>
          <p>Here's what's happening at your restaurant today.</p>
        </div>
        <Popover
          className="kd-datepick"
          button={({ open, toggle, panelId }) => (
            <button type="button" className="kd-select kd-glass kd-date-btn" aria-haspopup="listbox" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
              <CalendarDays aria-hidden="true" />
              <span>{periodLabel}</span>
              <ChevronDown aria-hidden="true" />
            </button>
          )}
        >
          {(close) => (
            <ul className="kd-menu" role="listbox" aria-label="Date range">
              {PERIODS.map((p) => (
                <li key={p.key}>
                  <button
                    type="button"
                    role="option"
                    aria-selected={period === p.key}
                    className={period === p.key ? 'is-active' : ''}
                    onClick={() => {
                      setPeriod(p.key);
                      close();
                    }}
                  >
                    {p.long}
                  </button>
                </li>
              ))}
              <li className="kd-menu-note">Sets the revenue chart. The cards always show today.</li>
            </ul>
          )}
        </Popover>
      </section>

      {summary.isLoading && (
        <div className="kd-section tw:grid tw:grid-cols-2 tw:gap-3 tw:lg:grid-cols-3 tw:kpi:grid-cols-6" role="status" aria-label="Loading">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="kd-kpi-skel" />
          ))}
        </div>
      )}
      {summary.isError && (
        <div className="kd-section">
          <PanelError message="Today's figures could not be loaded." onRetry={() => void summary.refetch()} />
        </div>
      )}
      {s && <KpiCards summary={s} revenueDelta={todayOverview.data?.deltas.net.percent} />}

      <div className="kd-section">
        <OnboardingChecklist />
      </div>

      <div className="kd-section tw:grid tw:gap-4 tw:xl:grid-cols-[minmax(0,65fr)_minmax(0,35fr)]">
        <RevenueOverview period={period} onPeriod={setPeriod} summary={s} />
        <LiveOrders />
      </div>

      <AnalyticsPanels summary={s} />

      <div className="kd-section">
        <NotificationsPanel restaurantId={restaurantId} />
      </div>
    </div>
  );
}
