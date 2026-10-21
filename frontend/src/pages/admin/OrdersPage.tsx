import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { orderApi, paymentAnalyticsApi } from '../../api';
import type { Order, PaymentMeta } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { EmptyState, ErrorMessage, Modal, QueryState, Spinner, StatusBadge } from '../../components/ui';
import { CollectPaymentModal } from '../../components/payments/CollectPayment';
import { money } from '../../lib/format';
import { CancelOrderDialog } from '../../components/admin/CancelOrderDialog';
import { OrderHistoryModal } from '../../components/admin/OrderHistoryModal';
import type { CancelReasonCode } from '../../lib/cancelReasons';
import { lineLabel } from '../../lib/menuOptions';
import { useRestaurantTopic } from '../../lib/useRestaurantTopic';

export default function OrdersPage() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(0);
  const orders = useQuery({
    queryKey: ['orders', page],
    queryFn: () => orderApi.list(page),
    refetchInterval: 30_000,
  });
  const [collectFor, setCollectFor] = useState<Order | null>(null);

  useRestaurantTopic(user?.restaurantId, () => {
    void queryClient.invalidateQueries({ queryKey: ['orders'] });
    void queryClient.invalidateQueries({ queryKey: ['order-payment-summary'] });
  });

  // Cancelling always asks why; the reason is shown to the guest and kept in the order's history.
  const [cancelling, setCancelling] = useState<Order | null>(null);
  const [historyFor, setHistoryFor] = useState<Order | null>(null);
  const cancel = useMutation({
    mutationFn: (v: { id: number; reason: CancelReasonCode; note: string }) =>
      orderApi.setStatus(v.id, 'CANCELLED', v.reason, v.note),
    onSuccess: () => {
      setCancelling(null);
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
  });

  // The server returns newest first and pages it, so no client-side sort is needed.
  const rows = orders.data?.items ?? [];

  return (
    <>
      <div className="page-head">
        <h2>Orders</h2>
        <Link className="btn btn-sm" to="/admin/revenue">
          Payments &amp; revenue
        </Link>
      </div>
      {cancelling && (
        <CancelOrderDialog
          orderNumber={cancelling.orderNumber}
          busy={cancel.isPending}
          error={cancel.error}
          onClose={() => setCancelling(null)}
          onConfirm={(reason, note) => cancel.mutate({ id: cancelling.id, reason, note })}
        />
      )}
      {historyFor && (
        <OrderHistoryModal orderId={historyFor.id} orderNumber={historyFor.orderNumber} onClose={() => setHistoryFor(null)} />
      )}
      <QueryState isLoading={orders.isLoading} error={orders.error}>
        {rows.length === 0 ? (
          <EmptyState>No orders yet.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Order</th>
                  <th>Items</th>
                  <th>Status</th>
                  <th>Payment</th>
                  <th className="num">Total</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((o) => (
                  <tr key={o.id}>
                    <td data-label="Order">
                      {o.orderNumber}
                      {o.notes && <div className="muted">Note: {o.notes}</div>}
                    </td>
                    <td data-label="Items">{o.items.map((i) => lineLabel(i.quantity, i.itemName, i.options)).join(', ')}</td>
                    <td data-label="Status">
                      <StatusBadge status={o.status} />
                    </td>
                    <td data-label="Payment">
                      <PaymentCell order={o} onCollect={() => setCollectFor(o)} />
                    </td>
                    <td className="num" data-label="Total">
                      {money(o.total)}
                    </td>
                    <td className="num">
                      <span className="actions">
                        {o.status !== 'CANCELLED' && (
                          <Link className="btn btn-sm" to={`/admin/orders/${o.id}/receipt`}>
                            Receipt
                          </Link>
                        )}
                        <button className="btn btn-sm" onClick={() => setHistoryFor(o)}>
                          History
                        </button>
                        {(o.status === 'PENDING' || o.status === 'CONFIRMED') && (
                          <button
                            className="btn btn-sm btn-danger"
                            disabled={cancel.isPending}
                            onClick={() => {
                              cancel.reset();
                              setCancelling(o);
                            }}
                          >
                            Cancel
                          </button>
                        )}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {rows.length > 0 && (
          <nav className="pay-pager" aria-label="Pages">
            <button
              type="button"
              className="btn btn-sm"
              disabled={!orders.data?.hasPrevious}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </button>
            <span className="muted">
              Page {(orders.data?.page ?? 0) + 1} of {Math.max(1, orders.data?.totalPages ?? 1)} ·{' '}
              {orders.data?.totalItems ?? 0} orders
            </span>
            <button
              type="button"
              className="btn btn-sm"
              disabled={!orders.data?.hasNext}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </button>
          </nav>
        )}
      </QueryState>

      {collectFor && <CollectPaymentModal order={collectFor} onClose={() => setCollectFor(null)} />}
    </>
  );
}

/**
 * What an order has paid and what is left.
 *
 * <p>Reads the row, which now carries the payment state. It used to run its own query per order with
 * a 30s refetch, so a page of orders meant one HTTP request per row every half minute - and the
 * endpoint it polled returned every order ever placed, so the two multiplied.
 *
 * <p>The pending payment's id is still needed to confirm it, but only once the user asks: the
 * confirm dialog fetches it on open rather than every row fetching it on the chance it is clicked.
 */
function PaymentCell({ order, onCollect }: { order: Order; onCollect: () => void }) {
  const [confirming, setConfirming] = useState(false);

  const paid = order.amountPaid ?? 0;
  const due = order.balanceDue ?? order.total;
  const status = order.paymentStatus ?? null;
  const cancelled = order.status === 'CANCELLED';

  return (
    <div className="pay-order-cell">
      {status ? <StatusBadge status={status} /> : <span className="muted">Not paid</span>}

      {paid > 0 && <div className="muted pay-order-parts">{money(paid)} paid</div>}

      {due > 0 && paid > 0 && <div className="muted">{money(due)} still due</div>}

      {status === 'PENDING' && (
        <div className="muted">Diner chose a method; awaiting confirmation</div>
      )}

      {!cancelled && (
        <div className="row-actions">
          {status === 'PENDING' && (
            <button className="btn btn-sm" onClick={() => setConfirming(true)}>
              Confirm payment
            </button>
          )}
          {due > 0 && (
            <button className="btn btn-sm btn-primary" onClick={onCollect}>
              {paid > 0 ? 'Take the rest' : 'Take payment'}
            </button>
          )}
        </div>
      )}

      {confirming && (
        <ConfirmPaymentModal orderId={order.id} onClose={() => setConfirming(false)} />
      )}
    </div>
  );
}

/**
 * Confirms the payment a diner started. The UPI app or wallet is asked for here because this is the
 * moment someone can see it; it is never derived from the reference afterwards.
 */
function ConfirmPaymentModal({ orderId, onClose }: { orderId: number; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [provider, setProvider] = useState('');
  const [cardType, setCardType] = useState('');
  const [reference, setReference] = useState('');

  // Fetched when the dialog opens, not by every row on the chance it is clicked.
  const summary = useQuery({
    queryKey: ['order-payment-summary', orderId],
    queryFn: () => orderApi.paymentSummary(orderId),
  });

  const pending = summary.data?.payments.find((p) => p.paymentStatus === 'PENDING');
  const paymentId = pending?.id;
  const method = pending?.paymentMethod ?? '';
  const amount = pending?.amount ?? 0;

  const meta = useQuery({ queryKey: ['payment-meta'], queryFn: paymentAnalyticsApi.meta });

  const update = useMutation({
    mutationFn: (status: 'SUCCESS' | 'FAILED') =>
      orderApi.setPaymentStatus(paymentId!, {
        paymentStatus: status,
        paymentProvider: status === 'SUCCESS' && provider ? provider : null,
        cardType: status === 'SUCCESS' && cardType ? cardType : null,
        transactionId: status === 'SUCCESS' && reference.trim() ? reference.trim() : null,
      }),
    onSuccess: () => {
      for (const key of [
        ['orders'],
        ['order-payment-summary', orderId],
        ['payment-overview'],
        ['payment-transactions'],
        ['reconciliation'],
        ['dashboard'],
      ]) {
        void queryClient.invalidateQueries({ queryKey: key });
      }
      onClose();
    },
  });

  const methodMeta: PaymentMeta['methods'][number] | undefined = meta.data?.methods.find(
    (m) => m.key === method,
  );

  if (summary.isLoading) {
    return (
      <Modal title="Confirm payment" onClose={onClose}>
        <Spinner />
      </Modal>
    );
  }

  if (!pending) {
    // Someone else confirmed it, or the order was cancelled, between the list loading and this
    // dialog opening.
    return (
      <Modal title="Confirm payment" onClose={onClose}>
        <p>There is no payment awaiting confirmation on this order any more.</p>
        <div className="actions">
          <button className="btn" onClick={onClose}>
            Close
          </button>
        </div>
      </Modal>
    );
  }

  return (
    <Modal title="Confirm payment" onClose={onClose}>
      <p>
        The diner chose <strong>{methodMeta?.label ?? method.toLowerCase()}</strong> for{' '}
        <strong>{money(amount)}</strong>. Confirm it once the money is in hand.
      </p>

      {methodMeta && methodMeta.providers.length > 0 && (
        <label className="field">
          <span>Which app did they pay from?</span>
          <select value={provider} onChange={(e) => setProvider(e.target.value)}>
            <option value="">I don't know</option>
            {methodMeta.providers
              .filter((p) => !p.key.startsWith('UNKNOWN'))
              .map((p) => (
                <option key={p.key} value={p.key}>
                  {p.label}
                </option>
              ))}
          </select>
          <small className="muted">
            Left unset, this payment is reported as an unknown app rather than being guessed.
          </small>
        </label>
      )}

      {method === 'CARD' && (
        <label className="field">
          <span>Card type</span>
          <select value={cardType} onChange={(e) => setCardType(e.target.value)}>
            <option value="">I don't know</option>
            {(meta.data?.cardTypes ?? []).map((c) => (
              <option key={c.key} value={c.key}>
                {c.label}
              </option>
            ))}
          </select>
        </label>
      )}

      <label className="field">
        <span>Reference (optional)</span>
        <input type="text" maxLength={100} value={reference} onChange={(e) => setReference(e.target.value)} />
      </label>

      <ErrorMessage error={update.error} />

      <div className="actions">
        <button className="btn" disabled={update.isPending} onClick={() => update.mutate('FAILED')}>
          Payment failed
        </button>
        <button
          className="btn btn-primary"
          disabled={update.isPending}
          onClick={() => update.mutate('SUCCESS')}
        >
          Payment received
        </button>
      </div>
    </Modal>
  );
}
