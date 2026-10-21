import { useMemo, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { orderApi, paymentAnalyticsApi } from '../../api';
import type { Order, PaymentMeta } from '../../api/types';
import { ErrorMessage, Field, Modal, QueryState, StatusBadge } from '../ui';
import { money } from '../../lib/format';
import { methodColor } from './charts';

/**
 * Takes payment for an order at the counter.
 *
 * The payment method, and for UPI or a wallet the app it came from, are recorded here — at the moment
 * money changes hands — because that is the only point at which anyone actually knows them. Nothing is
 * inferred afterwards from a reference, so the breakdown on the Payments & Revenue page is only ever as
 * precise as what staff picked here, and says "unknown" otherwise.
 *
 * A bill can be settled with several methods at once (cash plus an app, or a card plus change in cash).
 * Each line becomes its own payment record, which is what keeps the method totals honest.
 */

interface Line {
  id: number;
  method: string;
  provider: string;
  cardType: string;
  amount: string;
  reference: string;
}

let nextLineId = 1;

function newLine(method: string, amount: number): Line {
  return {
    id: nextLineId++,
    method,
    provider: '',
    cardType: '',
    amount: amount > 0 ? String(amount) : '',
    reference: '',
  };
}

export function CollectPaymentModal({ order, onClose }: { order: Order; onClose: () => void }) {
  const queryClient = useQueryClient();
  // Stable for the lifetime of this dialog, so a double submit or a retry after a dropped
  // connection cannot record the money twice.
  const requestKey = useRef(`collect-${order.id}-${Date.now()}`);

  const meta = useQuery({ queryKey: ['payment-meta'], queryFn: paymentAnalyticsApi.meta });
  const summary = useQuery({
    queryKey: ['order-payment-summary', order.id],
    queryFn: () => orderApi.paymentSummary(order.id),
  });

  const outstanding = summary.data?.outstanding ?? order.total;
  const [lines, setLines] = useState<Line[]>([]);
  const [initialised, setInitialised] = useState(false);

  // Start with one line for the full remaining balance: the common case is a single method.
  if (!initialised && summary.isSuccess && meta.isSuccess) {
    const firstMethod = meta.data.methods[0]?.key ?? 'CASH';
    setLines([newLine(firstMethod, outstanding)]);
    setInitialised(true);
  }

  const entered = lines.reduce((sum, l) => sum + (Number(l.amount) || 0), 0);
  const remaining = Math.round((outstanding - entered) * 100) / 100;

  const problems = useMemo(() => validate(lines, outstanding, meta.data), [lines, outstanding, meta.data]);

  const collect = useMutation({
    mutationFn: () =>
      orderApi.collect(order.id, {
        payments: lines.map((l) => ({
          paymentMethod: l.method,
          paymentProvider: l.provider || null,
          cardType: l.cardType || null,
          amount: Number(l.amount),
          transactionId: l.reference.trim() || null,
        })),
        idempotencyKey: requestKey.current,
      }),
    onSuccess: () => {
      for (const key of [
        ['orders'],
        ['order-payment-summary', order.id],
        ['order-payment', order.id],
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

  function update(id: number, patch: Partial<Line>) {
    setLines((current) =>
      current.map((l) => {
        if (l.id !== id) return l;
        const next = { ...l, ...patch };
        // A provider or card type only belongs to its own method.
        if (patch.method !== undefined) {
          next.provider = '';
          next.cardType = '';
        }
        return next;
      }),
    );
  }

  return (
    <Modal title={`Take payment · ${order.orderNumber}`} onClose={onClose}>
      <QueryState isLoading={meta.isLoading || summary.isLoading} error={meta.error ?? summary.error}>
        {meta.data && summary.data && (
          <div className="pay-collect">
            <dl className="pay-lines">
              <div>
                <dt>Order total</dt>
                <dd>{money(summary.data.orderTotal)}</dd>
              </div>
              {summary.data.settled > 0 && (
                <div>
                  <dt>Already paid</dt>
                  <dd>{money(summary.data.settled)}</dd>
                </div>
              )}
              <div className="total">
                <dt>Due now</dt>
                <dd>{money(outstanding)}</dd>
              </div>
            </dl>

            {summary.data.pending > 0 && (
              <p className="pay-note">
                The diner chose a method on their phone ({money(summary.data.pending)} pending). Recording
                payment here replaces that choice.
              </p>
            )}

            {outstanding <= 0 ? (
              <p className="pay-note">This order is fully paid. Nothing more to take.</p>
            ) : (
              <>
                <ul className="pay-collect-lines">
                  {lines.map((line, index) => {
                    const method = meta.data.methods.find((m) => m.key === line.method);
                    return (
                      <li key={line.id} className="pay-collect-line">
                        <div className="pay-collect-head">
                          <span className="pay-collect-index">Part {index + 1}</span>
                          {lines.length > 1 && (
                            <button
                              type="button"
                              className="btn btn-sm btn-ghost"
                              onClick={() => setLines((c) => c.filter((l) => l.id !== line.id))}
                            >
                              Remove
                            </button>
                          )}
                        </div>

                        <div className="pay-method-choice" role="group" aria-label="Payment method">
                          {meta.data.methods.map((m) => (
                            <button
                              key={m.key}
                              type="button"
                              className={`pay-chip${line.method === m.key ? ' on' : ''}`}
                              aria-pressed={line.method === m.key}
                              onClick={() => update(line.id, { method: m.key })}
                            >
                              <span
                                className="pay-swatch"
                                style={{ background: methodColor(m.key) }}
                                aria-hidden="true"
                              />
                              {m.label}
                            </button>
                          ))}
                        </div>

                        <div className="form-row">
                          <Field label="Amount">
                            <input
                              type="number"
                              min="0.01"
                              step="0.01"
                              inputMode="decimal"
                              value={line.amount}
                              onChange={(e) => update(line.id, { amount: e.target.value })}
                            />
                          </Field>

                          {method && method.providers.length > 0 && (
                            <Field
                              label={`${method.label} app`}
                              hint="Leave unset if you don't know; it will report as unknown rather than a guess."
                            >
                              <select
                                value={line.provider}
                                onChange={(e) => update(line.id, { provider: e.target.value })}
                              >
                                <option value="">Not recorded</option>
                                {method.providers
                                  .filter((p) => !p.key.startsWith('UNKNOWN'))
                                  .map((p) => (
                                    <option key={p.key} value={p.key}>
                                      {p.label}
                                    </option>
                                  ))}
                              </select>
                            </Field>
                          )}

                          {line.method === 'CARD' && (
                            <Field label="Card type">
                              <select
                                value={line.cardType}
                                onChange={(e) => update(line.id, { cardType: e.target.value })}
                              >
                                <option value="">Not recorded</option>
                                {meta.data.cardTypes.map((c) => (
                                  <option key={c.key} value={c.key}>
                                    {c.label}
                                  </option>
                                ))}
                              </select>
                            </Field>
                          )}

                          <Field label="Reference (optional)" hint="Terminal or UPI reference, if you have one.">
                            <input
                              type="text"
                              maxLength={100}
                              value={line.reference}
                              onChange={(e) => update(line.id, { reference: e.target.value })}
                            />
                          </Field>
                        </div>
                      </li>
                    );
                  })}
                </ul>

                <div className="pay-collect-foot">
                  <button
                    type="button"
                    className="btn btn-sm"
                    disabled={lines.length >= 10 || remaining <= 0}
                    onClick={() =>
                      setLines((c) => [...c, newLine(meta.data.methods[0]?.key ?? 'CASH', Math.max(0, remaining))])
                    }
                  >
                    Split across another method
                  </button>
                  <p className={`pay-collect-balance${remaining < 0 ? ' over' : ''}`}>
                    {remaining === 0
                      ? 'The parts add up to the amount due.'
                      : remaining > 0
                        ? `${money(remaining)} of this bill would still be unpaid.`
                        : `${money(Math.abs(remaining))} more than is due.`}
                  </p>
                </div>

                {problems.length > 0 && (
                  <ul className="pay-problems" role="alert">
                    {problems.map((p) => (
                      <li key={p}>{p}</li>
                    ))}
                  </ul>
                )}

                <ErrorMessage error={collect.error} />

                <div className="actions">
                  <button type="button" className="btn" onClick={onClose}>
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="btn btn-primary"
                    disabled={collect.isPending || problems.length > 0}
                    onClick={() => collect.mutate()}
                  >
                    {collect.isPending ? 'Recording…' : `Record ${money(entered)}`}
                  </button>
                </div>
              </>
            )}

            {summary.data.payments.length > 0 && (
              <section className="pay-detail-section">
                <h4>Already on this order</h4>
                <ul className="pay-detail-list">
                  {summary.data.payments.map((p) => (
                    <li key={p.id}>
                      <span>
                        {p.paymentMethod}
                        {p.paymentProvider && <span className="muted"> · {p.paymentProvider}</span>}
                      </span>
                      <span>{money(p.amount)}</span>
                      <StatusBadge status={p.paymentStatus} />
                    </li>
                  ))}
                </ul>
              </section>
            )}
          </div>
        )}
      </QueryState>
    </Modal>
  );
}

/** Client-side mirror of the server's rules, so mistakes are caught before the request. */
function validate(lines: Line[], outstanding: number, meta: PaymentMeta | undefined): string[] {
  const problems: string[] = [];
  if (lines.length === 0) {
    problems.push('Add at least one payment.');
    return problems;
  }
  let total = 0;
  for (const [index, line] of lines.entries()) {
    const amount = Number(line.amount);
    if (!line.amount || Number.isNaN(amount) || amount <= 0) {
      problems.push(`Part ${index + 1} needs an amount above zero.`);
      continue;
    }
    if (/\.\d{3,}$/.test(line.amount.trim())) {
      problems.push(`Part ${index + 1} can have at most two decimal places.`);
    }
    total += amount;
    const method = meta?.methods.find((m) => m.key === line.method);
    if (line.provider && method && !method.providers.some((p) => p.key === line.provider)) {
      problems.push(`Part ${index + 1}: that app does not belong to ${method.label}.`);
    }
  }
  if (Math.round(total * 100) > Math.round(outstanding * 100)) {
    problems.push(`The parts add up to more than the ${money(outstanding)} due on this order.`);
  }
  return problems;
}
