import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { platformAdminApi } from '../../api';
import { RestaurantActions, RestaurantStatusBadge } from '../../components/platform/RestaurantActions';
import { EmptyState, ErrorMessage, Spinner } from '../../components/ui';
import { formatDate, formatDateTime, formatLocation } from '../../lib/platform';

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>{children}</dd>
    </div>
  );
}

const dash = (v: string | null | undefined) => (v && v.trim() ? v : '—');

export default function RestaurantDetailPage() {
  const { id } = useParams();
  const numericId = Number(id);
  const valid = Number.isInteger(numericId) && numericId > 0;

  const detail = useQuery({
    queryKey: ['platform', 'restaurant', numericId],
    queryFn: () => platformAdminApi.get(numericId),
    enabled: valid,
  });
  const r = detail.data;

  return (
    <>
      <p style={{ margin: '0 0 0.5rem' }}>
        <Link to="/platform-admin/restaurants">&larr; All restaurants</Link>
      </p>

      {!valid ? (
        <ErrorMessage error={new Error('That restaurant link is not valid.')} />
      ) : detail.isLoading ? (
        <Spinner />
      ) : detail.error ? (
        <div>
          <ErrorMessage error={detail.error} />
          <button className="btn btn-sm" onClick={() => void detail.refetch()}>
            Try again
          </button>
        </div>
      ) : (
        r && (
          <>
            <div className="page-head">
              <h2>
                {r.name} <RestaurantStatusBadge status={r.status} />
              </h2>
              <span className="row-actions">
                <RestaurantActions restaurant={r} small={false} />
              </span>
            </div>

            {r.status === 'REJECTED' && (r.rejectionReason ?? r.statusReason) && (
              <div className="alert alert-error" role="note">
                <strong>Rejection reason:</strong> {r.rejectionReason ?? r.statusReason}
              </div>
            )}
            {r.status === 'SUSPENDED' && (r.suspensionReason ?? r.statusReason) && (
              <div className="alert alert-error" role="note">
                <strong>Suspension reason:</strong> {r.suspensionReason ?? r.statusReason}
              </div>
            )}

            <div className="pf-grid">
              <section className="card" aria-labelledby="pf-restaurant">
                <h3 id="pf-restaurant">Restaurant</h3>
                <dl className="pf-facts">
                  <Fact label="Name">{r.name}</Fact>
                  <Fact label="Email">{dash(r.email)}</Fact>
                  <Fact label="Phone">{dash(r.phone)}</Fact>
                  <Fact label="Address">{dash(r.address)}</Fact>
                  <Fact label="Location">{formatLocation(r.city, r.state, r.country)}</Fact>
                  <Fact label="Slug">{dash(r.slug)}</Fact>
                  <Fact label="Tables">{r.tableCount}</Fact>
                  <Fact label="Menu items">{r.menuItemCount}</Fact>
                </dl>
              </section>

              <section className="card" aria-labelledby="pf-owner">
                <h3 id="pf-owner">Owner</h3>
                <dl className="pf-facts">
                  <Fact label="Name">{r.ownerName}</Fact>
                  <Fact label="Email">{r.ownerEmail}</Fact>
                  <Fact label="Phone">{dash(r.ownerPhone)}</Fact>
                </dl>
              </section>

              <section className="card" aria-labelledby="pf-review">
                <h3 id="pf-review">Registration and review</h3>
                <dl className="pf-facts">
                  <Fact label="Registered">{formatDateTime(r.createdAt)}</Fact>
                  <Fact label="Status">
                    <RestaurantStatusBadge status={r.status} />
                  </Fact>
                  <Fact label="Reviewed by">{dash(r.reviewedByName)}</Fact>
                  <Fact label="Reviewed at">{r.reviewedAt ? formatDateTime(r.reviewedAt) : formatDate(null)}</Fact>
                </dl>
              </section>

              <section className="card" aria-labelledby="pf-docs">
                <h3 id="pf-docs">Documents</h3>
                {r.documents.length === 0 ? (
                  <EmptyState icon="box">No documents uploaded</EmptyState>
                ) : (
                  <ul>
                    {r.documents.map((d) => (
                      <li key={d.id}>{d.name}</li>
                    ))}
                  </ul>
                )}
              </section>
            </div>
          </>
        )
      )}
    </>
  );
}
