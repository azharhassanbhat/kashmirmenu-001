import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { orderApi, paymentAnalyticsApi } from '../../api';
import type { AnalyticsFilters, PaymentTransaction, Reconciliation } from '../../api/types';
import { EmptyState, ErrorMessage, Field, Icon, Modal, QueryState, StatusBadge } from '../ui';
import { businessDateTime } from '../../lib/charts';
import { money } from '../../lib/format';
import { methodColor } from './charts';

/* ------------------------------------------------------------------ history table */

type SortKey = 'time' | 'amount' | 'method' | 'status';

const COLUMNS: { key: SortKey; label: string; numeric?: boolean }[] = [
  { key: 'time', label: 'Date & time' },
  { key: 'method', label: 'Method' },
  { key: 'status', label: 'Status' },
  { key: 'amount', label: 'Amount', numeric: true },
];

export function TransactionHistory({ filters }: { filters: AnalyticsFilters }) {
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [page, setPage] = useState(0);
  const [sortBy, setSortBy] = useState<SortKey>('time');
  const [direction, setDirection] = useState<'asc' | 'desc'>('desc');
  const [openId, setOpenId] = useState<number | null>(null);
  const [exportError, setExportError] = useState<unknown>(null);

  // One request per pause in typing rather than one per keystroke.
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(timer);
  }, [search]);

  useEffect(() => setPage(0), [debounced, filters, sortBy, direction]);

  const query = useQuery({
    queryKey: ['payment-transactions', filters, debounced, page, sortBy, direction],
    queryFn: () =>
      paymentAnalyticsApi.transactions(filters, { search: debounced, page, size: 20, sortBy, direction }),
  });

  const exportCsv = useMutation({
    mutationFn: () => paymentAnalyticsApi.exportCsv(filters, debounced),
    onSuccess: (blob) => {
      setExportError(null);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `payments-${filters.period.toLowerCase()}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    },
    onError: setExportError,
  });

  function sort(key: SortKey) {
    if (key === sortBy) {
      setDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
    } else {
      setSortBy(key);
      setDirection('desc');
    }
  }

  const items = query.data?.items ?? [];

  return (
    <section className="pay-history" aria-label="Payment history">
      <header className="pay-history-head">
        <h3>Payment history</h3>
        <div className="pay-history-tools">
          <label className="pay-search">
            <span className="sr-only">Search by reference or order number</span>
            <Icon name="orders" />
            <input
              type="search"
              value={search}
              placeholder="Reference or order number"
              onChange={(e) => setSearch(e.target.value)}
            />
          </label>
          <button
            type="button"
            className="btn btn-sm"
            disabled={exportCsv.isPending}
            onClick={() => exportCsv.mutate()}
          >
            {exportCsv.isPending ? 'Preparing…' : 'Export CSV'}
          </button>
        </div>
      </header>

      <ErrorMessage error={exportError} />

      <QueryState isLoading={query.isLoading} error={query.error}>
        {items.length === 0 ? (
          <EmptyState icon="card">
            {debounced
              ? `Nothing matches “${debounced}” in this period.`
              : 'No payments recorded in this period.'}
          </EmptyState>
        ) : (
          <>
            <div className="table-wrap pay-table">
              <table>
                <thead>
                  <tr>
                    {COLUMNS.map((c) => (
                      <th
                        key={c.key}
                        className={c.numeric ? 'num' : undefined}
                        aria-sort={sortBy === c.key ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'}
                      >
                        <button type="button" className="pay-sort" onClick={() => sort(c.key)}>
                          {c.label}
                          {sortBy === c.key && <span aria-hidden="true">{direction === 'asc' ? ' ↑' : ' ↓'}</span>}
                        </button>
                      </th>
                    ))}
                    <th className="num">Refunded</th>
                    <th>Recorded by</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {items.map((t) => (
                    <tr key={t.id}>
                      <td data-label="Date & time">
                        {businessDateTime(t.timestamp)}
                        <div className="muted pay-ref">
                          {t.reference}
                          {!t.hasGatewayReference && <span className="muted"> (no gateway reference)</span>}
                        </div>
                      </td>
                      <td data-label="Method">
                        <MethodCell transaction={t} />
                      </td>
                      <td data-label="Status">
                        <StatusBadge status={t.status} />
                      </td>
                      <td className="num" data-label="Amount">
                        {money(t.amount)}
                      </td>
                      <td className="num" data-label="Refunded">
                        {t.refundedAmount > 0 ? money(t.refundedAmount) : <span className="muted">—</span>}
                      </td>
                      <td data-label="Recorded by">
                        {t.recordedBy ?? <span className="muted">Not recorded</span>}
                      </td>
                      <td className="num">
                        <button type="button" className="btn btn-sm" onClick={() => setOpenId(t.id)}>
                          Details
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <nav className="pay-pager" aria-label="Pages">
              <button
                type="button"
                className="btn btn-sm"
                disabled={!query.data?.hasPrevious}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Previous
              </button>
              <span className="muted">
                Page {(query.data?.page ?? 0) + 1} of {Math.max(1, query.data?.totalPages ?? 1)} ·{' '}
                {query.data?.totalItems ?? 0} payments
              </span>
              <button
                type="button"
                className="btn btn-sm"
                disabled={!query.data?.hasNext}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </button>
            </nav>
          </>
        )}
      </QueryState>

      {openId !== null && <TransactionDetail paymentId={openId} onClose={() => setOpenId(null)} />}
    </section>
  );
}

function MethodCell({ transaction }: { transaction: PaymentTransaction }) {
  return (
    <span className="pay-method-cell">
      <span className="pay-swatch" style={{ background: methodColor(transaction.method) }} aria-hidden="true" />
      <span>
        {transaction.methodLabel}
        {transaction.providerLabel && (
          <span className={`muted${transaction.provider?.startsWith('UNKNOWN') ? ' pay-unknown' : ''}`}>
            {' '}
            · {transaction.providerLabel}
          </span>
        )}
        {transaction.cardType && <span className="muted"> · {transaction.cardType.toLowerCase()}</span>}
      </span>
    </span>
  );
}

/* ------------------------------------------------------------------ detail drawer */

function TransactionDetail({ paymentId, onClose }: { paymentId: number; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [refundAmount, setRefundAmount] = useState('');
  const [reason, setReason] = useState('');

  const detail = useQuery({
    queryKey: ['payment-transaction', paymentId],
    queryFn: () => paymentAnalyticsApi.transaction(paymentId),
  });

  const refund = useMutation({
    mutationFn: () => orderApi.refund(paymentId, { amount: Number(refundAmount), reason: reason || null }),
    onSuccess: () => {
      setRefundAmount('');
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['payment-transaction', paymentId] });
      void queryClient.invalidateQueries({ queryKey: ['payment-transactions'] });
      void queryClient.invalidateQueries({ queryKey: ['payment-overview'] });
      void queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
    },
  });

  const t = detail.data?.transaction;
  const refundable = t ? t.amount - t.refundedAmount : 0;
  const canRefund = t ? ['SUCCESS', 'PARTIALLY_REFUNDED'].includes(t.status) && refundable > 0 : false;

  return (
    <Modal title="Payment details" onClose={onClose}>
      <QueryState isLoading={detail.isLoading} error={detail.error}>
        {detail.data && t && (
          <div className="pay-detail">
            <dl className="pay-detail-grid">
              <div>
                <dt>Amount</dt>
                <dd className="pay-detail-amount">{money(t.amount)}</dd>
              </div>
              <div>
                <dt>Status</dt>
                <dd>
                  <StatusBadge status={t.status} />
                </dd>
              </div>
              <div>
                <dt>Method</dt>
                <dd>
                  <MethodCell transaction={t} />
                </dd>
              </div>
              <div>
                <dt>Taken</dt>
                <dd>{businessDateTime(t.timestamp)}</dd>
              </div>
              <div>
                <dt>Reference</dt>
                <dd>{t.hasGatewayReference ? t.reference : <span className="muted">None recorded</span>}</dd>
              </div>
              <div>
                <dt>Order</dt>
                <dd>
                  {t.orderNumber}
                  {detail.data.tableName && <span className="muted"> · {detail.data.tableName}</span>}
                  <div className="muted">Order total {money(detail.data.orderTotal)}</div>
                </dd>
              </div>
              <div>
                <dt>Recorded by</dt>
                <dd>{t.recordedBy ?? <span className="muted">Not recorded</span>}</dd>
              </div>
              <div>
                <dt>Bank settlement</dt>
                <dd>
                  {t.settledAt ? (
                    businessDateTime(t.settledAt)
                  ) : (
                    <span className="muted">Not marked settled</span>
                  )}
                </dd>
              </div>
            </dl>

            {detail.data.otherPaymentsOnOrder.length > 0 && (
              <section className="pay-detail-section">
                <h4>Rest of this order</h4>
                <p className="muted">
                  This order was paid in {detail.data.otherPaymentsOnOrder.length + 1} parts.
                </p>
                <ul className="pay-detail-list">
                  {detail.data.otherPaymentsOnOrder.map((o) => (
                    <li key={o.id}>
                      <MethodCell transaction={o} />
                      <span>{money(o.amount)}</span>
                      <StatusBadge status={o.status} />
                    </li>
                  ))}
                </ul>
              </section>
            )}

            <section className="pay-detail-section">
              <h4>Refunds</h4>
              {detail.data.refunds.length === 0 ? (
                <p className="muted">Nothing refunded on this payment.</p>
              ) : (
                <ul className="pay-detail-list">
                  {detail.data.refunds.map((r) => (
                    <li key={r.id}>
                      <span>
                        {money(r.amount)}
                        {r.reason && <span className="muted"> · {r.reason}</span>}
                      </span>
                      <span className="muted">{businessDateTime(r.refundedAt)}</span>
                      <span className="muted">{r.refundedBy ?? ''}</span>
                    </li>
                  ))}
                </ul>
              )}

              {canRefund && (
                <div className="pay-refund-form">
                  <Field label="Refund amount" hint={`Up to ${money(refundable)} can still be refunded.`}>
                    <input
                      type="number"
                      min="0.01"
                      max={refundable}
                      step="0.01"
                      inputMode="decimal"
                      value={refundAmount}
                      onChange={(e) => setRefundAmount(e.target.value)}
                    />
                  </Field>
                  <Field label="Reason (optional)">
                    <input
                      type="text"
                      maxLength={255}
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      placeholder="Dish returned"
                    />
                  </Field>
                  <ErrorMessage error={refund.error} />
                  <div className="actions">
                    <button
                      type="button"
                      className="btn btn-danger"
                      disabled={
                        refund.isPending ||
                        !refundAmount ||
                        Number(refundAmount) <= 0 ||
                        Number(refundAmount) > refundable
                      }
                      onClick={() => refund.mutate()}
                    >
                      Refund {refundAmount ? money(Number(refundAmount)) : ''}
                    </button>
                  </div>
                </div>
              )}
            </section>
          </div>
        )}
      </QueryState>
    </Modal>
  );
}

/* ------------------------------------------------------------------ reconciliation */

export function ReconciliationPanel({ today }: { today: string }) {
  const queryClient = useQueryClient();
  const [date, setDate] = useState(today);
  const [counted, setCounted] = useState('');
  const [note, setNote] = useState('');

  const query = useQuery({
    queryKey: ['reconciliation', date],
    queryFn: () => paymentAnalyticsApi.reconciliation(date),
  });

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['reconciliation'] });
    void queryClient.invalidateQueries({ queryKey: ['payment-overview'] });
  };

  const record = useMutation({
    mutationFn: () =>
      paymentAnalyticsApi.recordCashCount({ date, countedCash: Number(counted), note: note || undefined }),
    onSuccess: () => {
      setCounted('');
      setNote('');
      invalidate();
    },
  });

  const settle = useMutation({
    mutationFn: () => paymentAnalyticsApi.markSettled(date),
    onSuccess: invalidate,
  });

  const r = query.data;
  const difference = useMemo(() => {
    if (!r || counted === '' || Number.isNaN(Number(counted))) return null;
    return Number(counted) - r.cash.expected;
  }, [r, counted]);

  return (
    <section className="pay-reconcile" aria-label="Daily reconciliation">
      <header className="pay-history-head">
        <div>
          <h3>Day's close</h3>
          <p className="muted">
            Count the drawer against what the system expects, and confirm which digital payments have
            reached the bank.
          </p>
        </div>
        <label className="field pay-reconcile-date">
          <span>Business day</span>
          <input type="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} />
        </label>
      </header>

      <QueryState isLoading={query.isLoading} error={query.error}>
        {r && (
          <div className="pay-reconcile-grid">
            <article className="card">
              <h4>Cash drawer</h4>
              <dl className="pay-lines">
                <div>
                  <dt>Cash taken</dt>
                  <dd>{money(r.cash.collected)}</dd>
                </div>
                <div>
                  <dt>Cash refunded</dt>
                  <dd>−{money(r.cash.refunded)}</dd>
                </div>
                <div className="total">
                  <dt>Should be in the drawer</dt>
                  <dd>{money(r.cash.expected)}</dd>
                </div>
              </dl>

              <div className="pay-count-form">
                <Field label="Counted" hint="The expected figure is worked out by the system, not typed in.">
                  <input
                    type="number"
                    min="0"
                    step="0.01"
                    inputMode="decimal"
                    value={counted}
                    onChange={(e) => setCounted(e.target.value)}
                  />
                </Field>
                {difference !== null && (
                  <p className={`pay-difference ${difference === 0 ? 'ok' : difference < 0 ? 'short' : 'over'}`}>
                    {difference === 0
                      ? 'Matches exactly.'
                      : `${difference < 0 ? 'Short' : 'Over'} by ${money(Math.abs(difference))}.`}
                  </p>
                )}
                <Field label="Note (optional)">
                  <input type="text" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
                </Field>
                <ErrorMessage error={record.error} />
                <div className="actions">
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={record.isPending || counted === ''}
                    onClick={() => record.mutate()}
                  >
                    Save count
                  </button>
                </div>
              </div>

              {r.cash.counts.length > 0 && <CountHistory counts={r.cash.counts} />}
              {r.cash.byStaff.length > 0 && (
                <div className="pay-staff-cash">
                  <h5>Cash by the person who recorded it</h5>
                  <ul>
                    {r.cash.byStaff.map((s) => (
                      <li key={s.staffId ?? 'none'}>
                        <span>{s.name}</span>
                        <span className="muted">
                          {s.transactions} {s.transactions === 1 ? 'payment' : 'payments'}
                        </span>
                        <span>{money(s.amount)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </article>

            <div className="pay-reconcile-side">
            <article className="card">
              <h4>Digital payments</h4>
              {r.digital.length === 0 ? (
                <p className="muted">No digital payments on this day.</p>
              ) : (
                <dl className="pay-lines">
                  {r.digital.map((d) => (
                    <div key={d.method}>
                      <dt>
                        <span className="pay-swatch" style={{ background: methodColor(d.method) }} aria-hidden="true" />
                        {d.label}
                        <span className="muted"> · {d.transactions}</span>
                      </dt>
                      <dd>{money(d.collected)}</dd>
                    </div>
                  ))}
                  <div className="total">
                    <dt>Collected</dt>
                    <dd>{money(r.digitalTotal)}</dd>
                  </div>
                </dl>
              )}

              <div className="pay-settlement">
                <h5>Reached the bank</h5>
                <p className="muted">
                  {r.settlement.gatewayConnected
                    ? 'Settlement figures come from the payment gateway.'
                    : 'No gateway is connected, so settlement is confirmed by hand. Collecting a payment is not the same as the money arriving.'}
                </p>
                <p className="pay-settle-lines">
                  <span>
                    Settled: {money(r.settlement.settledAmount)}{' '}
                    <span className="muted">({r.settlement.settledCount})</span>
                  </span>
                  <span>
                    Not yet: {money(r.settlement.unsettledAmount)}{' '}
                    <span className="muted">({r.settlement.unsettledCount})</span>
                  </span>
                </p>
                <ErrorMessage error={settle.error} />
                <button
                  type="button"
                  className="btn btn-sm"
                  disabled={settle.isPending || r.settlement.unsettledCount === 0}
                  onClick={() => settle.mutate()}
                >
                  Mark {r.settlement.unsettledCount} as settled
                </button>
              </div>
            </article>

            <article className="card pay-closing">
              <h4>Closing summary</h4>
              <dl className="pay-lines">
                <div>
                  <dt>Collected</dt>
                  <dd>{money(r.closing.grossCollected)}</dd>
                </div>
                <div>
                  <dt>Refunded</dt>
                  <dd>−{money(r.closing.refunds)}</dd>
                </div>
                <div className="total">
                  <dt>Net for the day</dt>
                  <dd>{money(r.closing.netCollected)}</dd>
                </div>
                <div>
                  <dt>Of which cash</dt>
                  <dd>{money(r.closing.cashNet)}</dd>
                </div>
                <div>
                  <dt>Of which digital</dt>
                  <dd>{money(r.closing.digitalNet)}</dd>
                </div>
                {r.closing.otherNet !== 0 && (
                  <div>
                    <dt>Other methods</dt>
                    <dd>{money(r.closing.otherNet)}</dd>
                  </div>
                )}
              </dl>
              {r.pending.count > 0 && (
                <p className="pay-note warn">
                  {r.pending.count} {r.pending.count === 1 ? 'payment is' : 'payments are'} still waiting for
                  confirmation ({money(r.pending.amount)}). They are not in any figure above.
                </p>
              )}
              {r.closing.latestCashDifference != null && r.closing.latestCashDifference !== 0 && (
                <p className="pay-note warn">
                  The last cash count was{' '}
                  {r.closing.latestCashDifference < 0 ? 'short' : 'over'} by{' '}
                  {money(Math.abs(r.closing.latestCashDifference))}.
                </p>
              )}
            </article>
            </div>
          </div>
        )}
      </QueryState>
    </section>
  );
}

function CountHistory({ counts }: { counts: Reconciliation['cash']['counts'] }) {
  return (
    <div className="pay-count-history">
      <h5>Counts recorded</h5>
      <ul>
        {counts.map((c) => (
          <li key={c.id}>
            <span>{money(c.counted)}</span>
            <span className={c.difference === 0 ? 'muted' : c.difference < 0 ? 'pay-short' : 'pay-over'}>
              {c.difference === 0
                ? 'matched'
                : `${c.difference < 0 ? 'short' : 'over'} ${money(Math.abs(c.difference))}`}
            </span>
            <span className="muted">
              {c.countedBy ?? 'Unknown'} · {businessDateTime(c.recordedAt)}
            </span>
            {c.note && <span className="muted pay-count-note">{c.note}</span>}
          </li>
        ))}
      </ul>
      <p className="muted">
        Each count keeps the expected figure from the moment it was taken, so later payments do not
        rewrite history.
      </p>
    </div>
  );
}
