import { useEffect, useRef, type ReactNode } from 'react';
import { ApiError } from '../api/client';
import { titleCase } from '../lib/format';
import { ChinarLeaf } from './customer/ChinarLeaf';

const ICONS = {
  dashboard: 'M3 13h7V3H3zM14 21h7v-9h-7zM14 3v6h7V3zM3 21h7v-5H3z',
  orders:
    'M9 5H7a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-2M9 5a2 2 0 0 1 2-2h2a2 2 0 0 1 2 2M9 5h6M9 12h6M9 16h4',
  menu: 'M4 4v7a3 3 0 0 0 3 3v7M7 4v6M10 4v7a3 3 0 0 1-3 3M18 21V4c-2 1-3.5 4-3.5 8v2H18',
  tag: 'M3 12V4h8l10 10-8 8zM7.5 8.5h.01',
  qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2v2h-2zM14 18h2v2h-2zM18 18h2v2h-2z',
  box: 'm21 8-9-5-9 5v8l9 5 9-5zM3.3 7.5 12 12.5l8.7-5M12 22V12.5',
  ticket:
    'M3 9a2 2 0 0 0 0 6v3a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-3a2 2 0 0 1 0-6V6a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1zM14 5v14',
  card: 'M3 6h18v12H3zM3 10h18M7 15h3',
  rupee: 'M7 4h10M7 8h10M15 4c0 4-3 4-8 4 4 0 8 3 8 8M7 20h4',
  flame: 'M12 3c1 3 5 5 5 10a5 5 0 0 1-10 0c0-2 1-3 2-4 0 2 1 3 2 3 0-3-1-6 1-9z',
  logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
  burger: 'M4 6h16M4 12h16M4 18h16',
  close: 'M6 6l12 12M18 6 6 18',
  bag: 'M6 7h12l1 13H5zM9 7a3 3 0 0 1 6 0',
  check: 'm5 12 5 5L20 7',
  bell: 'M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9M10.3 21a1.9 1.9 0 0 0 3.4 0',
  plate: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zM12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  star: 'M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z',
  store: 'M3 9l1.5-5h15L21 9M4 9v11h16V9M3 9a3 3 0 0 0 6 0 3 3 0 0 0 6 0 3 3 0 0 0 6 0M10 20v-6h4v6',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 12l2 2 4-4',
  sliders: 'M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0M14 4v4M8 10v4M16 16v4',
} as const;

export type IconName = keyof typeof ICONS;

export function Icon({ name }: { name: IconName }) {
  return (
    <svg className="icon" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
      <path d={ICONS[name]} />
    </svg>
  );
}

export function BrandMark() {
  return (
    <span className="brand-mark" aria-hidden="true">
      <ChinarLeaf />
    </span>
  );
}

/** Shimmering placeholder rows; announced to assistive tech as a single "Loading" status. */
export function Spinner() {
  return (
    <div className="skeleton-stack" role="status" aria-label="Loading">
      <div className="skeleton" />
      <div className="skeleton" />
      <div className="skeleton" />
    </div>
  );
}

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null;
  const fieldErrors = error instanceof ApiError ? error.fieldErrors : [];
  const message = error instanceof Error ? error.message : 'Something went wrong.';
  return (
    <div className="alert alert-error" role="alert">
      {message}
      {fieldErrors.length > 0 && (
        <ul>
          {fieldErrors.map((e, i) => (
            <li key={i}>
              {e.field}: {e.message}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

export function QueryState({
  isLoading,
  error,
  children,
}: {
  isLoading: boolean;
  error: unknown;
  children: ReactNode;
}) {
  if (isLoading) return <Spinner />;
  if (error) return <ErrorMessage error={error} />;
  return <>{children}</>;
}

export function EmptyState({ children, icon = 'plate' }: { children: ReactNode; icon?: IconName }) {
  return (
    <div className="empty">
      <span className="icon-bubble">
        <Icon name={icon} />
      </span>
      <p style={{ margin: 0 }}>{children}</p>
    </div>
  );
}

const STATUS_CLASS: Record<string, string> = {
  PENDING: 'badge-amber',
  CONFIRMED: 'badge-blue',
  PREPARING: 'badge-blue',
  READY: 'badge-green',
  SERVED: 'badge-gray',
  CANCELLED: 'badge-red',
  SUCCESS: 'badge-green',
  FAILED: 'badge-red',
  REFUNDED: 'badge-gray',
  PARTIALLY_REFUNDED: 'badge-amber',
};

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${STATUS_CLASS[status] ?? 'badge-gray'}`}>{titleCase(status)}</span>;
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) {
      dialog.showModal();
      dialog.focus(); // start focus on the dialog itself, not the close button
    }
  }, []);

  return (
    <dialog
      ref={ref}
      className="modal"
      aria-label={title}
      tabIndex={-1}
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="modal-head">
        <h3>{title}</h3>
        <button className="btn btn-ghost" onClick={onClose} aria-label="Close">
          <Icon name="close" />
        </button>
      </div>
      {children}
    </dialog>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return (
    <label className="field">
      <span>{label}</span>
      {children}
      {hint && <small className="muted">{hint}</small>}
    </label>
  );
}
