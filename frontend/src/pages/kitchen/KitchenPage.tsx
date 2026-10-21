import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { kitchenApi, orderApi } from '../../api';
import type { KitchenOrder, OrderStatus } from '../../api/types';
import { FLOOR_ROLES, MANAGER_ROLES, useAuth } from '../../auth/AuthContext';
import { SoundSettingsButton } from '../../components/notifications/SoundSettingsButton';
import { ErrorMessage, Spinner, StatusBadge } from '../../components/ui';
import { money, timeAgo, timeOfDay } from '../../lib/format';
import { CancelOrderDialog } from '../../components/admin/CancelOrderDialog';
import type { CancelReasonCode } from '../../lib/cancelReasons';
import { delayText, timingLine } from '../../lib/kitchenTiming';
import { lineLabel, optionsSummary } from '../../lib/menuOptions';
import { useRestaurantTopic } from '../../lib/useRestaurantTopic';

type Step = 'confirm' | 'preparing' | 'ready' | 'served';

const COLUMNS: { status: OrderStatus; title: string; next?: { step: Step; label: string } }[] = [
  { status: 'PENDING', title: 'New', next: { step: 'confirm', label: 'Confirm' } },
  { status: 'CONFIRMED', title: 'Confirmed', next: { step: 'preparing', label: 'Start preparing' } },
  { status: 'PREPARING', title: 'Preparing', next: { step: 'ready', label: 'Mark ready' } },
  { status: 'READY', title: 'Ready to serve', next: { step: 'served', label: 'Mark served' } },
];

export default function KitchenPage() {
  const { user, logout } = useAuth();
  const queryClient = useQueryClient();
  const restaurantId = user?.restaurantId ?? null;
  const [now, setNow] = useState(Date.now());
  const [view, setView] = useState<'board' | 'orders'>('board');

  const orders = useQuery({
    queryKey: ['kitchen-orders'],
    queryFn: kitchenApi.active,
    enabled: restaurantId !== null,
    refetchInterval: 20_000,
  });

  // Today's orders in every status. The board drops an order the moment it is served, which left
  // the kitchen unable to see what had gone out or look up an order a diner was asking about.
  const todays = useQuery({
    queryKey: ['kitchen-orders-today'],
    queryFn: kitchenApi.today,
    enabled: restaurantId !== null && view === 'orders',
    refetchInterval: view === 'orders' ? 30_000 : false,
  });

  const connected = useRestaurantTopic(restaurantId, () => {
    void queryClient.invalidateQueries({ queryKey: ['kitchen-orders'] });
    void queryClient.invalidateQueries({ queryKey: ['kitchen-orders-today'] });
  });

  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 30_000);
    return () => clearInterval(t);
  }, []);

  function refreshLists() {
    void queryClient.invalidateQueries({ queryKey: ['kitchen-orders'] });
    void queryClient.invalidateQueries({ queryKey: ['kitchen-orders-today'] });
  }

  const advance = useMutation({
    mutationFn: (v: { orderId: number; step: Step }) => kitchenApi.advance(v.orderId, v.step),
    onSettled: refreshLists,
  });
  // Cancelling always asks why: the reason is shown to the guest and kept in the order's history.
  const [cancelling, setCancelling] = useState<KitchenOrder | null>(null);
  const cancel = useMutation({
    mutationFn: (v: { orderId: number; reason: CancelReasonCode; note: string }) =>
      orderApi.setStatus(v.orderId, 'CANCELLED', v.reason, v.note),
    onSuccess: () => setCancelling(null),
    onSettled: refreshLists,
  });

  if (restaurantId === null) {
    return (
      <div className="center-card">
        <h2>No restaurant</h2>
        <p className="muted">This account is not attached to a restaurant, so there are no kitchen orders to show.</p>
        <button className="btn" onClick={logout}>
          Sign out
        </button>
      </div>
    );
  }

  const canCancel = user ? [...MANAGER_ROLES, 'CHEF'].includes(user.role) : false;
  const busy = advance.isPending || cancel.isPending;

  return (
    <div className="kitchen">
      <div className="kitchen-head">
        <h2 style={{ margin: 0 }}>Kitchen</h2>
        <div className="kitchen-tabs" role="tablist" aria-label="Kitchen view">
          <button
            type="button"
            role="tab"
            aria-selected={view === 'board'}
            className={`kitchen-tab${view === 'board' ? ' on' : ''}`}
            onClick={() => setView('board')}
          >
            Board
          </button>
          <button
            type="button"
            role="tab"
            aria-selected={view === 'orders'}
            className={`kitchen-tab${view === 'orders' ? ' on' : ''}`}
            onClick={() => setView('orders')}
          >
            Orders
          </button>
        </div>
        <div className="kitchen-tools">
          <span className={`live${connected ? ' on' : ''}`} role="status">
            {connected ? 'Live' : 'Reconnecting (auto-refresh on)'}
          </span>
          <SoundSettingsButton />
          <Link className="btn btn-sm" to="/requests">
            Table requests
          </Link>
          {user && MANAGER_ROLES.includes(user.role) && (
            <Link className="btn btn-sm" to="/admin">
              Dashboard
            </Link>
          )}
          {user && FLOOR_ROLES.includes(user.role) && !MANAGER_ROLES.includes(user.role) && (
            <Link className="btn btn-sm" to="/floor">
              Tables &amp; payments
            </Link>
          )}
          <button className="btn btn-sm" onClick={logout}>
            Sign out
          </button>
        </div>
      </div>

      <ErrorMessage error={orders.error ?? advance.error} />
      {cancelling && (
        <CancelOrderDialog
          orderNumber={cancelling.orderNumber}
          busy={cancel.isPending}
          error={cancel.error}
          onClose={() => setCancelling(null)}
          onConfirm={(reason, note) => cancel.mutate({ orderId: cancelling.orderId, reason, note })}
        />
      )}
      {view === 'board' && orders.isLoading && <Spinner />}

      {view === 'orders' && (
        <TodaysOrders query={todays} now={now} />
      )}

      {view === 'board' && orders.data && (
        <div className="kitchen-board">
          {COLUMNS.map((col) => {
            const list = orders.data.filter((o) => o.status === col.status);
            return (
              <section className="kitchen-col" key={col.status}>
                <h3>
                  <span>{col.title}</span>
                  <span className="muted">{list.length}</span>
                </h3>
                {list.length === 0 && <p className="empty">Nothing here</p>}
                {list.map((o) => (
                  <Ticket
                    key={o.orderId}
                    order={o}
                    now={now}
                    busy={busy}
                    nextLabel={col.next?.label}
                    onNext={() => col.next && advance.mutate({ orderId: o.orderId, step: col.next.step })}
                    onCancel={
                      canCancel && (o.status === 'PENDING' || o.status === 'CONFIRMED')
                        ? () => {
                            cancel.reset();
                            setCancelling(o);
                          }
                        : undefined
                    }
                  />
                ))}
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * Today's orders, newest first, in every status. Read-only on purpose: advancing an order stays on
 * the board, so this cannot be mistaken for a second place to work from.
 */
function TodaysOrders({
  query,
  now,
}: {
  query: { data?: KitchenOrder[]; isLoading: boolean; error: unknown };
  now: number;
}) {
  if (query.isLoading) return <Spinner />;
  if (query.error) return <ErrorMessage error={query.error} />;

  const list = query.data ?? [];
  if (list.length === 0) {
    return <p className="empty">No orders yet today.</p>;
  }

  return (
    <div className="table-wrap kitchen-orders">
      <table>
        <thead>
          <tr>
            <th>Order</th>
            <th>Table</th>
            <th>Items</th>
            <th>Status</th>
            <th className="num">Total</th>
            <th>Placed</th>
          </tr>
        </thead>
        <tbody>
          {list.map((o) => (
            <tr key={o.orderId}>
              <td data-label="Order">{o.orderNumber}</td>
              <td data-label="Table">{o.tableName}</td>
              <td data-label="Items">
                {o.items.map((i) => lineLabel(i.quantity, i.itemName, i.options)).join(', ')}
                {o.notes && <div className="muted">Note: {o.notes}</div>}
              </td>
              <td data-label="Status">
                <StatusBadge status={o.status} />
              </td>
              <td className="num" data-label="Total">
                {money(o.total)}
              </td>
              <td data-label="Placed">
                {timeOfDay(o.createdAt)}
                <div className="muted">{timeAgo(o.createdAt, now)}</div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Ticket({
  order,
  now,
  busy,
  nextLabel,
  onNext,
  onCancel,
}: {
  order: KitchenOrder;
  now: number;
  busy: boolean;
  nextLabel?: string;
  onNext: () => void;
  onCancel?: () => void;
}) {
  return (
    <article className={`ticket${order.delayed ? ' is-delayed' : ''}`}>
      <div className="meta">
        <strong style={{ color: 'var(--text)' }}>{order.tableName}</strong>
        <span>{timeAgo(order.createdAt, now)}</span>
      </div>
      {(timingLine(order) || delayText(order)) && (
        <div className="ticket-timing">
          {delayText(order) && <span className="badge badge-red">{delayText(order)}</span>}
          <span className="muted">{timingLine(order)}</span>
        </div>
      )}
      <div className="muted">#{order.orderNumber}</div>
      <ul>
        {order.items.map((i) => (
          <li key={i.id}>
            {i.quantity} × {i.itemName}
            {(i.options?.length ?? 0) > 0 && <div className="kitchen-opts">{optionsSummary(i.options)}</div>}
          </li>
        ))}
      </ul>
      {order.notes && <div className="note">Note: {order.notes}</div>}
      <div className="actions" style={{ justifyContent: 'space-between' }}>
        {onCancel ? (
          <button className="btn btn-sm btn-danger" disabled={busy} onClick={onCancel}>
            Cancel
          </button>
        ) : (
          <span />
        )}
        {nextLabel && (
          <button className="btn btn-sm btn-primary" disabled={busy} onClick={onNext}>
            {nextLabel}
          </button>
        )}
      </div>
    </article>
  );
}
