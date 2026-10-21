import { useState } from 'react';
import type { PublicUpi } from '../../api/types';
import { price } from '../../lib/format';
import { upiPayLink } from '../../lib/payment';
import { CIcon } from './CustomerIcon';

/**
 * "Pay with UPI": the restaurant's own uploaded QR, loaded from the server for THIS table's restaurant,
 * with the amount shown beside it. The uploaded QR is a static picture of the restaurant's UPI ID and
 * does not contain the bill, so the guest is told to enter the amount shown (the Open UPI app link does
 * carry it). Nothing here marks anything paid.
 */
export function UpiPanel({
  restaurantName,
  tableName,
  amount,
  upi,
  note,
}: {
  restaurantName: string;
  tableName: string;
  /** The server's figure for what this payment covers. */
  amount: number;
  upi: PublicUpi;
  /** Shown in the UPI app as the payment note, e.g. the table. */
  note: string;
}) {
  const [imageFailed, setImageFailed] = useState(false);
  const [copied, setCopied] = useState<'done' | 'failed' | null>(null);
  const recipient = upi.recipientName || restaurantName;
  const link = upiPayLink(upi.upiId, recipient, amount, note);

  async function copyId() {
    try {
      await navigator.clipboard.writeText(upi.upiId);
      setCopied('done');
    } catch {
      setCopied('failed');
    }
    window.setTimeout(() => setCopied(null), 2500);
  }

  return (
    <section className="km-panel km-upi" aria-labelledby="km-upi-title">
      <h2 className="km-panel-title" id="km-upi-title">
        <CIcon name="receipt" size={22} />
        Pay with UPI
      </h2>

      <dl className="km-upi-facts">
        <div>
          <dt>Restaurant</dt>
          <dd>{restaurantName}</dd>
        </div>
        <div>
          <dt>Table</dt>
          <dd>{tableName}</dd>
        </div>
        <div className="km-upi-total">
          <dt>Total amount</dt>
          <dd>{price(amount)}</dd>
        </div>
      </dl>

      <div className="km-upi-qr">
        {imageFailed ? (
          <p role="alert">UPI payment is currently unavailable for this restaurant.</p>
        ) : (
          <img
            src={upi.qrUrl}
            alt={`UPI payment QR code for ${recipient}`}
            width={260}
            height={260}
            onError={() => setImageFailed(true)}
          />
        )}
      </div>

      <p className="km-upi-hint">
        Scan with any UPI app (Google Pay, PhonePe, Paytm, BHIM) and pay <strong>{price(amount)}</strong>. The QR does
        not fill in the amount, so please enter it yourself.
      </p>

      <dl className="km-upi-facts">
        <div>
          <dt>Recipient</dt>
          <dd>{recipient}</dd>
        </div>
        <div>
          <dt>UPI ID</dt>
          <dd className="km-upi-id">{upi.upiId}</dd>
        </div>
      </dl>

      <div className="km-upi-actions">
        <a className="km-btn-ghost" href={upi.qrUrl} download={`upi-qr-${recipient.replace(/[^A-Za-z0-9]+/g, '-')}.jpg`}>
          Download QR
        </a>
        <button type="button" className="km-btn-ghost" onClick={() => void copyId()}>
          {copied === 'done' ? 'Copied' : copied === 'failed' ? 'Copy failed' : 'Copy UPI ID'}
        </button>
        {link && (
          <a className="km-btn-ghost" href={link}>
            Open UPI app
          </a>
        )}
      </div>
    </section>
  );
}
