import { useAuth } from '../../auth/AuthContext';
import { NotificationSettingsPanel } from '../../components/notifications/NotificationSettingsPanel';
import { roleLabel } from '../../lib/staff';

export default function PlatformSettingsPage() {
  const { user, logout } = useAuth();
  return (
    <>
      <div className="page-head">
        <h2>Settings</h2>
      </div>
      <section className="card" aria-labelledby="pf-account">
        <h3 id="pf-account">Your account</h3>
        <dl className="pf-facts">
          <div>
            <dt>Name</dt>
            <dd>{user?.name}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{user?.email}</dd>
          </div>
          <div>
            <dt>Role</dt>
            <dd>{user ? roleLabel(user.role) : ''}</dd>
          </div>
        </dl>
        <div className="actions" style={{ justifyContent: 'flex-start' }}>
          <button className="btn" onClick={logout}>
            Sign out
          </button>
        </div>
      </section>
      <section className="card" aria-labelledby="pf-sound">
        <h3 id="pf-sound">Notification &amp; Sound Settings</h3>
        <p className="muted">
          Saved on this device for your account. A new restaurant registration always shows a notice, with or without
          sound.
        </p>
        <NotificationSettingsPanel variant="platform" />
      </section>
    </>
  );
}
