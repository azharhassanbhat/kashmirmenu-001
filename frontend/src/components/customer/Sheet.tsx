import { useEffect, useRef, type ReactNode } from 'react';
import { CIcon } from './CustomerIcon';

/** Bottom sheet on phones, centred panel on wider screens. Native <dialog> gives focus trapping and Esc. */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog && !dialog.open) {
      dialog.showModal();
      dialog.focus();
    }
  }, []);

  return (
    <dialog
      ref={ref}
      className="km-sheet"
      tabIndex={-1}
      aria-labelledby="km-sheet-title"
      onClose={onClose}
      onClick={(e) => {
        if (e.target === ref.current) onClose();
      }}
    >
      <div className="km-sheet-inner">
        <div className="km-sheet-head">
          <h2 id="km-sheet-title">{title}</h2>
          <button type="button" className="km-icon-btn" onClick={onClose} aria-label="Close">
            <CIcon name="close" />
          </button>
        </div>
        {children}
      </div>
    </dialog>
  );
}
