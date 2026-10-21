import { useEffect, useId, useRef, useState, type ReactNode } from 'react';
import { AnimatePresence, animate, motion, useReducedMotion } from 'framer-motion';

/** Closes on an outside press or Escape. */
function useDismiss(ref: React.RefObject<HTMLElement | null>, open: boolean, close: () => void) {
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent | TouchEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) close();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close();
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('touchstart', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('touchstart', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [ref, open, close]);
}

export interface PopoverButtonProps {
  open: boolean;
  toggle: () => void;
  panelId: string;
}

/**
 * A button that opens a floating glass panel. Used for every dropdown in the dashboard (restaurant, date,
 * notifications, profile, chart metric) so they behave the same: Escape and outside press close it, and the
 * trigger announces its state.
 */
export function Popover({
  button,
  children,
  align = 'right',
  className = '',
  panelClassName = '',
}: {
  button: (p: PopoverButtonProps) => ReactNode;
  children: (close: () => void) => ReactNode;
  align?: 'left' | 'right';
  className?: string;
  panelClassName?: string;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const panelId = useId();
  const reduce = useReducedMotion();
  const close = () => setOpen(false);
  useDismiss(ref, open, close);

  return (
    <div className={`kd-pop ${className}`} ref={ref}>
      {button({ open, toggle: () => setOpen((o) => !o), panelId })}
      <AnimatePresence>
        {open && (
          <motion.div
            id={panelId}
            className={`kd-pop-panel kd-pop-${align} ${panelClassName}`}
            initial={reduce ? false : { opacity: 0, y: -6, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={reduce ? { opacity: 0 } : { opacity: 0, y: -6, scale: 0.98 }}
            transition={{ duration: 0.16, ease: 'easeOut' }}
          >
            {children(close)}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

/** Counts up to `value` (or jumps there when the person prefers reduced motion). */
export function AnimatedNumber({ value, format }: { value: number; format: (n: number) => string }) {
  const reduce = useReducedMotion();
  const [shown, setShown] = useState(reduce ? value : 0);
  const from = useRef(reduce ? value : 0);
  useEffect(() => {
    if (reduce) {
      setShown(value);
      from.current = value;
      return;
    }
    const controls = animate(from.current, value, {
      duration: 0.9,
      ease: 'easeOut',
      onUpdate: (n) => setShown(n),
      onComplete: () => {
        from.current = value;
      },
    });
    return () => {
      from.current = value;
      controls.stop();
    };
  }, [value, reduce]);
  return <>{format(value === shown ? value : shown)}</>;
}

export function Skeleton({ className = '' }: { className?: string }) {
  return <div className={`kd-skeleton ${className}`} aria-hidden="true" />;
}

/** A panel's shimmering placeholder, announced once as "Loading". */
export function PanelSkeleton({ rows = 3 }: { rows?: number }) {
  return (
    <div role="status" aria-label="Loading" className="kd-skel-stack">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="kd-skel-row" />
      ))}
    </div>
  );
}

export function PanelError({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="kd-panel-error" role="alert">
      <span>{message}</span>
      {onRetry && (
        <button type="button" className="kd-btn kd-btn-sm" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function Panel({
  icon,
  title,
  subtitle,
  action,
  tone = 'red',
  className = '',
  children,
}: {
  icon: ReactNode;
  title: string;
  subtitle?: string;
  action?: ReactNode;
  tone?: 'red' | 'gold' | 'orange' | 'blue';
  className?: string;
  children: ReactNode;
}) {
  return (
    <motion.section
      className={`kd-panel ${className}`}
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      whileHover={{ y: -2 }}
      transition={{ duration: 0.35, ease: 'easeOut' }}
    >
      <header className="kd-panel-head">
        <span className={`kd-icon-chip kd-chip-${tone}`}>{icon}</span>
        <div className="kd-panel-titles">
          <h3>{title}</h3>
          {subtitle && <p>{subtitle}</p>}
        </div>
        {action}
      </header>
      {children}
    </motion.section>
  );
}
