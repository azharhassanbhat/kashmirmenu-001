import { useEffect, useRef, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { notificationApi } from '../../api';
import { useAuth } from '../../auth/AuthContext';
import { useNotificationCenter } from '../../components/notifications/NotificationCenter';
import '../../dashboard/tailwind.css';
import '../../dashboard/dashboard.css';
import { navBadges } from '../../dashboard/data';
import { Sidebar, TopBar, useRestaurantName } from '../../dashboard/Shell';
import { useDashboardMode, useMediaQuery } from '../../dashboard/theme';

/**
 * The admin shell: sidebar, top bar and the page outlet. The sidebar is a fixed column on wide screens, an icon
 * rail on laptops and a drawer on tablets and phones. The dashboard's own light/dark choice is scoped to this
 * shell, so the rest of the app (diners, the kitchen) keeps following the device.
 */
export default function AdminLayout() {
  const { user, logout } = useAuth();
  const { unreadCount, connected } = useNotificationCenter();
  const restaurantName = useRestaurantName(user);
  // Same key and function as the notification centre and the bell, so this shares their cache.
  const unread = useQuery({
    queryKey: ['notifications', user?.restaurantId ?? null],
    queryFn: () => notificationApi.unread(user!.restaurantId!),
    enabled: user?.restaurantId != null,
    staleTime: 15_000,
  });
  const badges = navBadges(unread.data ?? []);
  const { mode, toggle: toggleMode } = useDashboardMode();
  const { pathname } = useLocation();

  const wide = useMediaQuery('(min-width: 1280px)');
  const desktop = useMediaQuery('(min-width: 1024px)');
  // null = follow the screen (full sidebar when wide, rail on laptops); a choice made with the toggle sticks.
  const [collapsedChoice, setCollapsedChoice] = useState<boolean | null>(null);
  const [drawer, setDrawer] = useState(false);
  const collapsed = desktop && (collapsedChoice ?? !wide);

  // The drawer closes after navigating and on Escape.
  useEffect(() => setDrawer(false), [pathname]);
  const opener = useRef<HTMLElement | null>(null);
  useEffect(() => {
    if (!drawer) return;
    // While the drawer is open: the page behind it does not scroll, focus moves into the drawer and Tab stays
    // inside it, and closing hands focus back to the control that opened it.
    // Safari does not focus a button when it is tapped, so the menu button is looked up rather than assumed.
    opener.current = document.querySelector<HTMLElement>('[aria-controls="kd-nav"]');
    document.documentElement.classList.add('kd-lock');
    const nav = document.getElementById('kd-nav');
    const focusables = () => Array.from(nav?.querySelectorAll<HTMLElement>('a[href], button:not([disabled])') ?? []);
    window.requestAnimationFrame(() => focusables()[0]?.focus());
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setDrawer(false);
        return;
      }
      if (e.key !== 'Tab') return;
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
      document.documentElement.classList.remove('kd-lock');
      opener.current?.focus();
    };
  }, [drawer]);
  useEffect(() => {
    if (desktop) setDrawer(false);
  }, [desktop]);

  return (
    <div className={`kd kd-shell${collapsed ? ' is-collapsed' : ''}`} data-mode={mode}>
      <a className="kd-skip" href="#kd-main">
        Skip to content
      </a>
      <Sidebar restaurantName={restaurantName} badges={badges} collapsed={collapsed} drawerOpen={drawer} hiddenOffscreen={!desktop && !drawer} onNavigate={() => setDrawer(false)} />
      {drawer && <button type="button" className="kd-scrim" aria-label="Close navigation" onClick={() => setDrawer(false)} />}
      <div className="kd-body">
        <TopBar
          user={user}
          restaurantName={restaurantName}
          connected={connected}
          mode={mode}
          onToggleMode={toggleMode}
          onToggleNav={() => (desktop ? setCollapsedChoice(!collapsed) : setDrawer((o) => !o))}
          navExpanded={desktop ? !collapsed : drawer}
          unreadCount={unreadCount}
          restaurantId={user?.restaurantId ?? null}
          onLogout={logout}
        />
        <main className="kd-main" id="kd-main">
          {user?.restaurantId == null ? (
            <div className="alert alert-info">
              This account is not attached to a restaurant. Restaurant-scoped pages will be empty or unavailable.
            </div>
          ) : null}
          <Outlet />
        </main>
      </div>
    </div>
  );
}
