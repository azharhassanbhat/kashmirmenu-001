import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { authApi } from '../api';
import { getToken, setNotApprovedHandler, setToken, setUnauthorizedHandler } from '../api/client';
import type { Me } from '../api/types';
import { FLOOR_ROLES, KITCHEN_ROLES, MANAGER_ROLES, PLATFORM_ROLES, homeFor } from '../lib/staff';

type Status = 'loading' | 'anonymous' | 'authenticated';

interface AuthState {
  status: Status;
  user: Me | null;
  login: (email: string, password: string) => Promise<Me>;
  registerOwner: (body: Parameters<typeof authApi.registerOwner>[0]) => Promise<Me>;
  logout: () => void;
  /** Re-reads /me (e.g. to see whether the restaurant has been approved since sign-in). */
  refreshMe: () => Promise<Me>;
}

const AuthContext = createContext<AuthState | null>(null);

// Role groups and homeFor live in lib/staff so they stay pure and unit-testable.
export { FLOOR_ROLES, KITCHEN_ROLES, MANAGER_ROLES, PLATFORM_ROLES, homeFor };

export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<Status>(getToken() ? 'loading' : 'anonymous');
  const [user, setUser] = useState<Me | null>(null);
  const queryClient = useQueryClient();

  const logout = useCallback(() => {
    // Tell the server first so the token is revoked for every device, not just forgotten here.
    // Deliberately not awaited and errors are swallowed: signing out must never fail or hang,
    // and the local state is cleared either way (an already-invalid token has nothing to revoke).
    if (getToken()) {
      void authApi.logout().catch(() => undefined);
    }
    setToken(null);
    setUser(null);
    setStatus('anonymous');
    queryClient.clear();
  }, [queryClient]);

  const loadMe = useCallback(async () => {
    const me = await authApi.me();
    setUser(me);
    setStatus('authenticated');
    return me;
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(logout);
    return () => setUnauthorizedHandler(null);
  }, [logout]);

  useEffect(() => {
    if (!getToken()) return;
    loadMe().catch(() => logout());
  }, [loadMe, logout]);

  // A restaurant suspended while its staff are signed in: the next call is refused with a 403 and a
  // code. Re-reading /me updates the status so RequireRole swaps the page for the status screen,
  // instead of leaving error banners on every page until a reload. One refresh at a time.
  useEffect(() => {
    let refreshing = false;
    setNotApprovedHandler(() => {
      if (refreshing || !getToken()) return;
      refreshing = true;
      loadMe()
        .catch(() => undefined)
        .finally(() => {
          refreshing = false;
        });
    });
    return () => setNotApprovedHandler(null);
  }, [loadMe]);

  const value = useMemo<AuthState>(
    () => ({
      status,
      user,
      logout,
      refreshMe: loadMe,
      login: async (email, password) => {
        const { accessToken } = await authApi.login(email, password);
        setToken(accessToken);
        return loadMe().catch((e) => {
          logout();
          throw e;
        });
      },
      registerOwner: async (body) => {
        const { accessToken } = await authApi.registerOwner(body);
        setToken(accessToken);
        return loadMe().catch((e) => {
          logout();
          throw e;
        });
      },
    }),
    [status, user, logout, loadMe],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
