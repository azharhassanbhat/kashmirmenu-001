import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { RestaurantStatus } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { gatedStatus, statusBadgeClass, statusLabel } from '../lib/platform';
import { homeFor } from '../lib/staff';
import { ErrorMessage } from './ui';

const COPY: Record<Exclude<RestaurantStatus, 'APPROVED'>, { title: string; body: string }> = {
  PENDING: {
    title: 'Your restaurant is awaiting approval',
    body:
      'Thanks for registering. Our team is reviewing your restaurant. Once it is approved you can set up your menu, ' +
      'tables and team straight away. There is nothing else you need to do; check back here later.',
  },
  REJECTED: {
    title: 'Your registration was not approved',
    body: 'Your restaurant could not be approved at this time. The reason given is below.',
  },
  SUSPENDED: {
    title: 'Your restaurant is suspended',
    body: 'Access to your restaurant has been paused. Please contact support to find out how to restore it.',
  },
};

/** How often a waiting (or suspended) owner's status is re-read while the tab is visible. */
const STATUS_REFRESH_MS = 20_000;

/** Shown in place of the admin, kitchen and floor areas while the restaurant is not approved. */
export default function RestaurantStatusScreen({
  status,
  reason,
}: {
  status: Exclude<RestaurantStatus, 'APPROVED'>;
  reason: string | null;
}) {
  const { user, logout, refreshMe } = useAuth();
  const navigate = useNavigate();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [stillSame, setStillSame] = useState(false);
  const copy = COPY[status];

  // The owner should not have to keep pressing the button: while this tab is visible, /me is re-read
  // every 20 seconds, and once when the tab comes back. A decision then swaps this screen for the app
  // (RequireRole re-renders with the new status) and the notification centre announces it. Errors are
  // ignored here: the button still reports them, and the next tick tries again.
  const refresh = useRef(refreshMe);
  refresh.current = refreshMe;
  useEffect(() => {
    const poll = () => {
      if (document.visibilityState === 'visible') void refresh.current().catch(() => undefined);
    };
    const timer = setInterval(poll, STATUS_REFRESH_MS);
    document.addEventListener('visibilitychange', poll);
    return () => {
      clearInterval(timer);
      document.removeEventListener('visibilitychange', poll);
    };
  }, []);

  async function check() {
    setBusy(true);
    setError(null);
    setStillSame(false);
    try {
      const me = await refreshMe();
      if (gatedStatus(me) === null) {
        navigate(homeFor(me.role), { replace: true });
      } else {
        setStillSame(true);
      }
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="center-card left" role="region" aria-labelledby="restaurant-status-title">
      <span className={`badge ${statusBadgeClass(status)}`}>{statusLabel(status)}</span>
      <h2 id="restaurant-status-title" style={{ marginTop: '0.75rem' }}>
        {copy.title}
      </h2>
      <p className="muted">{copy.body}</p>
      {status !== 'PENDING' && reason && (
        <div className="alert alert-error" role="note">
          <strong>Reason:</strong> {reason}
        </div>
      )}
      {status === 'SUSPENDED' && !reason && <p className="muted">No reason was recorded.</p>}
      <p className="muted">Signed in as {user?.email}.</p>
      <ErrorMessage error={error} />
      <div aria-live="polite">
        {stillSame && (
          <div className="alert alert-info" role="status">
            Your restaurant is still {statusLabel(status).toLowerCase()}.
          </div>
        )}
      </div>
      <div className="actions" style={{ justifyContent: 'flex-start' }}>
        <button className="btn btn-primary" onClick={() => void check()} disabled={busy}>
          {busy ? 'Checking…' : 'Check status again'}
        </button>
        <button className="btn" onClick={logout}>
          Sign out
        </button>
      </div>
    </div>
  );
}
