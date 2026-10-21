import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import type { RoleName } from '../api/types';
import RestaurantStatusScreen from '../components/RestaurantStatusScreen';
import { Spinner } from '../components/ui';
import { gatedStatus } from '../lib/platform';
import { useAuth } from './AuthContext';

export default function RequireRole({ roles, children }: { roles: RoleName[]; children: ReactNode }) {
  const { status, user, logout } = useAuth();
  const location = useLocation();

  if (status === 'loading') return <Spinner />;
  if (status === 'anonymous' || !user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (!roles.includes(user.role)) {
    return (
      <div className="center-card">
        <h2>No access</h2>
        <p className="muted">Your account ({user.role.toLowerCase().replace('_', ' ')}) cannot open this area.</p>
        <button className="btn" onClick={logout}>
          Sign out
        </button>
      </div>
    );
  }
  // A restaurant that is not approved has nothing to run yet; the server refuses its requests too.
  // Every restaurant area (admin, kitchen, floor, requests) goes through here, so one check covers all.
  const gate = gatedStatus(user);
  if (gate) return <RestaurantStatusScreen status={gate} reason={user.restaurantStatusReason ?? null} />;
  return <>{children}</>;
}
