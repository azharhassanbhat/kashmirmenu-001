import { useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { ChinarLeaf } from '../../components/customer/ChinarLeaf';
import { CIcon } from '../../components/customer/CustomerIcon';
import { CouponBox } from '../../components/customer/CouponBox';
import { CustomerShell, StateCard } from '../../components/customer/CustomerShell';
import { DishImage } from '../../components/customer/DishImage';
import { JoinTable } from '../../components/customer/JoinTable';
import { QuantityStepper } from '../../components/customer/QuantityStepper';
import { VegMark } from '../../components/customer/VegMark';
import { price } from '../../lib/format';
import { BillLines } from '../../components/customer/BillLines';
import { optionsSummary } from '../../lib/menuOptions';
import { useTableOrdering } from './useTableOrdering';

export default function CheckoutPage() {
  const { qr = '' } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const t = useTableOrdering(qr);
  const [notes, setNotes] = useState('');

  const goBack = () => {
    // Return to wherever the diner came from inside the app, else to the menu.
    if (location.key !== 'default') navigate(-1);
    else navigate(`/menu/${qr}`);
  };

  if (t.table.error) {
    const notFound = t.table.error instanceof ApiError && t.table.error.status === 404;
    return (
      <StateCard title={notFound ? 'This QR code is not active' : 'Your order could not open'}>
        {notFound
          ? 'The table may have been removed or its code replaced. Please ask a member of staff for help.'
          : (t.table.error as Error).message}
      </StateCard>
    );
  }

  // The cart is the guest's own, so a name comes first.
  if (t.diner.error) {
    return (
      <StateCard title="Your order could not open">{(t.diner.error as Error).message}</StateCard>
    );
  }
  if (t.diner.data === null) {
    return (
      <JoinTable
        restaurantName={t.table.data?.restaurantName}
        tableName={t.table.data?.tableName}
        busy={t.join.isPending}
        error={t.join.error ? (t.join.error as Error).message : null}
        onJoin={(name) => t.join.mutate(name)}
      />
    );
  }

  const info = t.table.data;
  const cart = t.cart.data;
  const loading = t.table.isLoading || t.diner.isPending || t.cart.isLoading;
  const lines = cart?.items ?? [];

  // Dishes cook side by side, so the longest preparation time is the honest estimate.
  const prepMinutes = lines.reduce((m, l) => Math.max(m, t.menuItemsById.get(l.menuItemId)?.preparationTime ?? 0), 0);

  const placeOrder = () =>
    t.place.mutate(notes, {
      // The URL carries the order's random token, not its sequential number.
      //
      // Guarded because the order has already been placed by the time this runs: if the token were
      // missing - a backend older than this build, say - navigating to /order/undefined would strand
      // the diner on a dead page with a real order behind it. Falling back to the menu at least
      // tells them the order exists and what it is called.
      onSuccess: (order) => {
        if (!order.publicToken) {
          navigate(`/menu/${qr}?placed=${encodeURIComponent(order.orderNumber)}`, { replace: true });
          return;
        }
        navigate(`/order/${order.publicToken}?t=${qr}&placed=1`, { replace: true });
      },
    });

  return (
    <CustomerShell
      qr={qr}
      active="cart"
      itemCount={t.itemCount}
      restaurantName={info?.restaurantName}
      tableName={info?.tableName}
    >
      <main className="km-checkout">
        <header className="km-checkout-head">
          <button type="button" className="km-icon-btn" onClick={goBack} aria-label="Back">
            <CIcon name="back" />
          </button>
          <div>
            <h1>Your Order</h1>
            <p>Review and place your order</p>
          </div>
          <ChinarLeaf className="km-checkout-leaf" />
        </header>

        {loading && (
          <div className="km-checkout-grid" role="status" aria-label="Loading your order">
            <div className="km-stack">
              <div className="km-line km-shimmer km-sk-block" />
              <div className="km-line km-shimmer km-sk-block" />
            </div>
            <div className="km-panel km-shimmer km-sk-block km-sk-tall" />
          </div>
        )}

        {!loading && t.cart.error && (
          <div className="km-alert" role="alert">
            <span>Your cart could not load. {(t.cart.error as Error).message}</span>
            <button type="button" className="km-btn-ghost" onClick={() => t.cart.refetch()}>
              Retry
            </button>
          </div>
        )}

        {!loading && cart && lines.length === 0 && (
          <div className="km-empty km-empty-roomy">
            <ChinarLeaf size={56} />
            <h2>Your order is empty</h2>
            <p>Add a few dishes from the menu and they will appear here for everyone at {info?.tableName ?? 'your table'}.</p>
            <Link className="km-btn-fire" to={`/menu/${qr}`}>
              Browse the menu
            </Link>
          </div>
        )}

        {!loading && cart && lines.length > 0 && (
          <div className="km-checkout-grid">
            <div className="km-stack">
              <ul className="km-lines" aria-label="Dishes in your order">
                {lines.map((line) => {
                  const dish = t.menuItemsById.get(line.menuItemId);
                  const working = t.pendingMenuItemId === line.menuItemId;
                  return (
                    <li className={`km-line${working ? ' is-working' : ''}`} key={line.id}>
                      <DishImage src={dish?.imageUrl ?? null} size={160} className="km-line-img" />
                      <div className="km-line-body">
                        <div className="km-line-name">
                          {dish && <VegMark veg={dish.veg} />}
                          <h2>{line.menuItemName}</h2>
                        </div>
                        {(line.options?.length ?? 0) > 0 && <p className="km-line-opts">{optionsSummary(line.options)}</p>}
                        <div className="km-line-prices">
                          <span className="km-price">{price(line.lineTotal)}</span>
                          {line.quantity > 1 && <small>{price(line.unitPrice)} each</small>}
                        </div>
                        <div className="km-line-actions">
                          <QuantityStepper
                            name={line.menuItemName}
                            quantity={line.quantity}
                            disabled={t.busy || t.place.isPending}
                            working={working}
                            onChange={(q) => t.changeQuantity(line.id, q)}
                            size="sm"
                          />
                          <button
                            type="button"
                            className="km-icon-btn km-icon-btn-quiet"
                            onClick={() => t.removeLine(line.id)}
                            disabled={t.busy || t.place.isPending}
                            aria-label={`Remove ${line.menuItemName} from your order`}
                          >
                            <CIcon name="trash" size={20} />
                          </button>
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>

              <label className="km-panel km-notes">
                <span className="km-panel-title">
                  <CIcon name="note" size={20} />
                  Notes for the kitchen
                  <small>Optional</small>
                </span>
                <textarea
                  rows={2}
                  maxLength={500}
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Less spicy, no onions, bring the kahwa after the meal…"
                />
              </label>

              {t.cartError != null && (
                <div className="km-alert" role="alert">
                  {(t.cartError as Error).message}
                </div>
              )}
            </div>

            <aside className="km-stack km-checkout-side" aria-label="Bill">
              <section className="km-panel km-bill" aria-labelledby="km-bill-title">
                <h2 className="km-panel-title" id="km-bill-title">
                  <CIcon name="bill" size={22} />
                  Bill Summary
                </h2>
                <BillLines bill={cart} itemsLabel={`Items (${t.itemCount})`} />
                <p className="km-bill-note">
                  <CIcon name="info" size={16} />
                  Totals are calculated by the restaurant. Everyone at {info?.tableName ?? 'this table'} shares this
                  order.
                </p>
              </section>

              <CouponBox cart={cart} apply={t.applyCoupon} remove={t.removeCoupon} />

              {t.place.error != null && (
                <div className="km-alert" role="alert">
                  {(t.place.error as Error).message}
                </div>
              )}

              <button
                type="button"
                className="km-btn-place"
                onClick={placeOrder}
                disabled={t.place.isPending || t.busy}
                aria-busy={t.place.isPending}
              >
                <span>{t.place.isPending ? 'Placing your order…' : 'Place Order'}</span>
                {!t.place.isPending && <CIcon name="arrowRight" size={22} />}
                <ChinarLeaf className="km-btn-place-leaf" tone="orange" veins />
              </button>

              {prepMinutes > 0 && (
                <p className="km-prep">
                  <CIcon name="clock" size={18} />
                  Estimated preparation time: about {prepMinutes} min
                </p>
              )}
            </aside>
          </div>
        )}
      </main>
    </CustomerShell>
  );
}
