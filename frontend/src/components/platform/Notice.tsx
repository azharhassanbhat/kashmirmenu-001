import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

type NoticeKind = 'ok' | 'error';
interface NoticeItem {
  id: number;
  kind: NoticeKind;
  text: string;
}

interface NoticeApi {
  success: (text: string) => void;
  failure: (text: string) => void;
}

const NoticeContext = createContext<NoticeApi | null>(null);

const SHOW_MS = 6000;

/**
 * The smallest accessible notification: a polite live region in the corner. Errors use role="alert"
 * so they are announced at once; successes use role="status". The app had no toast, so this is it.
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<NoticeItem[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, number>());

  const dismiss = useCallback((id: number) => {
    setItems((list) => list.filter((n) => n.id !== id));
    const t = timers.current.get(id);
    if (t !== undefined) window.clearTimeout(t);
    timers.current.delete(id);
  }, []);

  const push = useCallback(
    (kind: NoticeKind, text: string) => {
      const id = nextId.current++;
      setItems((list) => [...list.slice(-3), { id, kind, text }]);
      timers.current.set(
        id,
        window.setTimeout(() => dismiss(id), SHOW_MS),
      );
    },
    [dismiss],
  );

  useEffect(() => {
    const live = timers.current;
    return () => {
      live.forEach((t) => window.clearTimeout(t));
      live.clear();
    };
  }, []);

  const api = useMemo<NoticeApi>(
    () => ({ success: (t) => push('ok', t), failure: (t) => push('error', t) }),
    [push],
  );

  return (
    <NoticeContext.Provider value={api}>
      {children}
      <div className="pf-notices">
        {items.map((n) => (
          <div
            key={n.id}
            className={`alert ${n.kind === 'ok' ? 'alert-ok' : 'alert-error'} pf-notice`}
            role={n.kind === 'ok' ? 'status' : 'alert'}
          >
            <span>{n.text}</span>
            <button type="button" className="btn btn-ghost btn-sm" aria-label="Dismiss" onClick={() => dismiss(n.id)}>
              &times;
            </button>
          </div>
        ))}
      </div>
    </NoticeContext.Provider>
  );
}

export function useNotice(): NoticeApi {
  const ctx = useContext(NoticeContext);
  if (!ctx) throw new Error('useNotice must be used inside NoticeProvider');
  return ctx;
}
