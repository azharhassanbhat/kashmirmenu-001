import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link, Navigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { publicApi } from '../../api';
import { ApiError } from '../../api/client';
import type { DinerBill, PaymentMethod, PublicTable, Settlement, SettlementKind, TableBill } from '../../api/types';
import { CIcon } from '../../components/customer/CustomerIcon';
import { CustomerShell, StateCard } from '../../components/customer/CustomerShell';
import { JoinTable } from '../../components/customer/JoinTable';
import { UpiPanel } from '../../components/customer/UpiPanel';
import { ErrorMessage } from '../../components/ui';
import { parseServerTime, price } from '../../lib/format';
import {
  billBreakdown,
  describeSettlement,
  isValidUpiReference,
  kindLabel,
  methodTitle,
  lastAttemptThatDidNotPay,
  methodLabel,
  orderPayLabel,
  payChoices,
  pendingSettlement,
  shortReference,
  unavailableReason,
} from '../../lib/payment';
import { useDinerSession } from './useDinerSession';
import { usePayment } from './usePayment';

/**
 * Paying at the table. The guest explicitly chooses between two things:
 *
 *   Pay My Bill           their own items only
 *   Pay Entire Table Bill everyone's outstanding orders, so one guest can pay for the table
 *
 * and then how they will pay. Nothing is combined just because guests share a table, and nothing is
 * paid until a member of staff confirms the money arrived, so a started payment waits, visibly, until
 * they do. Every amount shown comes from the server.
 */
export default function PayPage() {
  const { qr = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const receiptCode = params.get('receipt');

  const table = useQuery({
    queryKey: ['public-table', qr],
    queryFn: () => publicApi.table(qr),
    retry: false,
    // The owner may replace or remove the UPI QR while a guest is paying: pick the change up without a reload.
    refetchInterval: 30_000,
  });
  const session = useDinerSession(qr, table.isSuccess);
  const ready = table.isSuccess && Boolean(session.diner.data);
  const payment = usePayment(qr, ready);
  const settlements = payment.settlements.data;

  // The payment the guest was waiting on has been confirmed: take them to its receipt.
  const watching = useRef<string | null>(null);
  useEffect(() => {
    if (!settlements) return;
    const waiting = pendingSettlement(settlements);
    if (waiting) {
      watching.current = waiting.code;
      return;
    }
    const code = watching.current;
    if (code) {
      watching.current = null;
      if (settlements.find((s) => s.code === code)?.status === 'SUCCESS') {
        setParams({ receipt: code }, { replace: true });
      }
    }
  }, [settlements, setParams]);

  if (table.isLoading) return <PaySkeleton />;
  if (table.error) {
    const notFound = table.error instanceof ApiError && table.error.status === 404;
    return (
      <StateCard title={notFound ? 'This QR code is not active' : 'Payment could not open'}>
        {notFound
          ? 'The table may have been removed or its code replaced. Please ask a member of staff for help.'
          : (table.error as Error).message}
      </StateCard>
    );
  }
  if (session.diner.isPending) return <PaySkeleton />;
  if (session.diner.error) {
    return (
      <StateCard
        title="Your table could not open"
        action={
          <button type="button" className="km-btn-fire" onClick={() => session.diner.refetch()}>
            Try again
          </button>
        }
      >
        {(session.diner.error as Error).message}
      </StateCard>
    );
  }
  const dinerData = session.diner.data;
  if (!dinerData) {
    return (
      <JoinTable
        restaurantName={table.data?.restaurantName}
        tableName={table.data?.tableName}
        busy={session.join.isPending}
        error={session.join.error ? (session.join.error as Error).message : null}
        onJoin={(name) => session.join.mutate(name)}
      />
    );
  }

  const info = table.data!;
  const guestName = dinerData.displayName;

  const frame = (children: ReactNode) => (
    <CustomerShell
      qr={qr}
      active="orders"
      restaurantName={info.restaurantName}
      tableName={info.tableName}
      reviewUrl={info.googleReviewUrl}
      mapsUrl={info.googleMapsUrl}
    >
      {children}
    </CustomerShell>
  );

  const bill = payment.bill.data;
  const tableBill = payment.tableBill.data;
  const loading = payment.bill.isLoading || payment.tableBill.isLoading || payment.settlements.isLoading;
  const failure = payment.bill.error ?? payment.tableBill.error ?? payment.settlements.error;

  if (failure) {
    return (
      <StateCard
        title="Your bill could not load"
        action={
          <button type="button" className="km-btn-fire" onClick={() => void payment.refreshAll()}>
            Try again
          </button>
        }
      >
        {(failure as Error).message}
      </StateCard>
    );
  }
  if (loading || !bill || !tableBill || !settlements) return <PaySkeleton />;

  // A receipt, if one was asked for and it is a received payment; anything else goes back to the screen.
  if (receiptCode) {
    const found = settlements.find((s) => s.code === receiptCode);
    if (found && found.status === 'SUCCESS') {
      return frame(<Receipt qr={qr} settlement={found} info={info} guestName={guestName} />);
    }
    return <Navigate to={`/menu/${qr}/pay`} replace />;
  }

  const waiting = pendingSettlement(settlements);
  if (waiting) {
    return frame(
      <WaitingForStaff
        settlement={waiting}
        info={info}
        cancelling={payment.cancel.isPending}
        cancelError={payment.cancel.error}
        onCancel={() => payment.cancel.mutate(waiting.code)}
        claiming={payment.claim.isPending}
        claimError={payment.claim.error}
        onClaim={(reference) => payment.claim.mutate({ code: waiting.code, reference })}
      />,
    );
  }

  return frame(
    <ChooseToPay
      qr={qr}
      guestName={guestName}
      bill={bill}
      tableBill={tableBill}
      info={info}
      methods={info.paymentMethods}
      settlements={settlements}
      starting={payment.start.isPending || payment.startAndClaim.isPending}
      startError={payment.start.error ?? payment.startAndClaim.error}
      onStart={(kind, method) => payment.start.mutate({ kind, method })}
      onStartUpi={(kind, reference) => payment.startAndClaim.mutate({ kind, reference })}
      onReceipt={(code) => setParams({ receipt: code })}
    />,
  );
}

// ---------------------------------------------------------------------------------------------------
// Choosing how to pay
// ---------------------------------------------------------------------------------------------------

function ChooseToPay({
  qr,
  guestName,
  bill,
  tableBill,
  info,
  methods,
  settlements,
  starting,
  startError,
  onStart,
  onStartUpi,
  onReceipt,
}: {
  qr: string;
  guestName: string;
  bill: DinerBill;
  tableBill: TableBill;
  info: PublicTable;
  methods: PublicTable['paymentMethods'];
  settlements: Settlement[];
  starting: boolean;
  startError: unknown;
  onStart: (kind: SettlementKind, method: PaymentMethod) => void;
  onStartUpi: (kind: SettlementKind, reference: string) => void;
  onReceipt: (code: string) => void;
}) {
  const [reference, setReference] = useState('');
  // Neither is chosen for the guest: they pick who is paying, and how.
  const [chosenKind, setChosenKind] = useState<SettlementKind | null>(null);
  const [chosenMethod, setChosenMethod] = useState<PaymentMethod | null>(null);

  const choices = payChoices(bill, tableBill);
  const billed = bill.orders.filter((o) => o.status !== 'CANCELLED');
  const breakdown = billBreakdown(bill.orders);
  const earlier = lastAttemptThatDidNotPay(settlements);

  // The bills refresh while this is open, so a choice that stops being possible is simply dropped.
  const kind = chosenKind && unavailableReason(chosenKind, choices) === null ? chosenKind : null;
  const method = chosenMethod && methods.includes(chosenMethod) ? chosenMethod : methods.length === 1 ? methods[0] : null;
  const amount = kind === 'COMBINED' ? choices.table.due : choices.mine.due;
  const upi = method === 'UPI' ? info.upi : null;
  const referenceOk = isValidUpiReference(reference);
  const canSubmit = kind !== null && method !== null && !starting && (method !== 'UPI' || (upi !== null && referenceOk));

  // A dropped connection may or may not have reached the server. Tapping again is safe.
  const networkFailure = startError instanceof ApiError && startError.status === 0;

  const submit = () => {
    if (!kind || !method || starting) return;
    if (method === 'UPI') onStartUpi(kind, reference);
    else onStart(kind, method);
  };

  return (
    <main className="km-orderpage km-payflow">
      <header className="km-confirm">
        <span className="km-confirm-mark" aria-hidden="true">
          <CIcon name="receipt" size={30} />
        </span>
        <h1>Pay</h1>
        <p className="km-confirm-hint">
          {guestName}, choose who is paying and how. A member of staff confirms the money.
        </p>
      </header>

      {earlier && (
        <p className={`km-pay-notice is-${describeSettlement(earlier, price).tone}`} role="status">
          <strong>{describeSettlement(earlier, price).title}.</strong> {describeSettlement(earlier, price).detail}
        </p>
      )}

      <section className="km-panel km-bill" aria-labelledby="km-pay-bill">
        <h2 className="km-panel-title" id="km-pay-bill">
          <CIcon name="bill" size={22} />
          Your bill
        </h2>
        {billed.length === 0 ? (
          <p className="km-muted">You have not ordered anything yet.</p>
        ) : (
          <dl>
            {billed.map((o) => (
              <div key={o.orderNumber}>
                <dt>
                  Order {o.orderNumber}
                  <small className="km-pay-order-state">{orderPayLabel(o)}</small>
                </dt>
                <dd>{price(o.total)}</dd>
              </div>
            ))}
            <div className="km-bill-parts" role="group" aria-label="How your bill is worked out">
              <div>
                <dt>Item subtotal</dt>
                <dd>{price(breakdown.subtotal)}</dd>
              </div>
              {breakdown.tax > 0 && (
                <div>
                  <dt>Taxes</dt>
                  <dd>{price(breakdown.tax)}</dd>
                </div>
              )}
              {breakdown.serviceCharge > 0 && (
                <div>
                  <dt>Service charge</dt>
                  <dd>{price(breakdown.serviceCharge)}</dd>
                </div>
              )}
              {breakdown.discount > 0 && (
                <div>
                  <dt>Discount</dt>
                  <dd>−{price(breakdown.discount)}</dd>
                </div>
              )}
            </div>
            {bill.paid > 0 && (
              <div>
                <dt>Already paid</dt>
                <dd>{price(bill.paid)}</dd>
              </div>
            )}
            {bill.inProgress > 0 && (
              <div>
                <dt>Waiting for staff to confirm</dt>
                <dd>{price(bill.inProgress)}</dd>
              </div>
            )}
            {bill.awaitingConfirmation > 0 && (
              <div>
                <dt>Waiting for the kitchen to confirm</dt>
                <dd>{price(bill.awaitingConfirmation)}</dd>
              </div>
            )}
            <div className="km-bill-total">
              <dt>To pay now</dt>
              <dd>{price(bill.due)}</dd>
            </div>
          </dl>
        )}
      </section>

      {!choices.anyAvailable ? (
        <section className="km-panel" aria-live="polite">
          <p className="km-muted">
            {choices.waitingForKitchen > 0
              ? 'Your order is waiting for the kitchen to confirm. You can pay as soon as it is.'
              : choices.inProgress > 0
                ? 'Your payment is already waiting for staff.'
                : bill.paid > 0
                  ? 'Everything you ordered is paid. Thank you.'
                  : 'There is nothing to pay right now.'}
          </p>
        </section>
      ) : (
        <>
          <fieldset className="km-panel km-pay-choice">
            <legend className="km-panel-title">Who is paying?</legend>

            <PayCard
              name="km-pay-kind"
              value="SEPARATE"
              title="Pay My Bill"
              hint="Pay for my items only."
              amount={choices.mine.due}
              checked={kind === 'SEPARATE'}
              reason={unavailableReason('SEPARATE', choices)}
              onChoose={() => setChosenKind('SEPARATE')}
            />
            <PayCard
              name="km-pay-kind"
              value="COMBINED"
              title="Pay Entire Table Bill"
              hint="Pay the table’s combined outstanding bill, so one person can pay for everyone."
              amount={choices.table.due}
              checked={kind === 'COMBINED'}
              reason={unavailableReason('COMBINED', choices)}
              onChoose={() => setChosenKind('COMBINED')}
            />

            {kind === 'COMBINED' && (
              <ul className="km-pay-breakdown" aria-label="The table’s bill, by person">
                {choices.table.guests.map((g) => (
                  <li key={g.displayName}>
                    <span>
                      {g.displayName}
                      {g.you ? ' (you)' : ''}
                      {g.due === 0 && g.inProgress > 0 && (
                        <small className="km-pay-order-state">payment already waiting for staff</small>
                      )}
                    </span>
                    <strong>{price(g.due)}</strong>
                  </li>
                ))}
                <li className="km-pay-breakdown-total">
                  <span>Table total</span>
                  <strong>{price(choices.table.due)}</strong>
                </li>
              </ul>
            )}
          </fieldset>

          <fieldset className="km-panel km-pay-choice">
            <legend className="km-panel-title">How will you pay?</legend>
            {methods.length === 0 ? (
              <p className="km-muted">Please ask a member of staff how to pay.</p>
            ) : (
              <div className="km-pay-methods">
                {methods.map((m) => (
                  <label key={m} className={`km-pay-method${method === m ? ' is-selected' : ''}`}>
                    <input
                      type="radio"
                      name="km-pay-method"
                      value={m}
                      checked={method === m}
                      onChange={() => setChosenMethod(m)}
                    />
                    <span>{methodTitle(m)}</span>
                  </label>
                ))}
              </div>
            )}
          </fieldset>

          {upi && kind && (
            <>
              <UpiPanel
                restaurantName={info.restaurantName}
                tableName={info.tableName}
                amount={amount}
                upi={upi}
                note={`${info.tableName} bill`}
              />
              <UpiReferenceField value={reference} onChange={setReference} valid={referenceOk} />
            </>
          )}

          {kind && method && method !== 'UPI' && (
            <p className="km-muted km-pay-summary" aria-live="polite">
              You will pay <strong>{price(amount)}</strong> by {methodLabel(method)}
              {kind === 'COMBINED' ? ', for the whole table' : ''}. Nothing is paid until a member of staff
              confirms they have received it.
            </p>
          )}

          <div className="km-alert-slot">
            <ErrorMessage error={startError} />
            {networkFailure && (
              <p className="km-muted">
                We could not tell whether that went through. Tap the button again: you will not be charged twice.
              </p>
            )}
          </div>

          <button type="button" className="km-btn-place" disabled={!canSubmit} aria-busy={starting} onClick={submit}>
            {starting
              ? 'Sending…'
              : kind && method === 'UPI'
                ? 'I’ve completed payment'
                : kind && method
                  ? `Pay ${price(amount)}`
                  : 'Choose how to pay'}
          </button>
        </>
      )}

      <PaymentHistory settlements={settlements} onReceipt={onReceipt} />

      <Link className="km-btn-ghost km-btn-block" to={`/menu/${qr}`}>
        Back to the menu
      </Link>
    </main>
  );
}

function UpiReferenceField({
  value,
  onChange,
  valid,
}: {
  value: string;
  onChange: (v: string) => void;
  valid: boolean;
}) {
  return (
    <div className="km-panel km-upi-ref">
      <label htmlFor="km-upi-ref">UPI transaction reference (optional)</label>
      <input
        id="km-upi-ref"
        type="text"
        inputMode="text"
        autoComplete="off"
        maxLength={35}
        value={value}
        aria-invalid={!valid}
        aria-describedby="km-upi-ref-hint"
        placeholder="e.g. 412345678901"
        onChange={(e) => onChange(e.target.value)}
      />
      <small id="km-upi-ref-hint" className={valid ? 'km-muted' : 'km-field-error'}>
        {valid
          ? 'Find it in your UPI app under the payment. Never share your UPI PIN or an OTP: we never ask for them.'
          : 'A reference is 6 to 35 letters or digits. You can leave it blank.'}
      </small>
      <p className="km-muted">Tapping the button tells the restaurant you have paid. They will verify it.</p>
    </div>
  );
}

function PayCard({
  name,
  value,
  title,
  hint,
  amount,
  checked,
  reason,
  onChoose,
}: {
  name: string;
  value: string;
  title: string;
  hint: string;
  amount: number;
  checked: boolean;
  /** Why this cannot be chosen; null when it can. */
  reason: string | null;
  onChoose: () => void;
}) {
  const disabled = reason !== null;
  return (
    <label className={`km-pay-card${checked ? ' is-selected' : ''}${disabled ? ' is-disabled' : ''}`}>
      <input type="radio" name={name} value={value} checked={checked} disabled={disabled} onChange={onChoose} />
      <span className="km-pay-card-body">
        <strong>{title}</strong>
        <small>{disabled ? reason : hint}</small>
      </span>
      <span className="km-pay-card-amount">{disabled ? '' : price(amount)}</span>
    </label>
  );
}

function PaymentHistory({
  settlements,
  onReceipt,
}: {
  settlements: Settlement[];
  onReceipt: (code: string) => void;
}) {
  if (settlements.length === 0) return null;
  return (
    <section className="km-panel" aria-labelledby="km-pay-history">
      <h2 className="km-panel-title" id="km-pay-history">
        <CIcon name="clock" size={22} />
        Your payments
      </h2>
      <ul className="km-pay-history">
        {settlements.map((s) => (
          <li key={s.code}>
            <span>
              <strong>{kindLabel(s.kind)}</strong>
              <small>
                {price(s.total)} · {methodLabel(s.paymentMethod)}
              </small>
            </span>
            <span className="km-pay-history-end">
              <span className={`km-status km-status-${s.status.toLowerCase()}`}>{describeSettlement(s, price).title}</span>
              {s.status === 'SUCCESS' && (
                <button type="button" className="km-link-btn" onClick={() => onReceipt(s.code)}>
                  Receipt
                </button>
              )}
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}

// ---------------------------------------------------------------------------------------------------
// Waiting for staff
// ---------------------------------------------------------------------------------------------------

function WaitingForStaff({
  settlement,
  info,
  cancelling,
  cancelError,
  onCancel,
  claiming,
  claimError,
  onClaim,
}: {
  settlement: Settlement;
  info: PublicTable;
  cancelling: boolean;
  cancelError: unknown;
  onCancel: () => void;
  claiming: boolean;
  claimError: unknown;
  onClaim: (reference: string) => void;
}) {
  const copy = describeSettlement(settlement, price);
  const [reference, setReference] = useState('');
  const referenceOk = isValidUpiReference(reference);
  // A UPI payment the guest has not yet said they made: show the QR again so they can finish paying.
  const needsClaim = settlement.paymentMethod === 'UPI' && !settlement.customerConfirmedAt;
  // Withdrawing is a deliberate two-step: one stray tap should not undo a payment that is in hand.
  const [asking, setAsking] = useState(false);

  return (
    <main className="km-orderpage km-payflow">
      <header className="km-confirm">
        <span className="km-confirm-mark" aria-hidden="true">
          <CIcon name="clock" size={30} />
        </span>
        <h1>{copy.title}</h1>
        <p className="km-confirm-number">
          {kindLabel(settlement.kind)} · <strong>{price(settlement.total)}</strong>
        </p>
        <p className="km-confirm-hint" aria-live="polite">
          {copy.detail}
        </p>
      </header>

      <section className="km-panel km-bill" aria-labelledby="km-pay-covers">
        <h2 className="km-panel-title" id="km-pay-covers">
          <CIcon name="bill" size={22} />
          This payment covers
        </h2>
        <dl>
          {settlement.guests.map((g) => (
            <div key={g.displayName}>
              <dt>
                {g.displayName}
                {g.you ? ' (you)' : ''}
              </dt>
              <dd>{price(g.amount)}</dd>
            </div>
          ))}
          <div className="km-bill-total">
            <dt>Total</dt>
            <dd>{price(settlement.total)}</dd>
          </div>
        </dl>
      </section>

      {needsClaim && (
        <>
          {info.upi ? (
            <UpiPanel
              restaurantName={info.restaurantName}
              tableName={info.tableName}
              amount={settlement.pending > 0 ? settlement.pending : settlement.total}
              upi={info.upi}
              note={`${info.tableName} bill`}
            />
          ) : (
            <p className="km-pay-notice is-bad" role="alert">
              UPI payment is currently unavailable for this restaurant.
            </p>
          )}
          <UpiReferenceField value={reference} onChange={setReference} valid={referenceOk} />
          <div className="km-alert-slot">
            <ErrorMessage error={claimError} />
          </div>
          <button
            type="button"
            className="km-btn-place"
            disabled={claiming || !referenceOk}
            aria-busy={claiming}
            onClick={() => onClaim(reference)}
          >
            {claiming ? 'Sending…' : 'I’ve completed payment'}
          </button>
        </>
      )}

      <p className="km-muted km-center" role="status">
        Checking automatically. You do not need to refresh.
      </p>

      <div className="km-alert-slot">
        <ErrorMessage error={cancelError} />
      </div>

      {asking ? (
        <div className="km-pay-confirm" role="group" aria-label="Cancel this payment?">
          <p>Cancel this payment? Nothing will be taken, and you can start again.</p>
          <button type="button" className="km-btn-fire" disabled={cancelling} onClick={onCancel}>
            {cancelling ? 'Cancelling…' : 'Yes, cancel it'}
          </button>
          <button type="button" className="km-btn-ghost" disabled={cancelling} onClick={() => setAsking(false)}>
            No, keep it
          </button>
        </div>
      ) : (
        <button type="button" className="km-btn-ghost km-btn-block" onClick={() => setAsking(true)}>
          Cancel this payment
        </button>
      )}
    </main>
  );
}

// ---------------------------------------------------------------------------------------------------
// Receipt
// ---------------------------------------------------------------------------------------------------

function Receipt({
  qr,
  settlement,
  info,
  guestName,
}: {
  qr: string;
  settlement: Settlement;
  info: PublicTable;
  guestName: string;
}) {
  const when = parseServerTime(settlement.confirmedAt ?? settlement.createdAt);
  const whenText = when
    ? when.toLocaleString('en-IN', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' })
    : '';

  return (
    <main className="km-orderpage km-payflow">
      <header className="km-confirm">
        <span className="km-confirm-mark" aria-hidden="true">
          <CIcon name="check" size={32} />
        </span>
        <h1>Payment received</h1>
        <p className="km-confirm-number">
          Receipt <strong>{shortReference(settlement.code)}</strong>
        </p>
        {whenText && <p className="km-confirm-time">{whenText}</p>}
      </header>

      <section className="km-panel km-bill km-receipt" aria-label="Receipt">
        <dl>
          <div>
            <dt>Restaurant</dt>
            <dd>{info.restaurantName}</dd>
          </div>
          <div>
            <dt>Table</dt>
            <dd>{info.tableName}</dd>
          </div>
          <div>
            <dt>Paid by</dt>
            <dd>{guestName}</dd>
          </div>
          <div>
            <dt>Method</dt>
            <dd>{methodTitle(settlement.paymentMethod)}</dd>
          </div>
          {settlement.reference && (
            <div>
              <dt>Staff reference</dt>
              <dd>{settlement.reference}</dd>
            </div>
          )}
          <div>
            <dt>Covers</dt>
            <dd>{kindLabel(settlement.kind)}</dd>
          </div>
          {settlement.guests.map((g) => (
            <div key={g.displayName}>
              <dt>
                {g.displayName}
                {g.you ? ' (you)' : ''}
              </dt>
              <dd>{price(g.amount)}</dd>
            </div>
          ))}
          <div className="km-bill-total">
            <dt>Total paid</dt>
            <dd>{price(settlement.confirmed)}</dd>
          </div>
        </dl>
      </section>

      <p className="km-muted km-center">Thank you for dining with us.</p>

      <Link className="km-btn-ghost km-btn-block" to={`/menu/${qr}/pay`}>
        Back to your bill
      </Link>
      <Link className="km-btn-ghost km-btn-block" to={`/menu/${qr}`}>
        Back to the menu
      </Link>
    </main>
  );
}

function PaySkeleton() {
  return (
    <div className="km">
      <main className="km-orderpage" role="status" aria-label="Loading your bill">
        <div className="km-panel km-shimmer km-sk-block km-sk-tall" />
        <div className="km-panel km-shimmer km-sk-block" />
      </main>
    </div>
  );
}
