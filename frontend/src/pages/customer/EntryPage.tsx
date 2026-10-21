import { lazy } from 'react';
import { useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { landingApi } from '../../api';
import { Spinner } from '../../components/ui';
import { hasLanded, markLanded } from '../../lib/landing';
import MenuPage from './MenuPage';

const LandingRoute = lazy(() => import('./LandingPage'));

/**
 * What a printed table QR opens. The address is the same as ever (/menu/:qr), so cards already on tables keep
 * working. A restaurant that switched its landing page on shows it first; once the guest has chosen to go on
 * (or the address says ?view=menu) it shows the menu, and every link inside the app keeps doing exactly that.
 * A restaurant that never opted in, or a server too old to know the landing page, gets the menu as before.
 */
export default function EntryPage() {
  const { qr = '' } = useParams();
  const [params] = useSearchParams();
  const wantsMenu = params.get('view') === 'menu';
  if (wantsMenu) markLanded(qr);
  const skip = wantsMenu || hasLanded(qr);

  const landing = useQuery({
    queryKey: ['landing', qr],
    queryFn: () => landingApi.get(qr),
    retry: false,
    enabled: !skip,
  });

  if (skip) return <MenuPage />;
  if (landing.isLoading) return <Spinner />;
  if (landing.data?.landingEnabled) return <LandingRoute />;
  return <MenuPage />;
}
