import type { MouseEvent } from 'react';
import { Link } from 'react-router-dom';
import { CIcon, type CustomerIconName } from './CustomerIcon';

export type NavTab = 'menu' | 'search' | 'cart' | 'orders' | 'more';

function Tab({
  icon,
  label,
  active,
  onClick,
  to,
}: {
  icon: CustomerIconName;
  label: string;
  active: boolean;
  onClick?: () => void;
  to?: string;
}) {
  // Tapping the tab for the screen you are already on takes you back to its top.
  const scrollTopIfActive = (e: MouseEvent) => {
    if (!active) return;
    e.preventDefault();
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' });
  };
  const content = (
    <>
      <CIcon name={icon} />
      <span>{label}</span>
    </>
  );
  return to ? (
    <Link className="km-tab" to={to} aria-current={active ? 'page' : undefined} onClick={scrollTopIfActive}>
      {content}
    </Link>
  ) : (
    <button type="button" className="km-tab" onClick={onClick} aria-pressed={active}>
      {content}
    </button>
  );
}

export function BottomNav({
  qr,
  active,
  itemCount,
  onSearch,
  onOrders,
  onMore,
}: {
  qr: string;
  active: NavTab;
  itemCount: number;
  onSearch: () => void;
  onOrders: () => void;
  onMore: () => void;
}) {
  return (
    <nav className="km-bottomnav" aria-label="Table navigation">
      <Tab icon="home" label="Menu" active={active === 'menu'} to={`/menu/${qr}`} />
      <Tab icon="search" label="Search" active={active === 'search'} onClick={onSearch} />
      <Link
        className="km-tab-cart"
        to={`/menu/${qr}/cart`}
        aria-current={active === 'cart' ? 'page' : undefined}
        aria-label={itemCount > 0 ? `Your order, ${itemCount} ${itemCount === 1 ? 'item' : 'items'}` : 'Your order, empty'}
      >
        <CIcon name="cart" size={26} />
        {itemCount > 0 && (
          <span className="km-tab-badge" key={itemCount} aria-hidden="true">
            {itemCount > 99 ? '99+' : itemCount}
          </span>
        )}
      </Link>
      <Tab icon="receipt" label="Orders" active={active === 'orders'} onClick={onOrders} />
      <Tab icon="more" label="More" active={active === 'more'} onClick={onMore} />
    </nav>
  );
}
