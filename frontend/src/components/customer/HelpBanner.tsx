import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { CIcon } from './CustomerIcon';
import { useCustomerShell } from './CustomerShell';

/** A slim, always-visible way into table service: sits in the page flow, so it never covers the cart bar. */
export function HelpBanner() {
  const { openSheet } = useCustomerShell();
  const [params, setParams] = useSearchParams();

  // Arriving from the landing page's "Call a waiter" (?help=1): open the request sheet once, then tidy the address.
  useEffect(() => {
    if (params.get('help') === '1') {
      openSheet('services');
      const next = new URLSearchParams(params);
      next.delete('help');
      setParams(next, { replace: true });
    }
  }, [params, setParams, openSheet]);

  return (
    <button type="button" className="km-help-banner" onClick={() => openSheet('services')}>
      <CIcon name="bell" size={20} />
      <span>
        <strong>Need something?</strong> Call waiter, water, bill…
      </span>
      <span className="km-help-banner-cta">
        Ask
        <CIcon name="arrowRight" size={18} />
      </span>
    </button>
  );
}
