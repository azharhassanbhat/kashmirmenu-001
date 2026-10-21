import type { CSSProperties } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { platformAdminApi } from '../../api';
import type { PlatformOverview } from '../../api/types';
import { RestaurantActions } from '../../components/platform/RestaurantActions';
import { EmptyState, ErrorMessage, QueryState } from '../../components/ui';
import { formatDate } from '../../lib/platform';

const CARDS: { key: keyof PlatformOverview; label: string; to: string }[] = [
  { key: 'total', label: 'Total registered', to: '/platform-admin/restaurants' },
  { key: 'pending', label: 'Pending approval', to: '/platform-admin/restaurants/pending' },
  { key: 'approved', label: 'Approved', to: '/platform-admin/restaurants/approved' },
  { key: 'rejected', label: 'Rejected', to: '/platform-admin/restaurants/rejected' },
  { key: 'suspended', label: 'Suspended', to: '/platform-admin/restaurants?status=SUSPENDED' },
];

export default function PlatformDashboardPage() {
  const overview = useQuery({ queryKey: ['platform', 'overview'], queryFn: platformAdminApi.overview });
  // The first five waiting, oldest first so nobody waits longest.
  const pending = useQuery({
    queryKey: ['platform', 'restaurants', 'dashboard-pending'],
    queryFn: () =>
      platformAdminApi.list({ q: '', status: 'PENDING', page: 0, size: 5, sortBy: 'createdAt', direction: 'asc' }),
  });

  return (
    <>
      <div className="page-head">
        <h2>Platform dashboard</h2>
      </div>

      <QueryState isLoading={overview.isLoading} error={null}>
        {overview.error ? (
          <div>
            <ErrorMessage error={overview.error} />
            <button className="btn btn-sm" onClick={() => void overview.refetch()}>
              Try again
            </button>
          </div>
        ) : (
          overview.data && (
            <div className="stats">
              {CARDS.map((c, i) => (
                <Link
                  key={c.key}
                  to={c.to}
                  className={`card stat pf-stat pf-stat-${c.key}`}
                  style={{ '--i': i } as CSSProperties}
                >
                  <div className="value">{overview.data[c.key]}</div>
                  <div className="label">{c.label}</div>
                </Link>
              ))}
            </div>
          )
        )}
      </QueryState>

      <div className="page-head">
        <h3 style={{ margin: 0 }}>Awaiting approval</h3>
        <Link className="btn btn-sm" to="/platform-admin/restaurants/pending">
          View all
        </Link>
      </div>
      <QueryState isLoading={pending.isLoading} error={null}>
        {pending.error ? (
          <div>
            <ErrorMessage error={pending.error} />
            <button className="btn btn-sm" onClick={() => void pending.refetch()}>
              Try again
            </button>
          </div>
        ) : pending.data && pending.data.items.length === 0 ? (
          <EmptyState icon="check">No restaurants are waiting for approval.</EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Restaurant</th>
                  <th>Owner</th>
                  <th>Registered</th>
                  <th>
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {pending.data?.items.map((r) => (
                  <tr key={r.id}>
                    <td data-label="Restaurant">
                      <strong>{r.name}</strong>
                    </td>
                    <td data-label="Owner" className="pf-contact">
                      {r.ownerName}
                      <div className="muted">{r.ownerEmail}</div>
                    </td>
                    <td data-label="Registered">{formatDate(r.createdAt)}</td>
                    <td className="num">
                      <span className="row-actions">
                        <Link className="btn btn-sm" to={`/platform-admin/restaurants/${r.id}`}>
                          Review
                        </Link>
                        <RestaurantActions restaurant={r} />
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </QueryState>
    </>
  );
}
