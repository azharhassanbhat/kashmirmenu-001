import type { Receipt } from '../api/types';
import { chargeRows, includedTaxNote } from '../lib/charges';
import { price } from '../lib/format';
import { addressLines, headingTone, quantityLine, receiptWhen, registrationLine } from '../lib/receipt';

/**
 * A bill as it is printed. Everything shown is what the order recorded when it was placed: nothing is
 * recalculated here, so the receipt cannot disagree with what the guest was shown. The charge rows come from
 * the same helper as the guest's order page, so the two describe a bill identically. It deliberately shows no
 * guest name, phone or feedback.
 */
export function ReceiptView({ receipt, narrow }: { receipt: Receipt; narrow: boolean }) {
  const note = includedTaxNote(receipt, price);
  const regLine = registrationLine(receipt.taxName, receipt.restaurant.taxRegistrationNumber);
  const tone = headingTone(receipt.paymentHeading);

  return (
    <article className={`receipt${narrow ? ' receipt-narrow' : ''}`} aria-label={`Receipt for order ${receipt.orderNumber}`}>
      <header className="receipt-head">
        <h1>{receipt.restaurant.name}</h1>
        {addressLines(receipt.restaurant).map((line) => (
          <p key={line}>{line}</p>
        ))}
        {receipt.restaurant.phone && <p>Tel {receipt.restaurant.phone}</p>}
        {regLine && <p className="receipt-reg">{regLine}</p>}
      </header>

      <p className="receipt-meta">
        <span>Order {receipt.orderNumber}</span>
        {receipt.tableName && <span>{receipt.tableName}</span>}
        <span>{receiptWhen(receipt.placedAt)}</span>
      </p>

      <table className="receipt-items">
        <tbody>
          {receipt.items.map((item, i) => (
            <tr key={i}>
              <td>
                <strong>{item.name}</strong>
                {item.options.map((o) => (
                  <small key={o} className="receipt-option">
                    {o}
                  </small>
                ))}
                <small className="receipt-qty">{quantityLine(item.quantity, price(item.unitPrice))}</small>
              </td>
              <td className="num">{price(item.lineTotal)}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <dl className="receipt-bill">
        {chargeRows(receipt, 'Items').map((row) => (
          <div key={row.key}>
            <dt>{row.label}</dt>
            <dd>
              {row.kind === 'discount' ? '\u2212' : ''}
              {price(row.amount)}
            </dd>
          </div>
        ))}
        <div className="receipt-total">
          <dt>Total</dt>
          <dd>{price(receipt.total)}</dd>
        </div>
      </dl>
      {note && <p className="receipt-note">{note}</p>}

      <section className="receipt-pay" aria-label="Payment">
        <p className={`receipt-heading is-${tone}`}>{receipt.paymentHeading}</p>
        {receipt.payments.length > 0 && (
          <dl className="receipt-bill">
            {receipt.payments.map((p, i) => (
              <div key={i}>
                <dt>{p.method}</dt>
                <dd>{price(p.amount)}</dd>
              </div>
            ))}
            {receipt.refunded > 0 && (
              <div>
                <dt>Refunded</dt>
                <dd>{'\u2212'}{price(receipt.refunded)}</dd>
              </div>
            )}
          </dl>
        )}
        {receipt.balanceDue > 0 && (
          <dl className="receipt-bill">
            <div className="receipt-total">
              <dt>Balance due</dt>
              <dd>{price(receipt.balanceDue)}</dd>
            </div>
          </dl>
        )}
      </section>

      <footer className="receipt-foot">
        <p>Thank you for dining with us.</p>
        <p className="receipt-fineprint">This is a receipt for your meal.</p>
      </footer>
    </article>
  );
}
