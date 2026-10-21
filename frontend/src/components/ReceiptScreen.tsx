import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import type { Receipt } from '../api/types';
import { ReceiptView } from './ReceiptView';

/**
 * The receipt with its toolbar: choose the paper (A4, or an 80 mm till roll) and print. The toolbar is hidden
 * when printing, and so is everything else on the page, so only the receipt comes out of the printer.
 */
export function ReceiptScreen({ receipt, backTo, backLabel }: { receipt: Receipt; backTo: string; backLabel: string }) {
  const [narrow, setNarrow] = useState(false);

  // Print rules that show ONLY the receipt apply while this class is on the page, and nowhere else.
  useEffect(() => {
    document.body.classList.add('print-receipt');
    return () => document.body.classList.remove('print-receipt');
  }, []);

  // The printed page size can only be set by CSS, so a 80 mm roll gets its own @page rule while selected.
  useEffect(() => {
    if (!narrow) return;
    const style = document.createElement('style');
    style.textContent = '@page { size: 80mm auto; margin: 3mm; }';
    document.head.appendChild(style);
    return () => style.remove();
  }, [narrow]);

  return (
    <div className="receipt-screen">
      <div className="receipt-toolbar no-print">
        <Link className="btn" to={backTo}>
          {backLabel}
        </Link>
        <span className="actions">
          <label className="receipt-paper">
            <span className="muted">Paper</span>
            <select value={narrow ? '80' : 'a4'} onChange={(e) => setNarrow(e.target.value === '80')}>
              <option value="a4">A4 / letter</option>
              <option value="80">80 mm till roll</option>
            </select>
          </label>
          <button type="button" className="btn btn-primary" onClick={() => window.print()}>
            Print receipt
          </button>
        </span>
      </div>
      <ReceiptView receipt={receipt} narrow={narrow} />
    </div>
  );
}
