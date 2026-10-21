import { useQuery } from '@tanstack/react-query';
import { orderApi } from '../../api';
import { historyText } from '../../lib/cancelReasons';
import { timeOfDay } from '../../lib/format';
import { Modal, QueryState } from '../ui';

/**
 * Everything recorded about an order's progress: when, by whom, and why if it was cancelled. Orders
 * placed before history was recorded have nothing here, and the screen says so rather than guessing.
 */
export function OrderHistoryModal({
  orderId,
  orderNumber,
  onClose,
}: {
  orderId: number;
  orderNumber: string;
  onClose: () => void;
}) {
  const history = useQuery({ queryKey: ['order-history', orderId], queryFn: () => orderApi.history(orderId) });

  return (
    <Modal title={`History of order ${orderNumber}`} onClose={onClose}>
      <QueryState isLoading={history.isLoading} error={history.error}>
        {(history.data ?? []).length === 0 ? (
          <p className="muted">No history was recorded for this order. Orders placed before this feature have none.</p>
        ) : (
          <ol className="order-history">
            {history.data!.map((h, i) => (
              <li key={i}>
                <span className="order-history-time">{timeOfDay(h.at)}</span>
                <span>
                  <strong>{historyText(h)}</strong>
                  <small className="muted"> by {h.actor}</small>
                  {h.note && <small className="order-history-note">Note: {h.note}</small>}
                </span>
              </li>
            ))}
          </ol>
        )}
      </QueryState>
      <div className="actions">
        <button className="btn" onClick={onClose}>
          Close
        </button>
      </div>
    </Modal>
  );
}
