import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './auth/AuthContext';
import { ApiError } from './api/client';
import App from './App';
import './fonts.css';
import './styles.css';

// After a new version is deployed, a tab that was already open still points at the old, now-deleted page
// chunks, and the next navigation fails with "Failed to fetch dynamically imported module". Reloading once
// fetches the new version. The timestamp guard stops a reload loop if the files really are missing.
window.addEventListener('vite:preloadError', (event) => {
  const KEY = 'km.chunk-reload';
  const last = Number(sessionStorage.getItem(KEY) ?? 0);
  if (Date.now() - last > 30_000) {
    event.preventDefault();
    sessionStorage.setItem(KEY, String(Date.now()));
    window.location.reload();
  }
});

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      refetchOnWindowFocus: false,
      retry: (count, error) =>
        !(error instanceof ApiError && error.status >= 400 && error.status < 500) && count < 2,
    },
  },
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AuthProvider>
          <App />
        </AuthProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
