import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import type { MenuItem } from '../../api/types';
import { ApiError } from '../../api/client';
import { BrandHeader } from '../../components/customer/BrandHeader';
import { CategoryRail, type CategoryFilter } from '../../components/customer/CategoryRail';
import { ChinarLeaf } from '../../components/customer/ChinarLeaf';
import { CIcon } from '../../components/customer/CustomerIcon';
import { CustomerShell, StateCard, useCustomerShell } from '../../components/customer/CustomerShell';
import { DishCard, DishCardSkeleton } from '../../components/customer/DishCard';
import { MapsReviewsLink, ReviewCallout, Stars, usePublicReviews } from '../../components/customer/GoogleReviews';
import { DishOptionsSheet } from '../../components/customer/DishOptionsSheet';
import { HelpBanner } from '../../components/customer/HelpBanner';
import { Hero } from '../../components/customer/Hero';
import { JoinTable } from '../../components/customer/JoinTable';
import { StickyCartBar } from '../../components/customer/StickyCartBar';
import { hasOptions } from '../../lib/menuOptions';
import { lastOrderKey, rememberedOrders } from '../../lib/orderHistory';
import { useTableOrdering } from './useTableOrdering';

// Kept for anything that imported it from here before the order history moved to lib/.
export { lastOrderKey };

const SEARCH_ID = 'km-menu-search';
/** In the All view each category previews this many dishes, with "See all" for the rest. */
const PREVIEW_COUNT = 4;

function smoothScrollTo(el: Element | null, block: ScrollLogicalPosition = 'start') {
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  el?.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block });
}

export default function MenuPage() {
  const { qr = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const t = useTableOrdering(qr);

  const [search, setSearch] = useState('');
  const [vegOnly, setVegOnly] = useState(false);
  const [category, setCategory] = useState<CategoryFilter>('all');
  const [announcement, setAnnouncement] = useState('');
  const listRef = useRef<HTMLDivElement>(null);

  const focusSearch = useCallback(() => {
    const input = document.getElementById(SEARCH_ID) as HTMLInputElement | null;
    if (!input) return;
    smoothScrollTo(input, 'center');
    input.focus({ preventScroll: true });
  }, []);

  // Arriving from the Search tab on another screen (?search=1).
  useEffect(() => {
    if (params.get('search') === '1' && t.table.isSuccess) {
      focusSearch();
      const next = new URLSearchParams(params);
      next.delete('search');
      setParams(next, { replace: true });
    }
  }, [params, setParams, t.table.isSuccess, focusSearch]);

  const categories = useMemo(() => (t.menu.data ?? []).map((c) => ({ id: c.id, name: c.name })), [t.menu.data]);

  const sections = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (t.menu.data ?? [])
      .filter((c) => category === 'all' || c.id === category)
      .map((c) => ({
        ...c,
        items: c.items.filter(
          (i) =>
            (!vegOnly || i.veg) &&
            (!term || i.name.toLowerCase().includes(term) || (i.description ?? '').toLowerCase().includes(term)),
        ),
      }))
      .filter((c) => c.items.length > 0);
  }, [t.menu.data, search, vegOnly, category]);

  const chooseCategory = useCallback((next: CategoryFilter) => {
    setCategory(next);
    // If the diner has scrolled past the top of the list, bring the first dish back into view.
    const top = listRef.current?.getBoundingClientRect().top ?? 0;
    if (top < 0) smoothScrollTo(listRef.current);
  }, []);

  const addMutate = t.add.mutate;
  const changeQuantity = t.changeQuantity;
  // A dish with options is configured in a sheet first; one without is added straight away, as before.
  const [picking, setPicking] = useState<MenuItem | null>(null);
  const onAdd = useCallback(
    (item: MenuItem) => {
      if (hasOptions(item)) {
        setPicking(item);
        return;
      }
      addMutate(
        { id: item.id, quantity: 1 },
        { onSuccess: () => setAnnouncement(`${item.name} added to your order`) },
      );
    },
    [addMutate],
  );

  // Clear the toast a moment after it appears.
  useEffect(() => {
    if (!announcement) return;
    const id = window.setTimeout(() => setAnnouncement(''), 2400);
    return () => window.clearTimeout(id);
  }, [announcement]);

  if (t.table.isLoading) return <MenuSkeleton />;
  if (t.table.error) {
    const notFound = t.table.error instanceof ApiError && t.table.error.status === 404;
    return (
      <StateCard
        title={notFound ? 'This QR code is not active' : 'The menu could not open'}
        action={
          notFound ? undefined : (
            <button type="button" className="km-btn-fire" onClick={() => t.table.refetch()}>
              Try again
            </button>
          )
        }
      >
        {notFound
          ? 'The table may have been removed or its code replaced. Please ask a member of staff for help.'
          : (t.table.error as Error).message}
      </StateCard>
    );
  }

  // The cart is the guest's own, so a name comes first. Nothing here needs the cart until it exists.
  if (t.diner.isPending) return <MenuSkeleton />;
  if (t.diner.error) {
    return (
      <StateCard
        title="Your table could not open"
        action={
          <button type="button" className="km-btn-fire" onClick={() => t.diner.refetch()}>
            Try again
          </button>
        }
      >
        {(t.diner.error as Error).message}
      </StateCard>
    );
  }
  if (t.diner.data === null) {
    return (
      <JoinTable
        restaurantName={t.table.data?.restaurantName}
        tableName={t.table.data?.tableName}
        busy={t.join.isPending}
        error={t.join.error ? (t.join.error as Error).message : null}
        onJoin={(name) => t.join.mutate(name)}
      />
    );
  }

  const info = t.table.data!;
  const latestOrder = rememberedOrders(qr).find((o) => Boolean(o.token));
  const term = search.trim();
  const activeCategoryName = category === 'all' ? null : categories.find((c) => c.id === category)?.name;

  return (
    <CustomerShell
      qr={qr}
      active="menu"
      itemCount={t.itemCount}
      restaurantName={info.restaurantName}
      tableName={info.tableName}
      reviewUrl={info.googleReviewUrl}
      mapsUrl={info.googleMapsUrl}
      onSearch={focusSearch}
      moreExtras={<VegToggle checked={vegOnly} onChange={setVegOnly} block />}
    >
      <Hero
        header={<MenuHeader onSearch={focusSearch} />}
        footer={
          <p className="km-hero-table">
            {info.logo ? <img src={info.logo} alt="" width={22} height={22} /> : <CIcon name="pin" size={16} />}
            <span>{info.restaurantName}</span>
            <span className="km-hero-table-pill">
              <CIcon name="table" size={14} />
              {info.tableName}
            </span>
          </p>
        }
        afterCopy={
          info.googleReviewUrl && (
            <div className="km-hero-reviews">
              <RatingPill qr={qr} mapsUrl={info.googleMapsUrl} />
              <a className="km-review-pill" href={info.googleReviewUrl} target="_blank" rel="noopener noreferrer">
                <CIcon name="star" size={15} />
                Write a review
                <span className="sr-only"> on Google (opens in a new tab)</span>
              </a>
            </div>
          )
        }
      />

      <main className="km-main" id="menu">
        <HelpBanner />

        {latestOrder && (
          <Link className="km-track-banner" to={`/order/${latestOrder.token}?t=${qr}`}>
            <CIcon name="clock" size={20} />
            <span>
              Your latest order <strong>{latestOrder.orderNumber}</strong>
            </span>
            <span className="km-track-banner-cta">
              Track
              <CIcon name="arrowRight" size={18} />
            </span>
          </Link>
        )}

        <div className="km-search-row">
          <label className="km-search">
            <CIcon name="search" />
            <span className="sr-only">Search dishes</span>
            <input
              id={SEARCH_ID}
              type="search"
              placeholder="Search for dishes…"
              autoComplete="off"
              enterKeyHint="search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            {search && (
              <button type="button" className="km-search-clear" onClick={() => setSearch('')} aria-label="Clear search">
                <CIcon name="close" size={18} />
              </button>
            )}
          </label>
          <VegToggle checked={vegOnly} onChange={setVegOnly} />
        </div>

        {categories.length > 1 && <CategoryRail categories={categories} active={category} onChange={chooseCategory} />}

        {t.cartError != null && (
          <div className="km-alert" role="alert">
            {(t.cartError as Error).message}
          </div>
        )}

        <div className="km-list" ref={listRef}>
          {t.menu.isLoading && (
            <div className="km-grid" role="status" aria-label="Loading the menu">
              {Array.from({ length: 4 }, (_, i) => (
                <DishCardSkeleton key={i} />
              ))}
            </div>
          )}

          {t.menu.error && (
            <div className="km-alert" role="alert">
              <span>The menu could not load. {(t.menu.error as Error).message}</span>
              <button type="button" className="km-btn-ghost" onClick={() => t.menu.refetch()}>
                Retry
              </button>
            </div>
          )}

          {t.menu.data && t.menu.data.length === 0 && (
            <div className="km-empty">
              <ChinarLeaf size={44} />
              <p>The kitchen has not published any dishes yet. Please ask a member of staff.</p>
            </div>
          )}

          {t.menu.data && t.menu.data.length > 0 && sections.length === 0 && (
            <div className="km-empty">
              <CIcon name="search" size={30} />
              <p>
                {term ? <>No dishes match “{term}”</> : 'No dishes here'}
                {activeCategoryName ? ` in ${activeCategoryName}` : ''}
                {vegOnly ? ' among vegetarian dishes' : ''}.
              </p>
              <div className="km-empty-actions">
                {category !== 'all' && (
                  <button type="button" className="km-btn-ghost" onClick={() => setCategory('all')}>
                    Search all categories
                  </button>
                )}
                {vegOnly && (
                  <button type="button" className="km-btn-ghost" onClick={() => setVegOnly(false)}>
                    Show non-veg too
                  </button>
                )}
                {term && (
                  <button type="button" className="km-btn-ghost" onClick={() => setSearch('')}>
                    Clear search
                  </button>
                )}
              </div>
            </div>
          )}

          {sections.map((section, sectionIndex) => {
            const preview = category === 'all' && !term;
            const shown = preview ? section.items.slice(0, PREVIEW_COUNT) : section.items;
            const hidden = section.items.length - shown.length;
            return (
            <section className="km-section" key={section.id} aria-labelledby={`cat-${section.id}`}>
              <div className="km-section-head">
                <h2 id={`cat-${section.id}`}>
                  <ChinarLeaf size={22} tone="orange" veins={false} />
                  {section.name}
                </h2>
                {hidden > 0 && (
                  <button
                    type="button"
                    className="km-see-all"
                    onClick={() => chooseCategory(section.id)}
                    aria-label={`See all ${section.items.length} dishes in ${section.name}`}
                  >
                    See all {section.items.length}
                    <CIcon name="arrowRight" size={16} />
                  </button>
                )}
              </div>
              {section.description && <p className="km-section-desc">{section.description}</p>}
              <div className="km-grid">
                {shown.map((item, idx) => {
                  const line = t.cartByMenuItem.get(item.id);
                  return (
                    <DishCard
                      key={item.id}
                      item={item}
                      index={idx}
                      quantity={line?.quantity ?? 0}
                      cartItemId={line?.cartItemId ?? null}
                      disabled={t.busy}
                      working={t.pendingMenuItemId === item.id}
                      eagerImage={sectionIndex === 0 && idx < 2}
                      onAdd={onAdd}
                      onChangeQuantity={changeQuantity}
                    />
                  );
                })}
              </div>
            </section>
            );
          })}
        </div>
        {info.googleReviewUrl && t.menu.data && t.menu.data.length > 0 && (
          <ReviewSection
            qr={qr}
            restaurantName={info.restaurantName}
            reviewUrl={info.googleReviewUrl}
            mapsUrl={info.googleMapsUrl}
          />
        )}
      </main>

      {picking && (
        <DishOptionsSheet
          // Keyed by dish so reopening for another starts from its own defaults, never the last one's.
          key={picking.id}
          item={t.menuItemsById.get(picking.id) ?? picking}
          working={t.add.isPending}
          error={t.add.error ? (t.add.error as Error).message : null}
          onClose={() => {
            t.add.reset();
            setPicking(null);
          }}
          onConfirm={(optionIds, quantity) =>
            addMutate(
              { id: picking.id, quantity, optionIds },
              {
                onSuccess: () => {
                  setAnnouncement(`${picking.name} added to your order`);
                  setPicking(null);
                },
              },
            )
          }
        />
      )}

      <StickyCartBar qr={qr} itemCount={t.itemCount} subtotal={t.cart.data?.subtotal ?? 0} />

      <div className="km-toast-region" role="status" aria-live="polite">
        {announcement && (
          <div className="km-toast" key={announcement}>
            <CIcon name="check" size={18} />
            {announcement}
          </div>
        )}
      </div>
    </CustomerShell>
  );
}

/**
 * Google rating in the hero, opening the reviews sheet. When the server has no Places API
 * key there is no rating to show, so it becomes a plain link to the reviews on Google Maps.
 */
function RatingPill({ qr, mapsUrl }: { qr: string; mapsUrl: string | null }) {
  const { openSheet } = useCustomerShell();
  const reviews = usePublicReviews(qr, true);
  const rating = reviews.data?.rating;
  if (reviews.isLoading) return null;
  if (rating == null) {
    return mapsUrl ? (
      <MapsReviewsLink href={mapsUrl} className="km-rating-pill">
        <CIcon name="star" size={14} />
        <strong>Google reviews</strong>
      </MapsReviewsLink>
    ) : null;
  }
  return (
    <button type="button" className="km-rating-pill" onClick={() => openSheet('reviews')}>
      <Stars value={rating} size={13} />
      <strong>{rating.toFixed(1)}</strong>
      <span>({(reviews.data?.ratingCount ?? 0).toLocaleString('en-IN')} on Google)</span>
    </button>
  );
}

function ReviewSection(props: { qr: string; restaurantName: string; reviewUrl: string; mapsUrl: string | null }) {
  const { openSheet } = useCustomerShell();
  return (
    <div className="km-review-section">
      <ReviewCallout {...props} onOpenSheet={() => openSheet('reviews')} />
    </div>
  );
}

function MenuHeader({ onSearch }: { onSearch: () => void }) {
  const { openSheet } = useCustomerShell();
  return <BrandHeader onOpenMore={() => openSheet('more')} onSearch={onSearch} />;
}

function VegToggle({
  checked,
  onChange,
  block = false,
}: {
  checked: boolean;
  onChange: (v: boolean) => void;
  block?: boolean;
}) {
  return (
    <button
      type="button"
      className={`km-veg-toggle${block ? ' km-veg-toggle-block' : ''}`}
      aria-pressed={checked}
      onClick={() => onChange(!checked)}
    >
      <span className="km-veg is-veg" aria-hidden="true">
        <svg viewBox="0 0 16 16">
          <rect x="1" y="1" width="14" height="14" rx="2.5" />
          <circle cx="8" cy="8" r="3.6" />
        </svg>
      </span>
      {block ? 'Show vegetarian dishes only' : 'Veg only'}
    </button>
  );
}

function MenuSkeleton() {
  return (
    <div className="km">
      <div className="km-hero km-hero-skeleton km-shimmer" />
      <main className="km-main" role="status" aria-label="Loading the menu">
        <div className="km-search km-shimmer" style={{ border: 0 }} />
        <div className="km-grid" style={{ marginTop: '1.5rem' }}>
          {Array.from({ length: 3 }, (_, i) => (
            <DishCardSkeleton key={i} />
          ))}
        </div>
      </main>
    </div>
  );
}
