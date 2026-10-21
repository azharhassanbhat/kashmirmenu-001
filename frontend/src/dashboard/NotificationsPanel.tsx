import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AnimatePresence, motion, useReducedMotion } from 'framer-motion';
import {
  AlertTriangle,
  Bell,
  CheckCircle2,
  ChefHat,
  ConciergeBell,
  CreditCard,
  MessageSquare,
  MoreVertical,
  PackageCheck,
  ReceiptText,
  ShoppingBag,
  Store,
  UtensilsCrossed,
  XCircle,
} from 'lucide-react';
import { notificationApi } from '../api';
import type { AppNotification } from '../api/types';
import { parseServerTime, timeAgo } from '../lib/format';
import { Panel, PanelError, PanelSkeleton, Popover } from './kit';

type Tone = 'green' | 'blue' | 'orange' | 'red' | 'purple' | 'gray';

/** An icon and colour for each kind of notification the server sends. */
export function notificationLook(type: string, eventKind?: string | null): { icon: ReactNode; tone: Tone } {
  // The structured kind tells a bill request from a waiter call; both share one notification type.
  if (type === 'SERVICE_REQUEST_CREATED' && eventKind === 'BILL_REQUEST') return { icon: <ReceiptText />, tone: 'purple' };
  switch (type) {
    case 'PAYMENT_SUCCESS':
      return { icon: <CreditCard />, tone: 'green' };
    case 'PAYMENT_FAILED':
      return { icon: <AlertTriangle />, tone: 'red' };
    case 'PAYMENT_REFUNDED':
      return { icon: <ReceiptText />, tone: 'purple' };
    case 'ORDER_PLACED':
      return { icon: <ShoppingBag />, tone: 'blue' };
    case 'ORDER_CONFIRMED':
      return { icon: <CheckCircle2 />, tone: 'blue' };
    case 'ORDER_PREPARING':
      return { icon: <ChefHat />, tone: 'orange' };
    case 'ORDER_READY':
      return { icon: <PackageCheck />, tone: 'green' };
    case 'ORDER_SERVED':
      return { icon: <UtensilsCrossed />, tone: 'blue' };
    case 'ORDER_CANCELLED':
      return { icon: <XCircle />, tone: 'red' };
    case 'SERVICE_REQUEST_CREATED':
    case 'SERVICE_REQUEST_UPDATED':
      return { icon: <ConciergeBell />, tone: 'orange' };
    case 'FEEDBACK_RECEIVED':
      return { icon: <MessageSquare />, tone: 'purple' };
    case 'RESTAURANT_APPROVED':
    case 'RESTAURANT_REACTIVATED':
    case 'RESTAURANT_REJECTED':
    case 'RESTAURANT_SUSPENDED':
      return { icon: <Store />, tone: type.endsWith('APPROVED') || type.endsWith('REACTIVATED') ? 'green' : 'red' };
    default:
      return { icon: <Bell />, tone: 'gray' };
  }
}

function stamp(iso: string): string {
  const at = parseServerTime(iso);
  return at ? new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true }).format(at) : '';
}

/**
 * The latest notifications, read and unread. Marking one read (or all) uses the same endpoint the bell uses, and
 * live events refresh this list because its query key sits under "notifications".
 */
export function NotificationsPanel({ restaurantId }: { restaurantId: number | null }) {
  const queryClient = useQueryClient();
  const reduce = useReducedMotion();
  const recent = useQuery({
    queryKey: ['notifications', 'recent', restaurantId],
    queryFn: () => notificationApi.recent(8),
    enabled: restaurantId !== null,
    staleTime: 10_000,
  });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };
  const markRead = useMutation({ mutationFn: notificationApi.markRead, onSuccess: refresh });
  const markAll = useMutation({
    mutationFn: async (ids: number[]) => {
      await Promise.all(ids.map((id) => notificationApi.markRead(id)));
    },
    onSettled: refresh,
  });

  const items: AppNotification[] = recent.data?.items ?? [];
  const unread = items.filter((n) => !n.read);
  const busy = markRead.isPending || markAll.isPending;

  return (
    <Panel
      className="kd-notifs"
      tone="red"
      icon={<Bell />}
      title="Notifications"
      subtitle="Latest updates from your restaurant"
      action={
        <div className="kd-notif-head-right">
          {unread.length > 0 && <span className="kd-count-badge" aria-label={`${unread.length} unread`}>{unread.length}</span>}
          <button type="button" className="kd-link-btn" disabled={busy || unread.length === 0} onClick={() => markAll.mutate(unread.map((n) => n.id))}>
            Mark all as read
          </button>
        </div>
      }
    >
      {recent.isLoading && <PanelSkeleton rows={4} />}
      {recent.isError && <PanelError message="Notifications could not be loaded." onRetry={() => void recent.refetch()} />}
      {(markRead.isError || markAll.isError) && <PanelError message="That could not be saved. Please try again." />}
      {recent.data && items.length === 0 && <p className="kd-empty">You are all caught up.</p>}
      <ul className="kd-notif-list">
        <AnimatePresence initial={false}>
          {items.map((n) => {
            const look = notificationLook(n.notificationType, n.eventKind);
            return (
              <motion.li
                key={n.id}
                layout={!reduce}
                className={`kd-notif${n.read ? '' : ' is-unread'}`}
                initial={reduce ? false : { opacity: 0, x: -8 }}
                animate={{ opacity: 1, x: 0 }}
                exit={reduce ? undefined : { opacity: 0 }}
              >
                <span className={`kd-notif-icon kd-tone-${look.tone}`} aria-hidden="true">
                  {look.icon}
                </span>
                <strong className="kd-notif-title">{n.title}</strong>
                <span className="kd-notif-text">{n.message}</span>
                <span className="kd-notif-time">
                  <span>{stamp(n.createdAt)}</span>
                  <em>{timeAgo(n.createdAt)}</em>
                </span>
                <span className={`kd-unread-dot kd-tone-${look.tone}${n.read ? ' is-read' : ''}`} role="img" aria-label={n.read ? 'Read' : 'Unread'} />
                <button type="button" className="kd-btn kd-btn-xs" disabled={n.read || busy} onClick={() => markRead.mutate(n.id)}>
                  {n.read ? 'Read' : 'Mark read'}
                </button>
                <Popover
                  className="kd-notif-more"
                  button={({ open, toggle, panelId }) => (
                    <button type="button" className="kd-icon-btn" aria-label={`Options for ${n.title}`} aria-haspopup="menu" aria-expanded={open} aria-controls={panelId} onClick={toggle}>
                      <MoreVertical aria-hidden="true" />
                    </button>
                  )}
                >
                  {(close) => (
                    <div className="kd-menu" role="menu">
                      {!n.read && (
                        <button
                          type="button"
                          role="menuitem"
                          onClick={() => {
                            markRead.mutate(n.id);
                            close();
                          }}
                        >
                          Mark as read
                        </button>
                      )}
                      {n.orderId !== null && (
                        <Link role="menuitem" to="/admin/orders" onClick={close}>
                          Open in Orders
                        </Link>
                      )}
                      {n.read && n.orderId === null && <span className="kd-menu-note">Nothing else to do here.</span>}
                    </div>
                  )}
                </Popover>
              </motion.li>
            );
          })}
        </AnimatePresence>
      </ul>
    </Panel>
  );
}
