import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { Cell, Pie, PieChart, ResponsiveContainer } from 'recharts';
import { useReducedMotion } from 'framer-motion';
import { ArrowRight, Bell, Flame, Table2 } from 'lucide-react';
import { menuApi, orderApi, serviceRequestApi, tableApi, tableSessionApi } from '../api';
import type { DashboardSummary } from '../api/types';
import { DishArt } from './art';
import { popularItems, statusSlices, tableStates, type StatusKey, type TableStateKey } from './data';
import { Panel, PanelError, PanelSkeleton } from './kit';

/* ---------- orders by status ---------- */

const STATUS_COLOR: Record<StatusKey, string> = {
  new: '#246bff',
  preparing: '#ff932e',
  ready: '#7c5cff',
  served: '#00d995',
};

export function OrdersByStatus({ summary }: { summary: DashboardSummary | undefined }) {
  const reduce = useReducedMotion();
  const [hover, setHover] = useState<StatusKey | null>(null);
  const { total, slices } = useMemo(
    () => (summary ? statusSlices(summary) : { total: 0, slices: [] }),
    [summary],
  );
  const ring = total === 0 ? [{ key: 'none', value: 1 }] : slices.filter((s) => s.value > 0).map((s) => ({ key: s.key, value: s.value }));

  return (
    <Panel className="kd-status" tone="red" icon={<Bell />} title="Orders by Status">
      {!summary ? (
        <PanelSkeleton rows={3} />
      ) : (
        <div className="kd-status-body">
          <div className="kd-donut" role="img" aria-label={`${total} orders: ${slices.map((s) => `${s.value} ${s.label.toLowerCase()}`).join(', ')}`}>
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={ring}
                  dataKey="value"
                  innerRadius="68%"
                  outerRadius="96%"
                  paddingAngle={ring.length > 1 ? 3 : 0}
                  cornerRadius={6}
                  stroke="none"
                  isAnimationActive={!reduce}
                  animationDuration={800}
                  onMouseLeave={() => setHover(null)}
                >
                  {ring.map((r) => (
                    <Cell
                      key={r.key}
                      fill={r.key === 'none' ? 'rgba(255,255,255,0.1)' : STATUS_COLOR[r.key as StatusKey]}
                      fillOpacity={hover && hover !== r.key ? 0.35 : 1}
                      onMouseEnter={() => r.key !== 'none' && setHover(r.key as StatusKey)}
                    />
                  ))}
                </Pie>
              </PieChart>
            </ResponsiveContainer>
            <div className="kd-donut-center" aria-hidden="true">
              <strong>{total}</strong>
              <span>Total orders</span>
            </div>
          </div>
          <ul className="kd-legend">
            {slices.map((s) => (
              <li key={s.key} className={hover === s.key ? 'is-hover' : ''} onMouseEnter={() => setHover(s.key)} onMouseLeave={() => setHover(null)}>
                <i className={`kd-dot kd-dot-${s.key}`} aria-hidden="true" />
                <span>{s.label}</span>
                <b>{s.value}</b>
                <em>{s.percent}%</em>
              </li>
            ))}
          </ul>
        </div>
      )}
    </Panel>
  );
}

/* ---------- popular items ---------- */

const BAR_TONES = ['red', 'orange', 'blue', 'green'] as const;
const RECENT_ORDERS = 100;

export function PopularItems() {
  const orders = useQuery({ queryKey: ['orders', 'dashboard-popular'], queryFn: () => orderApi.list(0, RECENT_ORDERS), staleTime: 60_000 });
  const menu = useQuery({ queryKey: ['menu-items', 'dashboard'], queryFn: menuApi.list, staleTime: 5 * 60_000 });
  const items = useMemo(() => popularItems(orders.data?.items ?? [], 4), [orders.data]);
  const top = items[0]?.count ?? 0;
  const image = (id: number | null) => menu.data?.items.find((m) => m.id === id)?.imageUrl ?? null;

  return (
    <Panel
      className="kd-popular"
      tone="orange"
      icon={<Flame />}
      title="Popular Items"
      subtitle={`Most ordered in your latest ${RECENT_ORDERS} orders`}
      action={
        <Link to="/admin/menu" className="kd-btn kd-btn-sm">
          View menu <ArrowRight aria-hidden="true" />
        </Link>
      }
    >
      {orders.isLoading && <PanelSkeleton rows={4} />}
      {orders.isError && <PanelError message="Popular items could not be loaded." onRetry={() => void orders.refetch()} />}
      {orders.data && items.length === 0 && <p className="kd-empty">Dishes show here once orders come in.</p>}
      <ol className="kd-popular-list">
        {items.map((it, i) => {
          const src = image(it.menuItemId);
          return (
            <li key={it.name}>
              {src ? <img className="kd-thumb" src={src} alt="" loading="lazy" /> : <DishArt className="kd-thumb" index={i} />}
              <div className="kd-popular-main">
                <div className="kd-popular-top">
                  <strong>{it.name}</strong>
                  <em>{it.percent}%</em>
                </div>
                <span>
                  {it.count} order{it.count === 1 ? '' : 's'}
                </span>
                <div className="kd-bar" role="presentation">
                  <i className={`kd-bar-fill kd-bar-${BAR_TONES[i % BAR_TONES.length]}`} style={{ width: `${top === 0 ? 0 : Math.max(6, (it.count / top) * 100)}%` }} />
                </div>
              </div>
            </li>
          );
        })}
      </ol>
    </Panel>
  );
}

/* ---------- table status ---------- */

const TABLE_TONE: Record<TableStateKey, string> = { occupied: 'green', available: 'orange', requested: 'red', offline: 'gray' };

export function TableStatus() {
  const tables = useQuery({ queryKey: ['tables', 'dashboard'], queryFn: tableApi.list, staleTime: 60_000 });
  const sessions = useQuery({ queryKey: ['live-tables'], queryFn: tableSessionApi.active, refetchInterval: 15_000 });
  const requests = useQuery({ queryKey: ['service-requests-board'], queryFn: serviceRequestApi.board, refetchInterval: 15_000 });
  const ready = tables.data && sessions.data && requests.data;
  const { total, slices } = useMemo(
    () => (ready ? tableStates(tables.data!, sessions.data!, requests.data!) : { total: 0, slices: [] }),
    [ready, tables.data, sessions.data, requests.data],
  );
  const failed = tables.isError || sessions.isError || requests.isError;

  return (
    <Panel
      className="kd-tables"
      tone="red"
      icon={<Table2 />}
      title="Table Status"
      subtitle="Live status of all tables"
      action={
        <Link to="/admin/live" className="kd-btn kd-btn-sm">
          View tables <ArrowRight aria-hidden="true" />
        </Link>
      }
    >
      {failed && <PanelError message="Table status could not be loaded." onRetry={() => { void tables.refetch(); void sessions.refetch(); void requests.refetch(); }} />}
      {!failed && !ready && <PanelSkeleton rows={3} />}
      {ready && (
        <>
          <div className="kd-table-cards">
            {slices.map((s) => (
              <div key={s.key} className={`kd-table-card kd-tc-${TABLE_TONE[s.key as TableStateKey]}`}>
                <span className="kd-tc-count">{s.count}</span>
                <span className="kd-tc-label">{s.label}</span>
                <span className="kd-tc-pct">{s.percent}%</span>
              </div>
            ))}
          </div>
          <div className="kd-segbar" role="img" aria-label={slices.map((s) => `${s.count} ${s.label.toLowerCase()}`).join(', ')}>
            {total === 0 ? (
              <i className="kd-seg kd-seg-gray" style={{ flexGrow: 1 }} />
            ) : (
              slices.filter((s) => s.count > 0).map((s) => <i key={s.key} className={`kd-seg kd-seg-${TABLE_TONE[s.key as TableStateKey]}`} style={{ flexGrow: s.count }} />)
            )}
          </div>
          <p className="kd-table-total">
            <b>{total}</b> Total tables
          </p>
        </>
      )}
    </Panel>
  );
}
