import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { platformAdminApi } from '../../api';
import { useAuth } from '../../auth/AuthContext';
import { NoticeProvider } from '../../components/platform/Notice';
import { BrandMark, Icon, type IconName } from '../../components/ui';

const LINKS: { to: string; label: string; icon: IconName; end?: boolean; badge?: 'pending' }[] = [
  { to: '/platform-admin', label: 'Dashboard', icon: 'dashboard', end: true },
  { to: '/platform-admin/restaurants', label: 'Restaurant Management', icon: 'store', end: true },
  { to: '/platform-admin/restaurants/pending', label: 'Pending Approvals', icon: 'clock', badge: 'pending' },
  { to: '/platform-admin/restaurants/approved', label: 'Approved Restaurants', icon: 'check' },
  { to: '/platform-admin/restaurants/rejected', label: 'Rejected Restaurants', icon: 'close' },
  { to: '/platform-admin/settings', label: 'Settings', icon: 'sliders' },
];

export default function PlatformLayout() {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);

  // Shares its key with the dashboard cards, so one refresh after a decision updates both.
  const overview = useQuery({ queryKey: ['platform', 'overview'], queryFn: platformAdminApi.overview, refetchInterval: 60_000 });
  const pending = overview.data?.pending ?? 0;

  useEffect(() => setOpen(false), [pathname]);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);

  const initial = (user?.name ?? '?').trim().charAt(0).toUpperCase();

  return (
    <NoticeProvider>
      <div className="admin platform">
        <header className="admin-top">
          <div className="brand">
            <BrandMark />
            <span>
              Kashmir Menu
              <span className="brand-tag">Platform owner</span>
            </span>
          </div>
          <button
            className="btn btn-ghost btn-icon"
            aria-label="Open navigation"
            aria-expanded={open}
            aria-controls="platform-nav"
            onClick={() => setOpen(true)}
          >
            <Icon name="burger" />
          </button>
        </header>
        {open && <button className="admin-scrim" aria-label="Close navigation" onClick={() => setOpen(false)} />}
        <nav id="platform-nav" className={`admin-nav${open ? ' open' : ''}`} aria-label="Platform">
          <div className="brand">
            <BrandMark />
            <span>
              Kashmir Menu
              <span className="brand-tag">Platform owner</span>
            </span>
          </div>
          <div className="nav-label">Platform</div>
          {LINKS.map((l) => (
            <NavLink key={l.to} to={l.to} end={l.end}>
              <Icon name={l.icon} />
              {l.label}
              {l.badge === 'pending' && pending > 0 && (
                <span className="pf-count" aria-label={`${pending} pending`}>
                  {pending}
                </span>
              )}
            </NavLink>
          ))}
          <div className="spacer" />
          <div className="user-chip">
            <span className="avatar" aria-hidden="true">
              {initial}
            </span>
            <div className="who">
              <span>{user?.name}</span>
              <span className="muted" style={{ fontWeight: 500, fontSize: '0.75rem' }}>
                Platform owner
              </span>
            </div>
          </div>
          <button className="btn btn-sm" onClick={logout}>
            <Icon name="logout" />
            Sign out
          </button>
        </nav>
        <main className="admin-main">
          <Outlet />
        </main>
      </div>
    </NoticeProvider>
  );
}
