import { FeedbackCard } from '../../components/customer/FeedbackCard';
import type { ReactNode } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { publicApi } from '../../api';
import type { OrderStatus, PaymentMethod } from '../../api/types';
import { ErrorMessage } from '../../components/ui';
import { ChinarLeaf, OrnamentDivider } from '../../components/customer/ChinarLeaf';
import { CIcon } from '../../components/customer/CustomerIcon';
import { CustomerShell, StateCard, useCustomerShell } from '../../components/customer/CustomerShell';
import { HelpBanner } from '../../components/customer/HelpBanner';
import { ReviewCallout } from '../../components/customer/GoogleReviews';
import { DalLakeScene } from '../../components/customer/DalLakeScene';
import { price, timeAgo, timeOfDay, titleCase } from '../../lib/format';
import { BillLines } from '../../components/customer/BillLines';
import { optionsSummary } from '../../lib/menuOptions';
import { useOrderStatusChime } from './useOrderStatusChime';

const STEPS: { status: OrderStatus; label: string; hint: string }[] = [
  { status: 'PENDING', label: 'Received', hint: 'The restaurant has your order.' },
  { status: 'CONFIRMED', label: 'Confirmed', hint: 'The kitchen has accepted your order.' },
  { status: 'PREPARING', label: 'Preparing', hint: 'Your dishes are being cooked.' },
  { status: 'READY', label: 'Ready', hint: 'Your food is on its way to the table.' },
  { status: 'SERVED', label: 'Served', hint: 'Enjoy your meal.' },
];
const FINAL: OrderStatus[] = ['SERVED', 'CANCELLED'];

export default function OrderPage() {
  // The URL carries the order's random public token; the readable ORD-n comes from the response.
  const { token = '' } = useParams();
  const [params] = useSearchParams();
  const qr = params.get('t');
  const justPlaced = params.get('placed') === '1';
  const queryClient = useQueryClient();

  const order = useQuery({
    queryKey: ['public-order', token],
    queryFn: () => publicApi.track(token),
    refetchInterval: (q) => (q.state.data && FINAL.includes(q.state.data.status) ? false : 5_000),
    retry: false,
  });
  const table = useQuery({
    queryKey: ['public-table', qr],
    queryFn: () => publicApi.table(qr!),
    enabled: !!qr,
    retry: false,
  });
  const cart = useQuery({
    queryKey: ['public-cart', qr],
    queryFn: () => publicApi.cart(qr!),
    enabled: !!qr && table.isSuccess,
  });
  const pay = useMutation({
    mutationFn: (method: PaymentMethod) => publicApi.pay(token, method),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['public-order', token] }),
  });

  // Before the early returns below: hooks must run on every render.
  const chime = useOrderStatusChime(token, order.data?.status);

  if (order.isLoading) {
    return (
      <Frame qr={qr} itemCount={0}>
        <main className="km-orderpage" role="status" aria-label="Loading your order">
          <div className="km-panel km-shimmer km-sk-block km-sk-tall" />
          <div className="km-panel km-shimmer km-sk-block" />
        </main>
      </Frame>
    );
  }
  if (order.error) {
    return (
      <StateCard
        title="Order not found"
        action={
          qr ? (
            <Link className="km-btn-ghost" to={`/menu/${qr}`}>
              Back to the menu
            </Link>
          ) : undefined
        }
      >
        Check the order number, or ask a member of staff.
      </StateCard>
    );
  }

  const o = order.data!;
  const cancelled = o.status === 'CANCELLED';
  const currentIndex = STEPS.findIndex((s) => s.status === o.status);
  const current = STEPS[currentIndex];
  const methods = table.data?.paymentMethods ?? [];
  // A bill can be part paid (split across methods, or settled at the counter), and a failed attempt
  // can be retried, so what matters is whether anything is still due and nothing is already in flight.
  const balanceDue = o.balanceDue ?? o.total;
  const canPay = !cancelled && balanceDue > 0 && o.paymentStatus !== 'PENDING';
  const itemCount = cart.data?.items.reduce((n, i) => n + i.quantity, 0) ?? 0;
  // Ask prominently once the food is at (or on its way to) the table.
  const foodArrived = o.status === 'READY' || o.status === 'SERVED';
  const reviewCard =
    !cancelled && qr && table.data?.googleReviewUrl ? (
      <ShellReviewCallout
        qr={qr}
        restaurantName={table.data.restaurantName}
        reviewUrl={table.data.googleReviewUrl}
        mapsUrl={table.data.googleMapsUrl}
        heading={o.status === 'SERVED' ? 'Enjoyed it? Tell others on Google' : `Enjoying ${table.data.restaurantName}?`}
      />
    ) : null;

  return (
    <Frame
      qr={qr}
      itemCount={itemCount}
      restaurantName={table.data?.restaurantName}
      tableName={o.tableName}
      reviewUrl={table.data?.googleReviewUrl}
      mapsUrl={table.data?.googleMapsUrl}
    >
      <main className="km-orderpage">
        <header className={`km-confirm${cancelled ? ' is-cancelled' : ''}`}>
          <span className="km-confirm-mark" aria-hidden="true">
            {cancelled ? <CIcon name="close" size={30} /> : <CIcon name="check" size={32} />}
          </span>
          <h1>{cancelled ? 'Order cancelled' : justPlaced ? 'Order placed' : 'Your order'}</h1>
          <p className="km-confirm-number">
            Order ID: <strong>{o.orderNumber}</strong>
            <span className="km-hero-table-pill">
              <CIcon name="table" size={14} />
              {o.tableName}
            </span>
          </p>
          {/* Diners had no idea when they ordered, which is the first thing anyone wants to know
              while waiting. Clock time answers "when", the elapsed time answers "how long". */}
          {o.createdAt && (
            <p className="km-confirm-time">
              Ordered at {timeOfDay(o.createdAt)}
              <span className="km-muted-inline"> · {timeAgo(o.createdAt)}</span>
            </p>
          )}
          <p className="km-confirm-hint" aria-live="polite">
            {cancelled
              ? `${o.cancelReasonLabel ? `${o.cancelReasonLabel}. ` : ''}Please speak to a member of staff.`
              : current
                ? current.hint
                : titleCase(o.status)}
          </p>
        </header>

        <HelpBanner />

        {chime.supported && (
          <button
            type="button"
            className="km-sound-toggle"
            aria-pressed={chime.enabled}
            onClick={chime.toggle}
          >
            Sound alerts: {chime.enabled ? 'On' : 'Off'}
          </button>
        )}

        {!cancelled && (
          <ol className="km-progress" aria-label={`Order status: ${titleCase(o.status)}`}>
            {STEPS.map((s, i) => (
              <li
                key={s.status}
                className={`${i <= currentIndex ? 'is-done' : ''}${i === currentIndex ? ' is-current' : ''}`}
                aria-current={i === currentIndex ? 'step' : undefined}
              >
                <span className="km-progress-dot" aria-hidden="true">
                  {i < currentIndex ? <CIcon name="check" size={14} /> : null}
                </span>
                <span className="km-progress-label">{s.label}</span>
              </li>
            ))}
          </ol>
        )}

        {o.status === 'SERVED' && (
          <FeedbackCard
            token={token}
            rating={o.feedbackRating}
            comment={o.feedbackComment}
            onSaved={() => void queryClient.invalidateQueries({ queryKey: ['public-order', token] })}
          />
        )}

        {foodArrived && reviewCard}

        <div className="km-order-grid">
          <section className="km-panel km-bill" aria-labelledby="km-order-items">
            <h2 className="km-panel-title" id="km-order-items">
              <CIcon name="bill" size={22} />
              What you ordered
            </h2>
            <dl>
              {o.items.map((i, idx) => (
                <div key={idx}>
                  <dt>
                    <span className="km-qty">{i.quantity}×</span> {i.itemName}
                    {(i.options?.length ?? 0) > 0 && <small className="km-line-opts">{optionsSummary(i.options)}</small>}
                  </dt>
                  <dd>{price(i.lineTotal)}</dd>
                </div>
              ))}
            </dl>
            <BillLines bill={o} itemsLabel="Subtotal" />
          </section>

          <section className="km-panel" aria-labelledby="km-pay-title">
            <h2 className="km-panel-title" id="km-pay-title">
              <CIcon name="receipt" size={22} />
              Payment
            </h2>
            {o.paymentStatus && (
              <p className="km-pay-status">
                <span className={`km-status km-status-${o.paymentStatus.toLowerCase()}`}>{titleCase(o.paymentStatus)}</span>
                {o.paymentStatus === 'PENDING' && <span>Staff will confirm your payment at the table.</span>}
                {o.paymentStatus === 'FAILED' && <span>That payment didn’t go through. You can choose another way to pay.</span>}
              </p>
            )}
            {o.amountPaid > 0 && balanceDue > 0 && (
              <p className="km-muted">
                {price(o.amountPaid)} of {price(o.total)} is paid. {price(balanceDue)} still to go.
              </p>
            )}
            {canPay && qr && (
              <>
                <p className="km-muted">
                  Ready to settle up? Pay your own bill, or pay for the whole table.
                </p>
                <Link className="km-btn-fire km-btn-block" to={`/menu/${qr}/pay`}>
                  Pay my bill
                </Link>
              </>
            )}
            {/* Opened from a bare link, with no table to pay at: the order's own payment choice. */}
            {canPay && !qr && (
              <>
                <p className="km-muted">
                  Choose how you plan to pay {o.amountPaid > 0 ? 'the rest' : ''}. This records your choice; a member of staff confirms the payment.
                </p>
                {methods.length === 0 && <p className="km-muted">Please ask staff how to pay.</p>}
                <div className="km-pay-options">
                  {methods.map((m) => (
                    <button
                      key={m}
                      type="button"
                      className="km-btn-ghost"
                      disabled={pay.isPending}
                      onClick={() => pay.mutate(m)}
                    >
                      {m === 'UPI' ? 'UPI' : titleCase(m)}
                    </button>
                  ))}
                </div>
                <div className="km-alert-slot">
                  <ErrorMessage error={pay.error} />
                </div>
              </>
            )}
          </section>
        </div>

        {!foodArrived && reviewCard}


        {!cancelled && (
          <Link className="km-btn-ghost km-btn-block" to={`/receipt/${token}`}>
            View receipt
          </Link>
        )}

        {qr && (
          <Link className="km-btn-ghost km-btn-block" to={`/menu/${qr}`}>
            Add more dishes
          </Link>
        )}

        <figure className="km-farewell">
          <DalLakeScene className="km-farewell-art" />
          <ChinarLeaf className="km-leaf km-farewell-leaf" />
          <figcaption>
            <span className="km-farewell-line">Good Food Brings People Together</span>
            <OrnamentDivider />
            <span className="km-farewell-thanks">Thank you for choosing Kashmir Menu</span>
          </figcaption>
        </figure>
      </main>
    </Frame>
  );
}

/** Rendered inside <CustomerShell>, so it can open the shell's reviews sheet. */
function ShellReviewCallout(props: Omit<Parameters<typeof ReviewCallout>[0], 'onOpenSheet'>) {
  const { openSheet } = useCustomerShell();
  return <ReviewCallout {...props} onOpenSheet={() => openSheet('reviews')} />;
}

/** Wraps the page in the diner shell when we know the table, otherwise just the theme. */
function Frame({
  qr,
  itemCount,
  restaurantName,
  tableName,
  reviewUrl,
  mapsUrl,
  children,
}: {
  qr: string | null;
  itemCount: number;
  restaurantName?: string;
  tableName?: string;
  reviewUrl?: string | null;
  mapsUrl?: string | null;
  children: ReactNode;
}) {
  if (!qr) return <div className="km">{children}</div>;
  return (
    <CustomerShell
      qr={qr}
      active="orders"
      itemCount={itemCount}
      restaurantName={restaurantName}
      tableName={tableName}
      reviewUrl={reviewUrl}
      mapsUrl={mapsUrl}
    >
      {children}
    </CustomerShell>
  );
}
