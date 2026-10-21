import { chargeRows, includedTaxNote, type ChargeSource } from '../../lib/charges';
import { price } from '../../lib/format';

/**
 * The rows of a bill: items, discount, service charge, tax, then the total. Rows with nothing to show
 * are left out, so a restaurant with no tax or service charge shows exactly items and total.
 * Used by the cart and by the order page so the two can never describe the same bill differently.
 */
export function BillLines({ bill, itemsLabel }: { bill: ChargeSource; itemsLabel: string }) {
  const note = includedTaxNote(bill, price);
  return (
    <>
      <dl>
        {chargeRows(bill, itemsLabel).map((row) => (
          <div key={row.key} className={row.kind === 'discount' ? 'km-bill-discount' : undefined}>
            <dt>{row.label}</dt>
            <dd>
              {row.kind === 'discount' ? '\u2212' : ''}
              {price(row.amount)}
            </dd>
          </div>
        ))}
        <div className="km-bill-total">
          <dt>Total Amount</dt>
          <dd>{price(bill.total)}</dd>
        </div>
      </dl>
      {note && <p className="km-bill-included">{note}</p>}
    </>
  );
}
