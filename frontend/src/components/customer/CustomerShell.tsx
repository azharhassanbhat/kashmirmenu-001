import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { rememberedOrders } from '../../lib/orderHistory';
import { BottomNav, type NavTab } from './BottomNav';
import { ChinarLeaf } from './ChinarLeaf';
import { MapsReviewsLink, ReviewsPanel, usePublicReviews, WriteReviewButton } from './GoogleReviews';
import { CIcon } from './CustomerIcon';
import { ServiceRequestSheet } from './ServiceRequests';
import { Sheet } from './Sheet';
import '../../customer.css';

type SheetName = 'orders' | 'more' | 'reviews' | 'services' | null;

interface ShellApi {
  openSheet: (sheet: Exclude<SheetName, null>) => void;
}

const ShellContext = createContext<ShellApi>({ openSheet: () => {} });
export const useCustomerShell = () => useContext(ShellContext);

/**
 * Frame shared by every diner screen: Chinar theme root, bottom navigation and the Orders / More
 * sheets. Pages render their own content as children.
 */
export function CustomerShell({
  qr,
  active,
  itemCount = 0,
  restaurantName,
  tableName,
  reviewUrl,
  mapsUrl,
  onSearch,
  moreExtras,
  children,
  withNav = true,
}: {
  qr: string;
  active: NavTab;
  itemCount?: number;
  restaurantName?: string;
  tableName?: string;
  /** Google "write a review" link for the restaurant, when it has one. */
  reviewUrl?: string | null;
  /** Google Maps page with the restaurant's reviews. */
  mapsUrl?: string | null;
  /** Focus the search field; when absent the Search tab returns to the menu with search focused. */
  onSearch?: () => void;
  /** Page-specific controls for the More sheet (e.g. the veg-only filter on the menu). */
  moreExtras?: ReactNode;
  children: ReactNode;
  withNav?: boolean;
}) {
  const navigate = useNavigate();
  const [sheet, setSheet] = useState<SheetName>(null);
  const openSheet = useCallback((s: Exclude<SheetName, null>) => setSheet(s), []);
  const api = useMemo(() => ({ openSheet }), [openSheet]);

  // Match the browser chrome to the theme while a diner screen is shown.
  useEffect(() => {
    const meta = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
    const previous = meta?.content;
    meta?.setAttribute('content', '#10080B');
    return () => {
      if (meta && previous) meta.setAttribute('content', previous);
    };
  }, []);

  const search = onSearch ?? (() => navigate(`/menu/${qr}?search=1`));

  return (
    <ShellContext.Provider value={api}>
      <div className={`km${withNav ? ' km-has-nav' : ''}`}>
        {children}
        {withNav && (
          <BottomNav
            qr={qr}
            active={sheet === 'reviews' ? 'more' : sheet === 'services' ? active : (sheet ?? active)}
            itemCount={itemCount}
            onSearch={search}
            onOrders={() => setSheet('orders')}
            onMore={() => setSheet('more')}
          />
        )}
        {sheet === 'orders' && <OrdersSheet qr={qr} onClose={() => setSheet(null)} />}
        {sheet === 'services' && <ServiceRequestSheet qr={qr} onClose={() => setSheet(null)} />}
        {sheet === 'reviews' && (
          <ReviewsSheet qr={qr} title={restaurantName ? `${restaurantName} on Google` : 'Google reviews'} onClose={() => setSheet(null)} />
        )}
        {sheet === 'more' && (
          <Sheet title="Your table" onClose={() => setSheet(null)}>
            {(restaurantName || tableName) && (
              <div className="km-table-card">
                <ChinarLeaf size={36} />
                <div>
                  {restaurantName && <strong>{restaurantName}</strong>}
                  {tableName && (
                    <span>
                      <CIcon name="table" size={16} /> {tableName}
                    </span>
                  )}
                </div>
              </div>
            )}
            <button type="button" className="km-btn-fire km-btn-block km-more-service" onClick={() => setSheet('services')}>
              <CIcon name="bell" size={20} />
              Need something? Call waiter, water, bill
            </button>
            {moreExtras}
            {reviewUrl && <MoreReviews qr={qr} reviewUrl={reviewUrl} mapsUrl={mapsUrl ?? null} onOpenSheet={() => setSheet('reviews')} />}
            <p className="km-sheet-note">
              Your cart is yours alone: everyone at {tableName ?? 'this table'} has their own. You can pay for
              just your own bill or for the whole table, and staff confirm payment at the table.
            </p>
            {active !== 'menu' && (
              <Link className="km-btn-ghost km-btn-block" to={`/menu/${qr}`} onClick={() => setSheet(null)}>
                Back to the menu
              </Link>
            )}
          </Sheet>
        )}
      </div>
    </ShellContext.Provider>
  );
}

/**
 * With a rating available (server has a Places API key) "read reviews" opens the in-app
 * sheet; without one it simply opens the reviews on Google Maps.
 */
function MoreReviews({
  qr,
  reviewUrl,
  mapsUrl,
  onOpenSheet,
}: {
  qr: string;
  reviewUrl: string;
  mapsUrl: string | null;
  onOpenSheet: () => void;
}) {
  const reviews = usePublicReviews(qr, true);
  const hasRating = reviews.data?.rating != null;
  return (
    <div className="km-more-review">
      <WriteReviewButton href={reviewUrl} block />
      {hasRating ? (
        <button type="button" className="km-link-btn" onClick={onOpenSheet}>
          Read what other guests say
        </button>
      ) : (
        mapsUrl && (
          <MapsReviewsLink href={mapsUrl} className="km-link-btn km-link-center">
            Read what other guests say on Google Maps
          </MapsReviewsLink>
        )
      )}
    </div>
  );
}

function ReviewsSheet({ qr, title, onClose }: { qr: string; title: string; onClose: () => void }) {
  const reviews = usePublicReviews(qr, true);
  return (
    <Sheet title={title} onClose={onClose}>
      <ReviewsPanel data={reviews.data} loading={reviews.isLoading} />
    </Sheet>
  );
}

function OrdersSheet({ qr, onClose }: { qr: string; onClose: () => void }) {
  // Orders remembered before tracking moved to tokens have no key to look them up with, so they
  // are dropped rather than linked to a dead URL.
  const orders = rememberedOrders(qr).filter((o) => Boolean(o.token));
  return (
    <Sheet title="Your orders" onClose={onClose}>
      {orders.length === 0 ? (
        <div className="km-empty km-empty-tight">
          <CIcon name="receipt" size={30} />
          <p>Orders you place from this table on this phone will appear here, so you can follow them live.</p>
        </div>
      ) : (
        <ul className="km-order-list">
          {orders.map((o) => (
            <li key={o.orderNumber}>
              <Link to={`/order/${o.token}?t=${qr}`} onClick={onClose}>
                <span>
                  <strong>Order {o.orderNumber}</strong>
                  {o.placedAt && (
                    <small>
                      Placed{' '}
                      {new Date(o.placedAt).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })}
                    </small>
                  )}
                </span>
                <span className="km-order-list-cta">
                  Track
                  <CIcon name="arrowRight" size={18} />
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <Link className="km-btn-fire km-btn-block km-sheet-pay" to={`/menu/${qr}/pay`} onClick={onClose}>
        Pay my bill
      </Link>
    </Sheet>
  );
}

/** Full-screen message for invalid QR codes and hard failures. */
export function StateCard({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <div className="km">
      <main className="km-state">
        <ChinarLeaf size={64} />
        <h1>{title}</h1>
        <p>{children}</p>
        {action}
      </main>
    </div>
  );
}
