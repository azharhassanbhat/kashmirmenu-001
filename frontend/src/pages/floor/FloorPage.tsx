import { Link } from 'react-router-dom';
import { useAuth } from '../../auth/AuthContext';
import { SoundSettingsButton } from '../../components/notifications/SoundSettingsButton';
import { BrandMark } from '../../components/ui';
import { LiveTablesPanel } from '../admin/LiveTablesPage';

/**
 * The live tables view for floor staff: waiters and cashiers, who confirm payments and close tables but
 * have no business in the admin area. The same panel the owner sees, on a plain page.
 */
export default function FloorPage() {
  const { user, logout } = useAuth();

  return (
    <div className="floor">
      <header className="floor-head">
        <div className="brand">
          <BrandMark />
          <span>Kashmir Menu</span>
        </div>
        <div className="floor-tools">
          {user && <span className="muted">{user.name}</span>}
          <SoundSettingsButton />
          <Link className="btn btn-sm" to="/requests">
            Table requests
          </Link>
          {user?.role !== 'CASHIER' && (
            <Link className="btn btn-sm" to="/kitchen">
              Kitchen
            </Link>
          )}
          <button type="button" className="btn btn-sm" onClick={logout}>
            Sign out
          </button>
        </div>
      </header>
      <main className="floor-main">
        <LiveTablesPanel />
      </main>
    </div>
  );
}
