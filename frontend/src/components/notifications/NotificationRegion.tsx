import { useEffect } from 'react';
import { Link } from 'react-router-dom';
import type { ToastItem, ToastState } from '../../lib/notificationCenter';

const TONE_CLASS: Record<ToastItem['tone'], string> = {
  ok: 'alert-ok',
  info: 'alert-info',
  warn: 'alert-warn',
  error: 'alert-error',
};

function Toast({ toast, onDismiss }: { toast: ToastItem; onDismiss: (id: string) => void }) {
  const { id, stamp, ttlMs } = toast;
  // Restarts whenever the toast is refreshed by a repeat of the same event.
  useEffect(() => {
    const t = setTimeout(() => onDismiss(id), ttlMs);
    return () => clearTimeout(t);
  }, [id, stamp, ttlMs, onDismiss]);

  return (
    <div
      className={`alert ${TONE_CLASS[toast.tone]} pf-notice notif-toast`}
      role={toast.tone === 'error' ? 'alert' : undefined}
    >
      <div className="notif-text">
        <strong>
          {toast.title}
          {toast.count > 1 && <span className="notif-count"> ×{toast.count}</span>}
        </strong>
        {toast.body && <div>{toast.body}</div>}
        {toast.lines && toast.lines.length > 0 && (
          <ul>
            {toast.lines.map((line, i) => (
              <li key={i}>{line}</li>
            ))}
          </ul>
        )}
      </div>
      <div className="notif-buttons">
        {toast.action && (
          <Link className="btn btn-sm btn-primary" to={toast.action.to} onClick={() => onDismiss(id)}>
            {toast.action.label}
          </Link>
        )}
        <button type="button" className="btn btn-ghost btn-sm" aria-label="Dismiss" onClick={() => onDismiss(id)}>
          &times;
        </button>
      </div>
    </div>
  );
}

export interface BlockedBar {
  onEnable: () => void;
  onDismiss: () => void;
}

/**
 * The fixed corner where toasts stack, plus the "sounds are blocked" bar. The container never intercepts
 * clicks itself; only the toasts do. It stays mounted when empty so screen readers have a live region
 * in place before the first message arrives.
 */
export function NotificationRegion({
  toasts,
  onDismiss,
  bar,
}: {
  toasts: ToastState;
  onDismiss: (id: string) => void;
  bar: BlockedBar | null;
}) {
  return (
    <div className="notif-region">
      {bar && (
        <div className="alert alert-info pf-notice notif-bar" role="region" aria-label="Notification sounds">
          <span className="notif-text">Notification sounds are blocked by your browser.</span>
          <div className="notif-buttons">
            <button type="button" className="btn btn-sm btn-primary" data-sound-activator onClick={bar.onEnable}>
              Enable Notification Sounds
            </button>
            <button type="button" className="btn btn-ghost btn-sm" aria-label="Dismiss" onClick={bar.onDismiss}>
              &times;
            </button>
          </div>
        </div>
      )}
      <div className="notif-list" role="status" aria-live="polite" aria-label="Notifications">
        {toasts.hidden > 0 && (
          <div className="notif-more">
            +{toasts.hidden} earlier notification{toasts.hidden === 1 ? '' : 's'}
          </div>
        )}
        {toasts.items.map((t) => (
          <Toast key={t.id} toast={t} onDismiss={onDismiss} />
        ))}
      </div>
    </div>
  );
}
