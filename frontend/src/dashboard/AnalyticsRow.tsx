import type { DashboardSummary } from '../api/types';
import { OrdersByStatus, PopularItems, TableStatus } from './AnalyticsPanels';

/** The second row: three balanced panels side by side on wide screens, stacked on narrow ones. */
export function AnalyticsPanels({ summary }: { summary: DashboardSummary | undefined }) {
  return (
    <div className="kd-section tw:grid tw:gap-4 tw:xl:grid-cols-3">
      <OrdersByStatus summary={summary} />
      <PopularItems />
      <TableStatus />
    </div>
  );
}
