import type { ReactNode } from 'react';
import { motion } from 'framer-motion';
import { ArrowDownRight, ArrowRight, ArrowUpRight, CheckCircle2, ChefHat, ConciergeBell, IndianRupee, ShoppingBag, FileText } from 'lucide-react';
import type { DashboardSummary } from '../api/types';
import { money } from '../lib/format';
import { signedPercent, trendOf } from '../lib/charts';
import { AnimatedNumber } from './kit';

interface Kpi {
  key: string;
  tone: 'red' | 'purple' | 'blue' | 'amber' | 'indigo' | 'green';
  icon: ReactNode;
  label: string;
  value: number;
  format: (n: number) => string;
  /** Decorative mini bars, as a share of full height. Not data. */
  bars: number[];
  caption: ReactNode;
}

const count = (n: number) => String(Math.round(n));

/**
 * Six headline numbers. They come from the dashboard summary, so they are exactly what the server counted.
 * Only revenue has a comparison (against yesterday, from payment analytics); the others carry a plain
 * caption instead of an invented trend. The little bar charts are decoration.
 */
export function KpiCards({ summary, revenueDelta }: { summary: DashboardSummary; revenueDelta: number | null | undefined }) {
  const trend = trendOf(revenueDelta);
  const TrendIcon = trend === 'up' ? ArrowUpRight : trend === 'down' ? ArrowDownRight : ArrowRight;
  const kpis: Kpi[] = [
    {
      key: 'revenue',
      tone: 'red',
      icon: <IndianRupee />,
      label: "Today's revenue",
      value: summary.todayRevenue,
      format: (n) => money(n).replace(/\.00$/, ''),
      bars: [0.35, 0.55, 0.45, 0.8, 0.62],
      caption:
        revenueDelta === undefined || revenueDelta === null ? (
          <span className="kd-kpi-muted">No comparison yet</span>
        ) : (
          <>
            <TrendIcon aria-hidden="true" />
            <b>{signedPercent(revenueDelta)}</b> vs yesterday
          </>
        ),
    },
    { key: 'orders', tone: 'purple', icon: <ShoppingBag />, label: 'Total orders', value: summary.todayOrders, format: count, bars: [0.4, 0.7, 0.5, 0.9, 0.6], caption: <span className="kd-kpi-muted">Placed today</span> },
    { key: 'new', tone: 'blue', icon: <FileText />, label: 'New orders', value: summary.pendingOrders, format: count, bars: [0.3, 0.5, 0.35, 0.6, 0.45], caption: <span className="kd-kpi-muted">Waiting for the kitchen</span> },
    { key: 'preparing', tone: 'amber', icon: <ChefHat />, label: 'Preparing', value: summary.preparingOrders, format: count, bars: [0.5, 0.35, 0.65, 0.4, 0.55], caption: <span className="kd-kpi-muted">In the kitchen now</span> },
    { key: 'ready', tone: 'indigo', icon: <ConciergeBell />, label: 'Ready', value: summary.readyOrders, format: count, bars: [0.45, 0.6, 0.4, 0.7, 0.5], caption: <span className="kd-kpi-muted">Ready to serve</span> },
    { key: 'served', tone: 'green', icon: <CheckCircle2 />, label: 'Served', value: summary.servedOrders, format: count, bars: [0.5, 0.8, 0.6, 0.95, 0.7], caption: <span className="kd-kpi-muted">Completed today</span> },
  ];

  return (
    <div className="kd-section tw:grid tw:grid-cols-2 tw:gap-3 tw:lg:grid-cols-3 tw:kpi:grid-cols-6" role="list" aria-label="Today at a glance">
      {kpis.map((k, i) => (
        <motion.article
          key={k.key}
          role="listitem"
          className={`kd-kpi kd-kpi-${k.tone}`}
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          whileHover={{ y: -3 }}
          transition={{ duration: 0.4, delay: i * 0.05, ease: 'easeOut' }}
        >
          <span className="kd-kpi-icon" aria-hidden="true">
            {k.icon}
          </span>
          <div className="kd-kpi-body">
            <div className="kd-kpi-value">
              <AnimatedNumber value={k.value} format={k.format} />
            </div>
            <div className="kd-kpi-label">{k.label}</div>
            <div className={`kd-kpi-caption${trend === 'down' && k.key === 'revenue' ? ' is-down' : ''}`}>{k.caption}</div>
          </div>
          <svg className="kd-kpi-bars" viewBox="0 0 40 28" aria-hidden="true">
            {k.bars.map((b, n) => (
              <rect key={n} x={n * 8} y={28 - b * 26} width="5" height={b * 26} rx="1.5" />
            ))}
          </svg>
          <svg className="kd-kpi-wave" viewBox="0 0 200 40" preserveAspectRatio="none" aria-hidden="true">
            <path d="M0 24 C30 6 60 38 100 22 S170 8 200 24 V40 H0Z" />
          </svg>
        </motion.article>
      ))}
    </div>
  );
}
