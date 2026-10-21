import { useState, type CSSProperties, type ReactNode } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { landingApi } from '../../api';
import { ApiError } from '../../api/client';
import type { LandingActionKey, PublicLanding } from '../../api/types';
import { ChinarLeaf } from '../../components/customer/ChinarLeaf';
import { CardArt, SocialIcon } from '../../components/customer/LandingArt';
import { CIcon, type CustomerIconName } from '../../components/customer/CustomerIcon';
import { StateCard } from '../../components/customer/CustomerShell';
import { DalLakeScene } from '../../components/customer/DalLakeScene';
import { RewardsSheet, WifiSheet } from '../../components/customer/LandingSheets';
import { ACTION_TEXT, isSafeExternal, isSafeImage, knownActions, markLanded, safeColor, telHref } from '../../lib/landing';
import '../../landing.css';

const ICONS: Record<LandingActionKey, { icon: CustomerIconName; tone: string }> = {
  REWARDS: { icon: 'heart', tone: 'crimson' },
  REVIEW: { icon: 'star', tone: 'saffron' },
  MENU: { icon: 'cloche', tone: 'orange' },
  WIFI: { icon: 'wifi', tone: 'teal' },
  ASSISTANCE: { icon: 'bell', tone: 'rose' },
};

type OpenSheet = 'REWARDS' | 'WIFI' | null;

function Card({ action, index, children }: { action: LandingActionKey; index: number; children: ReactNode }) {
  const { tone } = ICONS[action];
  return (
    <li className={`km-l-card tone-${tone}`} style={{ '--i': index } as CSSProperties}>
      {children}
    </li>
  );
}

function CardBody({ action }: { action: LandingActionKey }) {
  const { icon } = ICONS[action];
  const text = ACTION_TEXT[action];
  return (
    <>
      <CardArt action={action} />
      <span className="km-l-ico">
        <CIcon name={icon} size={22} />
      </span>
      <span className="km-l-txt">
        <strong>{text.title}</strong>
        <small>{text.hint}</small>
      </span>
      <CIcon name="arrowRight" size={18} className="km-l-go" />
    </>
  );
}

function Landing({ qr, data }: { qr: string; data: PublicLanding }) {
  const [sheet, setSheet] = useState<OpenSheet>(null);
  const accent = safeColor(data.primaryColor);
  const cover = isSafeImage(data.coverImageUrl) ? data.coverImageUrl : null;
  const logo = isSafeImage(data.logo) ? data.logo : null;
  const l = data.links;
  const review = isSafeExternal(l.googleReviewUrl) ? l.googleReviewUrl : null;
  const actions = knownActions(data.actions);
  const tel = telHref(l.phoneDial);
  const social = [
    { label: 'Instagram', url: l.instagramUrl },
    { label: 'YouTube', url: l.youtubeUrl },
    { label: 'Facebook', url: l.facebookUrl },
  ].filter((s) => isSafeExternal(s.url));

  const style = accent ? ({ '--km-accent': accent } as CSSProperties) : undefined;
  const menuPath = `/menu/${qr}?view=menu`;

  return (
    <div className="km km-landing" style={style}>
      <div className="km-l-shell">
        <header className="km-l-hero">
          <div className="km-l-cover" aria-hidden="true">
            {cover ? <img src={cover} alt="" fetchPriority="high" /> : <DalLakeScene />}
          </div>
          <div className="km-l-brand">
            <div className="km-l-id">
              <div className="km-l-logo">
                {logo ? <img src={logo} alt="" width={84} height={84} /> : <span aria-hidden="true">{data.restaurantName.charAt(0)}</span>}
              </div>
              <div>
                <h1>{data.restaurantName}</h1>
                {data.tagline && <p className="km-l-tagline">{data.tagline}</p>}
                <p className="km-l-table">
                  <CIcon name="table" size={14} /> {data.tableName}
                </p>
              </div>
              <p className="km-l-powered">
                <ChinarLeaf tone="current" size={14} className="km-l-powered-leaf" />
                Powered by Kashmir Menu
              </p>
            </div>
          </div>
        </header>

        <main className="km-l-main">
          {data.announcement && (
            <p className="km-l-announce" role="note">
              <CIcon name="gift" size={18} />
              <span>{data.announcement}</span>
            </p>
          )}

          <ul className="km-l-cards" aria-label="What would you like to do?">
            {actions.map((action, i) => {
              switch (action) {
                case 'MENU':
                  return (
                    <Card key={action} action={action} index={i}>
                      <Link to={menuPath} onClick={() => markLanded(qr)}>
                        <CardBody action={action} />
                      </Link>
                    </Card>
                  );
                case 'ASSISTANCE':
                  return (
                    <Card key={action} action={action} index={i}>
                      <Link to={`/menu/${qr}?view=menu&help=1`} onClick={() => markLanded(qr)}>
                        <CardBody action={action} />
                      </Link>
                    </Card>
                  );
                case 'REVIEW':
                  return review ? (
                    <Card key={action} action={action} index={i}>
                      <a href={review} target="_blank" rel="noopener noreferrer">
                        <CardBody action={action} />
                        <span className="sr-only"> (opens Google in a new tab)</span>
                      </a>
                    </Card>
                  ) : null;
                default:
                  return (
                    <Card key={action} action={action} index={i}>
                      <button type="button" onClick={() => setSheet(action as OpenSheet)}>
                        <CardBody action={action} />
                      </button>
                    </Card>
                  );
              }
            })}
          </ul>

          {actions.length === 0 && (
            <p className="km-l-note">
              <Link to={menuPath} onClick={() => markLanded(qr)}>
                View the menu
              </Link>
            </p>
          )}

          {(tel || isSafeExternal(l.directionsUrl) || isSafeExternal(l.websiteUrl)) && (
            <nav className="km-l-info" aria-label="Find and contact us">
              {tel && (
                <a href={tel}>
                  <CIcon name="phone" size={18} />
                  Call
                </a>
              )}
              {isSafeExternal(l.directionsUrl) && (
                <a href={l.directionsUrl} target="_blank" rel="noopener noreferrer">
                  <CIcon name="pin" size={18} />
                  Directions
                </a>
              )}
              {isSafeExternal(l.websiteUrl) && (
                <a href={l.websiteUrl} target="_blank" rel="noopener noreferrer">
                  <CIcon name="globe" size={18} />
                  Website
                </a>
              )}
            </nav>
          )}

          {data.hours.known && (
            <details className="km-l-hours">
              <summary>
                <span className={`km-l-dot ${data.hours.open ? 'open' : 'shut'}`} aria-hidden="true" />
                {data.hours.label}
                <svg className="km-l-chev" viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path d="M6 9l6 6 6-6" />
                </svg>
              </summary>
              <ul>
                {data.hours.week.map((d) => (
                  <li key={d.day}>
                    <span>{d.day}</span>
                    <span>{d.hours}</span>
                  </li>
                ))}
              </ul>
            </details>
          )}

          {social.length > 0 && (
            <ul className="km-l-social" aria-label="Follow us">
              {social.map((s) => (
                <li key={s.label}>
                  <a href={s.url!} target="_blank" rel="noopener noreferrer">
                    <SocialIcon label={s.label} />
                    {s.label}
                    <span className="sr-only"> (opens in a new tab)</span>
                  </a>
                </li>
              ))}
            </ul>
          )}
        </main>
      </div>

      {sheet === 'WIFI' && <WifiSheet qr={qr} onClose={() => setSheet(null)} />}
      {sheet === 'REWARDS' && <RewardsSheet qr={qr} landing={data} onClose={() => setSheet(null)} />}
    </div>
  );
}

/** The QR landing page for the table in the address. Opened directly at /menu/:qr/home and by the entry gate. */
export default function LandingRoute() {
  const { qr = '' } = useParams();
  const landing = useQuery({ queryKey: ['landing', qr], queryFn: () => landingApi.get(qr), retry: false });

  if (landing.isLoading) {
    return (
      <div className="km km-landing">
        <div className="km-l-skeleton" role="status" aria-label="Loading" />
      </div>
    );
  }
  if (landing.error) {
    const notFound = landing.error instanceof ApiError && landing.error.status === 404;
    return (
      <StateCard
        title={notFound ? 'This QR code is not active' : 'This page could not open'}
        action={
          notFound ? undefined : (
            <button type="button" className="km-btn-fire" onClick={() => landing.refetch()}>
              Try again
            </button>
          )
        }
      >
        {notFound
          ? 'The table may have been removed or its code replaced. Please ask a member of staff for help.'
          : (landing.error as Error).message}
      </StateCard>
    );
  }
  // A restaurant that has not switched the landing page on keeps its QR code opening the menu.
  if (!landing.data || !landing.data.landingEnabled) return <Navigate to={`/menu/${qr}?view=menu`} replace />;
  return <Landing qr={qr} data={landing.data} />;
}
