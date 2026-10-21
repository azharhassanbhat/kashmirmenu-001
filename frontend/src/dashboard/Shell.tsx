import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Link, NavLink, useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Bell,
  BellRing,
  Boxes,
  Check,
  ChevronDown,
  ClipboardList,
  Flame,
  IndianRupee,
  LayoutDashboard,
  LogOut,
  MapPin,
  Menu as MenuIcon,
  MessageSquareHeart,
  Moon,
  Percent,
  QrCode,
  Search,
  Settings,
  Sparkles,
  Store,
  Sun,
  Tags,
  Ticket,
  UserCog,
  UtensilsCrossed,
  Volume2,
  Wallet,
  X,
} from 'lucide-react';
import { notificationApi, orderApi, restaurantApi } from '../api';
import type { Me } from '../api/types';
import { timeAgo } from '../lib/format';
import { BrandLeaf, LeafPattern } from './art';
import { searchEntries, type NavBadges, type SearchEntry } from './data';
import { Popover } from './kit';
import { notificationLook } from './NotificationsPanel';
import type { DashboardMode } from './theme';

/* ---------- navigation ---------- */

interface NavItem {
  to: string;
  label: string;
  icon: ReactNode;
  end?: boolean;
  /** Which unread count this entry shows, if any. */
  badge?: keyof NavBadges;
}

export const NAV_GROUPS: { label: string; items: NavItem[] }[] = [
  {
    label: 'Overview',
    items: [
      { to: '/admin', label: 'Dashboard', icon: <LayoutDashboard />, end: true, badge: 'other' },
      { to: '/admin/live', label: 'Live tables', icon: <UtensilsCrossed /> },
      { to: '/admin/requests', label: 'Table requests', icon: <BellRing />, badge: 'requests' },
    ],
  },
  {
    label: 'Orders & Tables',
    items: [
      { to: '/admin/orders', label: 'Orders', icon: <ClipboardList />, badge: 'orders' },
      { to: '/admin/tables', label: 'Tables & QR', icon: <QrCode /> },
    ],
  },
  {
    label: 'Menu & Inventory',
    items: [
      { to: '/admin/menu', label: 'Menu items', icon: <UtensilsCrossed /> },
      { to: '/admin/categories', label: 'Categories', icon: <Tags /> },
      { to: '/admin/inventory', label: 'Inventory', icon: <Boxes /> },
    ],
  },
  {
    label: 'Finance',
    items: [
      { to: '/admin/revenue', label: 'Payments & revenue', icon: <IndianRupee /> },
      { to: '/admin/coupons', label: 'Coupons', icon: <Ticket /> },
    ],
  },
  {
    label: 'Settings',
    items: [
      { to: '/admin/payments', label: 'Payment settings', icon: <Wallet /> },
      { to: '/admin/billing', label: 'Taxes & service charge', icon: <Percent /> },
      { to: '/admin/staff', label: 'Team', icon: <UserCog /> },
      { to: '/admin/landing', label: 'QR landing page', icon: <Sparkles /> },
      { to: '/admin/feedback', label: 'Customer feedback', icon: <MessageSquareHeart /> },
      { to: '/admin/notifications', label: 'Alerts & sound', icon: <Settings /> },
    ],
  },
  {
    label: 'Live',
    items: [{ to: '/kitchen', label: 'Kitchen display', icon: <Flame /> }],
  },
];

const SEARCH_PAGES: SearchEntry[] = NAV_GROUPS.flatMap((g) => g.items.map((i) => ({ label: i.label, hint: g.label, to: i.to })));

export function Sidebar({
  restaurantName,
  badges,
  collapsed,
  drawerOpen,
  hiddenOffscreen = false,
  onNavigate,
}: {
  restaurantName: string;
  badges: NavBadges;
  collapsed: boolean;
  drawerOpen: boolean;
  /** True while the sidebar is a closed drawer: it is off-screen, so it must not take keyboard focus or be read out. */
  hiddenOffscreen?: boolean;
  onNavigate: () => void;
}) {
  return (
    <nav
      id="kd-nav"
      className={`kd-sidebar${collapsed ? ' is-collapsed' : ''}${drawerOpen ? ' is-open' : ''}`}
      aria-label="Admin"
      inert={hiddenOffscreen}
    >
      <LeafPattern />
      <div className="kd-brand">
        <BrandLeaf />
        <div className="kd-brand-text">
          <span className="kd-brand-name">
            KASHMIR <em>MENU</em>
          </span>
          <span className="kd-brand-tag">TASTE OUR HERITAGE</span>
        </div>
      </div>

      <div className="kd-nav-scroll">
        {NAV_GROUPS.map((g) => (
          <div key={g.label} className="kd-nav-group">
            <div className="kd-nav-label">{g.label}</div>
            {g.items.map((item) => (
              <NavLink key={item.to} to={item.to} end={item.end} className="kd-nav-link" onClick={onNavigate} title={collapsed ? item.label : undefined}>
                <span className="kd-nav-icon" aria-hidden="true">
                  {item.icon}
                </span>
                <span className="kd-nav-text">{item.label}</span>
                {item.badge && badges[item.badge] > 0 && (
                  <span className="kd-nav-badge" aria-label={`${badges[item.badge]} unread`}>
                    {badges[item.badge] > 99 ? '99+' : badges[item.badge]}
                  </span>
                )}
              </NavLink>
            ))}
          </div>
        ))}
      </div>

      <div className="kd-side-foot">
        <div className="kd-profile-card">
          <span className="kd-profile-img" aria-hidden="true">
            <Store />
          </span>
          <span className="kd-profile-text">
            <strong>{restaurantName}</strong>
          </span>
        </div>
      </div>
    </nav>
  );
}

/* ---------- top bar ---------- */

export function TopBar({
  user,
  restaurantName,
  connected,
  mode,
  onToggleMode,
  onToggleNav,
  navExpanded,
  unreadCount,
  restaurantId,
  onLogout,
}: {
  user: Me | null;
  restaurantName: string;
  connected: boolean;
  mode: DashboardMode;
  onToggleMode: () => void;
  onToggleNav: () => void;
  navExpanded: boolean;
  unreadCount: number;
  restaurantId: number | null;
  onLogout: () => void;
}) {
  const initial = (user?.name ?? '?').trim().charAt(0).toUpperCase();
  const role = user?.role === 'RESTAURANT_OWNER' ? 'Owner' : (user?.role ?? '').toString().toLowerCase().replace(/^\w/, (c) => c.toUpperCase());

  return (
    <header className="kd-topbar">
      <button type="button" className="kd-icon-btn kd-glass" aria-label="Toggle navigation" aria-expanded={navExpanded} aria-controls="kd-nav" onClick={onToggleNav}>
        <MenuIcon aria-hidden="true" />
      </button>

      <Popover
        align="left"
        className="kd-restaurant"
        button={({ open, toggle, panelId }) => (
          <button type="button" className="kd-select kd-glass kd-restaurant-btn" aria-haspopup="menu" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
            <MapPin aria-hidden="true" className="kd-pin" />
            <span className="kd-restaurant-name">{restaurantName}</span>
            <ChevronDown aria-hidden="true" />
          </button>
        )}
      >
        {(close) => (
          <div className="kd-menu" role="menu">
            <span className="kd-menu-title">Your restaurant</span>
            <button type="button" role="menuitemradio" aria-checked="true" className="is-active" onClick={close}>
              <Check aria-hidden="true" /> {restaurantName}
            </button>
            <span className="kd-menu-note">This account manages one restaurant.</span>
          </div>
        )}
      </Popover>

      <span className={`kd-live-pill kd-glass${connected ? ' is-on' : ''}`} role="status" title={connected ? 'Live updates are on' : 'Reconnecting to live updates'}>
        <i aria-hidden="true" />
        {connected ? 'Live' : 'Reconnecting'}
      </span>

      <div className="kd-top-spacer" />

      <SearchBox restaurantId={restaurantId} />

      <button type="button" className="kd-mode kd-glass" role="switch" aria-checked={mode === 'light'} aria-label="Light theme" onClick={onToggleMode}>
        <span className={mode === 'light' ? 'is-on' : ''}>
          <Sun aria-hidden="true" />
        </span>
        <span className={mode === 'dark' ? 'is-on' : ''}>
          <Moon aria-hidden="true" />
        </span>
      </button>

      <BellMenu unreadCount={unreadCount} restaurantId={restaurantId} />

      <Popover
        className="kd-profile"
        button={({ open, toggle, panelId }) => (
          <button type="button" className="kd-profile-btn kd-glass" aria-haspopup="menu" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
            <span className="kd-avatar" aria-hidden="true">
              {initial}
            </span>
            <span className="kd-profile-who">
              <strong>{user?.name}</strong>
              <small>{role}</small>
            </span>
            <ChevronDown aria-hidden="true" />
          </button>
        )}
      >
        {(close) => (
          <div className="kd-menu" role="menu">
            <span className="kd-menu-title">{user?.email}</span>
            <Link role="menuitem" to="/admin/notifications" onClick={close}>
              <Volume2 aria-hidden="true" /> Alerts &amp; sound
            </Link>
            <Link role="menuitem" to="/admin/staff" onClick={close}>
              <UserCog aria-hidden="true" /> Team
            </Link>
            <button type="button" role="menuitem" onClick={onLogout}>
              <LogOut aria-hidden="true" /> Sign out
            </button>
          </div>
        )}
      </Popover>
    </header>
  );
}

/* ---------- search ---------- */

function SearchBox({ restaurantId }: { restaurantId: number | null }) {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const navigate = useNavigate();
  const box = useRef<HTMLDivElement>(null);
  const wanted = q.trim().length > 0;
  // Orders are fetched only once the person starts typing.
  const orders = useQuery({
    queryKey: ['orders', 'dashboard-search'],
    queryFn: () => orderApi.list(0, 50),
    enabled: wanted && restaurantId !== null,
    staleTime: 30_000,
  });
  const entries = useMemo<SearchEntry[]>(
    () => [
      ...SEARCH_PAGES,
      ...(orders.data?.items ?? []).map((o) => ({ label: o.orderNumber, hint: `Order${o.dinerName ? ` · ${o.dinerName}` : ''}`, to: '/admin/orders' })),
    ],
    [orders.data],
  );
  const results = useMemo(() => searchEntries(entries, q), [entries, q]);

  useEffect(() => setActive(0), [q]);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (box.current && !box.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const go = (entry: SearchEntry) => {
    setOpen(false);
    setQ('');
    navigate(entry.to);
  };

  return (
    <div className="kd-search" ref={box}>
      <Search aria-hidden="true" className="kd-search-icon" />
      <input
        type="search"
        placeholder="Search anything..."
        aria-label="Search pages and orders"
        role="combobox"
        aria-expanded={open && wanted}
        aria-controls="kd-search-results"
        aria-activedescendant={open && results[active] ? `kd-sr-${active}` : undefined}
        value={q}
        onChange={(e) => {
          setQ(e.target.value);
          setOpen(true);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActive((i) => Math.min(results.length - 1, i + 1));
          } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActive((i) => Math.max(0, i - 1));
          } else if (e.key === 'Enter' && results[active]) {
            go(results[active]);
          } else if (e.key === 'Escape') {
            setOpen(false);
          }
        }}
      />
      {q && (
        <button type="button" className="kd-search-clear" aria-label="Clear search" onClick={() => setQ('')}>
          <X aria-hidden="true" />
        </button>
      )}
      {open && wanted && (
        <ul id="kd-search-results" className="kd-search-results" role="listbox">
          {results.length === 0 && <li className="kd-search-none">Nothing matches “{q.trim()}”.</li>}
          {results.map((r, i) => (
            <li key={`${r.to}-${r.label}`} id={`kd-sr-${i}`} role="option" aria-selected={i === active}>
              <button type="button" className={i === active ? 'is-active' : ''} onMouseEnter={() => setActive(i)} onClick={() => go(r)}>
                <strong>{r.label}</strong>
                <small>{r.hint}</small>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/* ---------- notification bell ---------- */

function BellMenu({ unreadCount, restaurantId }: { unreadCount: number; restaurantId: number | null }) {
  const queryClient = useQueryClient();
  // Same key and function as the notification centre, so this shares its cache and refreshes with live events.
  const unread = useQuery({
    queryKey: ['notifications', restaurantId],
    queryFn: () => notificationApi.unread(restaurantId!),
    enabled: restaurantId !== null,
    staleTime: 15_000,
  });
  const markRead = useMutation({
    mutationFn: notificationApi.markRead,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['notifications'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });
  const items = (unread.data ?? []).slice(0, 5);

  return (
    <Popover
      className="kd-bell"
      button={({ open, toggle, panelId }) => (
        <button
          type="button"
          className="kd-icon-btn kd-glass"
          aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
          aria-haspopup="menu"
          aria-expanded={open}
          aria-controls={panelId}
          onClick={toggle}
        >
          <Bell aria-hidden="true" />
          {unreadCount > 0 && <span className="kd-bell-badge">{unreadCount > 99 ? '99+' : unreadCount}</span>}
        </button>
      )}
      panelClassName="kd-bell-panel"
    >
      {(close) => (
        <div className="kd-menu" role="menu">
          <span className="kd-menu-title">Unread notifications</span>
          {items.length === 0 && <span className="kd-menu-note">You are all caught up.</span>}
          {items.map((n) => {
            const look = notificationLook(n.notificationType, n.eventKind);
            return (
              <button key={n.id} type="button" role="menuitem" className="kd-bell-item" onClick={() => markRead.mutate(n.id)} title="Mark as read">
                <span className={`kd-notif-icon kd-tone-${look.tone}`} aria-hidden="true">
                  {look.icon}
                </span>
                <span>
                  <strong>{n.title}</strong>
                  <small>
                    {n.message} · {timeAgo(n.createdAt)}
                  </small>
                </span>
              </button>
            );
          })}
          <Link role="menuitem" to="/admin" className="kd-bell-all" onClick={close}>
            See all on the dashboard
          </Link>
        </div>
      )}
    </Popover>
  );
}

/** The restaurant's name for the top bar and sidebar; only owners may read the record, others see a generic label. */
export function useRestaurantName(user: Me | null): string {
  const allowed = user !== null && user.restaurantId !== null && (user.role === 'RESTAURANT_OWNER' || user.role === 'ADMIN');
  const q = useQuery({
    queryKey: ['restaurant', user?.restaurantId],
    queryFn: () => restaurantApi.get(user!.restaurantId!),
    enabled: allowed,
    retry: false,
    staleTime: 10 * 60_000,
  });
  return q.data?.name ?? 'Your restaurant';
}
