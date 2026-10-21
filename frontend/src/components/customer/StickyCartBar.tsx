import { Link } from 'react-router-dom';
import { price } from '../../lib/format';
import { CIcon } from './CustomerIcon';

/** Summary pill that sits just above the bottom navigation whenever the cart has something in it. */
export function StickyCartBar({ qr, itemCount, subtotal }: { qr: string; itemCount: number; subtotal: number }) {
  if (itemCount === 0) return null;
  const items = `${itemCount} ${itemCount === 1 ? 'item' : 'items'}`;
  return (
    <div className="km-cartbar-wrap">
      <Link className="km-cartbar" to={`/menu/${qr}/cart`}>
        {/* keyed so the values re-animate when the cart changes */}
        <span className="km-cartbar-sum" key={`${itemCount}-${subtotal}`}>
          <span className="km-cartbar-count">{items}</span>
          <span className="km-cartbar-total">{price(subtotal)}</span>
        </span>
        <span className="km-cartbar-cta">
          View cart
          <CIcon name="arrowRight" size={20} />
        </span>
      </Link>
    </div>
  );
}
