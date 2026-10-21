import { useState } from 'react';
import type { UseMutationResult } from '@tanstack/react-query';
import type { Cart } from '../../api/types';
import { CIcon } from './CustomerIcon';
import { price } from '../../lib/format';

/**
 * Coupon entry on the diner's bill.
 *
 * Codes are typed by hand, so the field uppercases as you go and the server matches
 * case-insensitively. Applying a code does not consume it - the server only claims a use when the
 * order is placed - so a diner can try a code, remove it, and try another without burning the
 * restaurant's limited run.
 *
 * The server owns the arithmetic: this component sends a code and renders whatever cart comes back.
 */
export function CouponBox({
  cart,
  apply,
  remove,
}: {
  cart: Cart;
  apply: UseMutationResult<Cart, Error, string>;
  remove: UseMutationResult<Cart, Error, void>;
}) {
  const [code, setCode] = useState('');
  const [open, setOpen] = useState(false);

  const applied = cart.couponCode != null;
  const error = apply.error ?? remove.error;
  const busy = apply.isPending || remove.isPending;

  if (applied) {
    return (
      <section className="km-panel km-coupon" aria-labelledby="km-coupon-title">
        <h2 className="km-panel-title" id="km-coupon-title">
          <CIcon name="bill" size={22} />
          Coupon
        </h2>
        <div className="km-coupon-applied">
          <div>
            <strong>{cart.couponCode}</strong>
            {cart.couponName && <span className="km-muted"> · {cart.couponName}</span>}
            <div className="km-coupon-saving">You save {price(cart.discount)}</div>
          </div>
          <button type="button" className="km-btn-ghost" disabled={busy} onClick={() => remove.mutate()}>
            {remove.isPending ? 'Removing…' : 'Remove'}
          </button>
        </div>
        {error && (
          <p className="km-alert" role="alert">
            {error.message}
          </p>
        )}
      </section>
    );
  }

  return (
    <section className="km-panel km-coupon" aria-labelledby="km-coupon-title">
      <h2 className="km-panel-title" id="km-coupon-title">
        <CIcon name="bill" size={22} />
        Coupon
      </h2>

      {!open ? (
        <button type="button" className="km-btn-ghost km-btn-block" onClick={() => setOpen(true)}>
          Have a coupon code?
        </button>
      ) : (
        <>
          <div className="km-coupon-entry">
            <label htmlFor="km-coupon-code" className="sr-only">
              Coupon code
            </label>
            <input
              id="km-coupon-code"
              type="text"
              inputMode="text"
              autoCapitalize="characters"
              autoComplete="off"
              spellCheck={false}
              maxLength={50}
              placeholder="e.g. WELCOME10"
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && code.trim() && !busy) {
                  apply.mutate(code.trim());
                }
              }}
            />
            <button
              type="button"
              className="km-btn-fire"
              disabled={busy || code.trim().length === 0}
              onClick={() => apply.mutate(code.trim())}
            >
              {apply.isPending ? 'Checking…' : 'Apply'}
            </button>
          </div>
          {error ? (
            <p className="km-alert" role="alert">
              {error.message}
            </p>
          ) : (
            <p className="km-muted km-coupon-hint">Ask a member of staff if you're not sure of the code.</p>
          )}
        </>
      )}
    </section>
  );
}
