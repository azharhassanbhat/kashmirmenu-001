import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { settlementApi, tableSessionApi } from '../../api';
import type { SettlementStaff, TableSessionDetail, TableSessionSummary } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { EmptyState, ErrorMessage, Modal, QueryState, StatusBadge } from '../../components/ui';
import {
  closeReasonLabel,
  closeWarning,
  howPaid,
  owed,
  payStateLabel,
  payStateTone,
  sortLive,
} from '../../lib/floor';
import { money, timeAgo, timeOfDay } from '../../lib/format';
import { methodTitle } from '../../lib/payment';
import { useRestaurantTopic } from '../../lib/useRestaurantTopic';

/**
 * The owner's live view of the floor: payments guests have started and staff must confirm, the tables
 * that are open (who is at them, what they owe), and tables recently closed.
 *
 * Every figure comes from the server, worked out from the same order and payment records the guest
 * screens use, so what staff see here is what guests are told. Closing a table is never blocked by money
 * owing; the screen says what is owed first, so it is never a surprise.
 */
export function LiveTablesPanel() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const [openId, setOpenId] = useState<number | null>(null);

  const active = useQuery({ queryKey: ['live-tables'], queryFn: tableSessionApi.active, refetchInterval: 10_000 });
  const recent = useQuery({ queryKey: ['live-tables-recent'], queryFn: tableSessionApi.recent, refetchInterval: 30_000 });
  const pending = useQuery({ queryKey: ['live-settlements'], queryFn: settlementApi.pending, refetchInterval: 5_000 });

  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['live-tables'] }),
      queryClient.invalidateQueries({ queryKey: ['live-tables-recent'] }),
      queryClient.invalidateQueries({ queryKey: ['live-settlements'] }),
      queryClient.invalidateQueries({ queryKey: ['live-table'] }),
    ]);

  // Orders and payments announce themselves over the socket; polling covers anything it misses.
  useRestaurantTopic(user?.restaurantId, () => {
    void refresh();
  });

  const waiting = pending.data ?? [];
  const tables = sortLive(active.data ?? []);
  const closed = recent.data ?? [];

  return (
    <>
      <div className="page-head">
        <h2>Live tables</h2>
        <span className="muted">Updates by itself</span>
      </div>

      {waiting.length > 0 && <PaymentsToConfirm payments={waiting} onDone={refresh} />}

      <QueryState isLoading={active.isLoading} error={active.error}>
        {tables.length === 0 ? (
          <EmptyState>No tables are open. A table opens when a guest scans its QR code.</EmptyState>
        ) : (
          <div className="live-grid">
            {tables.map((t) => (
              <TableCard key={t.id} table={t} onOpen={() => setOpenId(t.id)} />
            ))}
          </div>
        )}
      </QueryState>

      {closed.length > 0 && (
        <section className="live-recent" aria-labelledby="live-recent-title">
          <h3 id="live-recent-title">Recently closed</h3>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Table</th>
                  <th>Closed</th>
                  <th>Bill</th>
                  <th className="num">Still owed</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {closed.map((t) => (
                  <tr key={t.id}>
                    <td data-label="Table">{t.tableName}</td>
                    <td data-label="Closed">
                      {t.closedAt ? timeOfDay(t.closedAt) : ''}
                      <div className="muted">{closeReasonLabel(t.closeReason)}</div>
                    </td>
                    <td data-label="Bill">
                      <span className={`badge badge-${payStateTone(t.payState)}`}>{payStateLabel(t.payState)}</span>
                    </td>
                    <td className="num" data-label="Still owed">
                      {money(owed(t))}
                    </td>
                    <td className="num">
                      <button type="button" className="btn btn-sm" onClick={() => setOpenId(t.id)}>
                        View
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {openId !== null && (
        <TableDetailModal
          id={openId}
          onClose={() => setOpenId(null)}
          onChanged={() => {
            void refresh();
          }}
        />
      )}
    </>
  );
}

/** The page inside the admin layout. */
export default function LiveTablesPage() {
  return <LiveTablesPanel />;
}

// ---------------------------------------------------------------------------------------------------
// Payments waiting for staff
// ---------------------------------------------------------------------------------------------------

function PaymentsToConfirm({ payments, onDone }: { payments: SettlementStaff[]; onDone: () => Promise<unknown> }) {
  const decide = useMutation({
    mutationFn: (v: { id: number; status: 'SUCCESS' | 'FAILED'; reference: string }) =>
      settlementApi.setStatus(v.id, v.status, v.reference),
    onSuccess: () => onDone(),
  });

  return (
    <section className="live-confirm" aria-labelledby="live-confirm-title">
      <h3 id="live-confirm-title">
        Payments to confirm <span className="badge badge-amber">{payments.length}</span>
      </h3>
      <p className="muted">
        A guest has chosen how to pay. Nothing counts as paid until you confirm you have the money.
      </p>
      <ErrorMessage error={decide.error} />
      <div className="live-confirm-list">
        {payments.map((p) => (
          <PaymentToConfirm
            key={p.id}
            payment={p}
            busy={decide.isPending && decide.variables?.id === p.id}
            onDecide={(status, reference) => decide.mutate({ id: p.id, status, reference })}
          />
        ))}
      </div>
    </section>
  );
}

function PaymentToConfirm({
  payment,
  busy,
  onDecide,
}: {
  payment: SettlementStaff;
  busy: boolean;
  onDecide: (status: 'SUCCESS' | 'FAILED', reference: string) => void;
}) {
  // A UPI reference the guest typed in is a suggestion to check against the bank statement, not proof.
  const [reference, setReference] = useState(payment.customerReference ?? '');
  const claimed = payment.customerConfirmedAt !== null;

  return (
    <div className="card live-confirm-card">
      <div className="live-confirm-top">
        <div>
          <strong>{payment.tableName}</strong>
          <div className="muted">
            {payment.payerName} · {howPaid(payment.kind)} · {methodTitle(payment.paymentMethod)}
          </div>
        </div>
        <div className="live-confirm-amount">{money(payment.pending > 0 ? payment.pending : payment.total)}</div>
      </div>

      {payment.paymentMethod === 'UPI' && (
        <p className={`live-upi-claim${claimed ? ' is-claimed' : ''}`} role="status">
          {claimed ? (
            <>
              <strong>Customer says they have paid</strong> ({timeAgo(payment.customerConfirmedAt!)}). Check the money
              reached your UPI account before confirming.
              {payment.customerReference && (
                <>
                  {' '}
                  Their reference: <code>{payment.customerReference}</code>
                </>
              )}
            </>
          ) : (
            <>Waiting for the customer to pay by UPI. They have not confirmed yet.</>
          )}
        </p>
      )}

      <ul className="live-confirm-orders" aria-label="Orders this covers">
        {payment.orders.map((o) => (
          <li key={o.orderNumber}>
            <span>
              {o.orderNumber} · {o.guestName}
            </span>
            <span>{money(o.amount)}</span>
          </li>
        ))}
      </ul>

      <div className="live-confirm-actions">
        <input
          type="text"
          value={reference}
          maxLength={100}
          placeholder="Receipt or reference (optional)"
          aria-label="Receipt or reference, optional"
          onChange={(e) => setReference(e.target.value)}
        />
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => onDecide('SUCCESS', reference)}>
          {busy ? 'Saving…' : payment.paymentMethod === 'UPI' ? 'Payment received' : 'Money received'}
        </button>
        <button type="button" className="btn btn-danger" disabled={busy} onClick={() => onDecide('FAILED', '')}>
          {payment.paymentMethod === 'UPI' ? 'Reject payment' : 'Didn’t go through'}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------------------------------
// An open table
// ---------------------------------------------------------------------------------------------------

function TableCard({ table, onOpen }: { table: TableSessionSummary; onOpen: () => void }) {
  const totalOwed = owed(table);
  return (
    <button
      type="button"
      className={`live-card${table.needsStaff ? ' needs-staff' : ''}`}
      onClick={onOpen}
      aria-label={`${table.tableName}, ${payStateLabel(table.payState)}. Open details.`}
    >
      <span className="live-card-top">
        <strong>{table.tableName}</strong>
        <span className={`badge badge-${payStateTone(table.payState)}`}>{payStateLabel(table.payState)}</span>
      </span>

      <span className="live-card-meta muted">
        {table.guestCount} {table.guestCount === 1 ? 'guest' : 'guests'} · opened {timeAgo(table.openedAt)}
      </span>

      {table.needsStaff && (
        <span className="live-card-flag" role="status">
          Idle for a while with {money(totalOwed)} still owed. Needs someone to look at it.
        </span>
      )}
      {table.idle && !table.needsStaff && <span className="live-card-flag is-quiet">Idle. Closes by itself shortly.</span>}

      <span className="live-card-amounts">
        <span>
          <small>To pay</small>
          <strong>{money(table.due)}</strong>
        </span>
        {table.inProgress > 0 && (
          <span>
            <small>Waiting for you</small>
            <strong>{money(table.inProgress)}</strong>
          </span>
        )}
        {table.awaitingConfirmation > 0 && (
          <span>
            <small>Not confirmed</small>
            <strong>{money(table.awaitingConfirmation)}</strong>
          </span>
        )}
        <span>
          <small>Paid</small>
          <strong>{money(table.paid)}</strong>
        </span>
      </span>
    </button>
  );
}

// ---------------------------------------------------------------------------------------------------
// One table in detail
// ---------------------------------------------------------------------------------------------------

function TableDetailModal({ id, onClose, onChanged }: { id: number; onClose: () => void; onChanged: () => void }) {
  const queryClient = useQueryClient();
  const detail = useQuery({
    queryKey: ['live-table', id],
    queryFn: () => tableSessionApi.detail(id),
    refetchInterval: 10_000,
  });

  const settled = (data: TableSessionDetail) => {
    queryClient.setQueryData(['live-table', id], data);
    onChanged();
  };
  const close = useMutation({ mutationFn: () => tableSessionApi.close(id), onSuccess: settled });
  const reopen = useMutation({ mutationFn: () => tableSessionApi.reopen(id), onSuccess: settled });

  const [confirming, setConfirming] = useState(false);
  const d = detail.data;

  return (
    <Modal title={d ? d.summary.tableName : 'Table'} onClose={onClose}>
      <QueryState isLoading={detail.isLoading} error={detail.error}>
        {d && (
          <div className="live-detail">
            <div className="live-detail-head">
              <span className={`badge badge-${payStateTone(d.summary.payState)}`}>{payStateLabel(d.summary.payState)}</span>
              <span className="muted">
                {d.summary.guestCount} {d.summary.guestCount === 1 ? 'guest' : 'guests'} · opened {timeOfDay(d.summary.openedAt)}
                {d.summary.status === 'OPEN' ? ` · last activity ${timeAgo(d.summary.lastActivityAt)}` : ''}
              </span>
            </div>

            {d.summary.status === 'CLOSED' && (
              <p className="alert alert-info">
                {closeReasonLabel(d.summary.closeReason)}
                {d.closedBy ? ` by ${d.closedBy}` : ''}
                {d.summary.closedAt ? ` at ${timeOfDay(d.summary.closedAt)}` : ''}.
              </p>
            )}

            <dl className="live-totals">
              <div>
                <dt>To pay</dt>
                <dd>{money(d.summary.due)}</dd>
              </div>
              <div>
                <dt>Waiting for you</dt>
                <dd>{money(d.summary.inProgress)}</dd>
              </div>
              <div>
                <dt>Not confirmed</dt>
                <dd>{money(d.summary.awaitingConfirmation)}</dd>
              </div>
              <div>
                <dt>Paid</dt>
                <dd>{money(d.summary.paid)}</dd>
              </div>
            </dl>

            <h4>Guests</h4>
            {d.guests.length === 0 && <p className="muted">Nobody has joined.</p>}
            {d.guests.map((g) => (
              <section className="live-guest" key={g.id} aria-label={g.displayName}>
                <div className="live-guest-head">
                  <strong>{g.displayName}</strong>
                  <span className={`badge badge-${payStateTone(g.payState)}`}>{payStateLabel(g.payState)}</span>
                  <span className="muted">
                    owes {money(g.due + g.inProgress + g.awaitingConfirmation)} · paid {money(g.paid)}
                  </span>
                </div>
                {g.orders.length === 0 ? (
                  <p className="muted">No orders yet.</p>
                ) : (
                  <div className="table-wrap">
                    <table>
                      <thead>
                        <tr>
                          <th>Order</th>
                          <th>Items</th>
                          <th>Status</th>
                          <th>Bill</th>
                          <th className="num">Total</th>
                          <th className="num">Paid</th>
                        </tr>
                      </thead>
                      <tbody>
                        {g.orders.map((o) => (
                          <tr key={o.id}>
                            <td data-label="Order">{o.orderNumber}</td>
                            <td data-label="Items">{o.items.map((i) => `${i.quantity}× ${i.name}`).join(', ')}</td>
                            <td data-label="Status">
                              <StatusBadge status={o.status} />
                            </td>
                            <td data-label="Bill">
                              <span className={`badge badge-${payStateTone(o.payState)}`}>{payStateLabel(o.payState)}</span>
                            </td>
                            <td className="num" data-label="Total">
                              {money(o.total)}
                            </td>
                            <td className="num" data-label="Paid">
                              {money(o.paid)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            ))}

            <h4>Payments</h4>
            {d.payments.length === 0 ? (
              <p className="muted">Nothing has been paid or started yet.</p>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Order</th>
                      <th>How</th>
                      <th>Method</th>
                      <th>Status</th>
                      <th className="num">Amount</th>
                    </tr>
                  </thead>
                  <tbody>
                    {d.payments.map((p) => (
                      <tr key={p.id}>
                        <td data-label="When">{timeOfDay(p.at)}</td>
                        <td data-label="Order">
                          {p.orderNumber}
                          <div className="muted">{p.guestName}</div>
                        </td>
                        <td data-label="How">{howPaid(p.settlementKind)}</td>
                        <td data-label="Method">
                          {methodTitle(p.method)}
                          {p.transactionId && <div className="muted">{p.transactionId}</div>}
                        </td>
                        <td data-label="Status">
                          <StatusBadge status={p.status} />
                        </td>
                        <td className="num" data-label="Amount">
                          {money(p.amount)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <ErrorMessage error={close.error ?? reopen.error} />

            <div className="live-detail-actions">
              {d.summary.status === 'OPEN' && !confirming && (
                <button type="button" className="btn btn-danger" onClick={() => setConfirming(true)}>
                  Close table
                </button>
              )}
              {d.summary.status === 'OPEN' && confirming && (
                <div className="live-close-confirm" role="group" aria-label="Close this table?">
                  <p>{closeWarning(d.summary, money)}</p>
                  <button type="button" className="btn btn-danger" disabled={close.isPending} onClick={() => close.mutate()}>
                    {close.isPending ? 'Closing…' : 'Close table'}
                  </button>
                  <button type="button" className="btn" disabled={close.isPending} onClick={() => setConfirming(false)}>
                    Keep it open
                  </button>
                </div>
              )}
              {d.summary.status === 'CLOSED' && (
                <button type="button" className="btn btn-primary" disabled={reopen.isPending} onClick={() => reopen.mutate()}>
                  {reopen.isPending ? 'Reopening…' : 'Reopen table'}
                </button>
              )}
            </div>
          </div>
        )}
      </QueryState>
    </Modal>
  );
}
