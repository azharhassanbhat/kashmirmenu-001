import { useState, type FormEvent } from 'react';
import { ErrorMessage, Modal } from '../ui';
import { CANCEL_REASONS, MAX_NOTE, reasonProblem, type CancelReasonCode } from '../../lib/cancelReasons';

/**
 * Cancelling an order always asks why. The reason is shown to the guest (as its label, never the note)
 * and kept in the order's history. The server accepts a cancellation without a reason for older callers,
 * but this screen does not: a reason is what lets a restaurant see, later, why orders are lost.
 */
export function CancelOrderDialog({
  orderNumber,
  busy,
  error,
  onClose,
  onConfirm,
}: {
  orderNumber: string;
  busy: boolean;
  error: unknown;
  onClose: () => void;
  onConfirm: (reason: CancelReasonCode, note: string) => void;
}) {
  const [reason, setReason] = useState<CancelReasonCode | null>(null);
  const [note, setNote] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  function submit(e: FormEvent) {
    e.preventDefault();
    const p = reasonProblem(reason, note);
    setProblem(p);
    if (!p && reason) onConfirm(reason, note.trim());
  }

  return (
    <Modal title={`Cancel order ${orderNumber}?`} onClose={onClose}>
      <form onSubmit={submit}>
        <p className="muted">
          The guest will see the reason you choose. Dishes go back to stock, and a coupon use is given back.
        </p>
        <fieldset className="field" style={{ border: 0, padding: 0 }}>
          <legend>Why is it being cancelled?</legend>
          {CANCEL_REASONS.map((r) => (
            <label className="check" key={r.code}>
              <input
                type="radio"
                name="cancel-reason"
                checked={reason === r.code}
                onChange={() => {
                  setReason(r.code);
                  setProblem(null);
                }}
              />{' '}
              {r.label}
            </label>
          ))}
        </fieldset>
        <label className="field">
          <span>
            Note {reason === 'OTHER' ? '(required)' : '(optional, for your records; the guest does not see it)'}
          </span>
          <textarea rows={2} maxLength={MAX_NOTE} value={note} onChange={(e) => setNote(e.target.value)} />
        </label>
        {problem && (
          <div className="alert alert-error" role="alert">
            {problem}
          </div>
        )}
        <ErrorMessage error={error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Keep the order
          </button>
          <button className="btn btn-danger" disabled={busy}>
            {busy ? 'Cancelling…' : 'Cancel the order'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
