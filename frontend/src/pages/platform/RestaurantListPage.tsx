import { useEffect, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { platformAdminApi } from '../../api';
import type { PlatformSortBy, RestaurantStatus, SortDirection } from '../../api/types';
import { RestaurantActions, RestaurantStatusBadge } from '../../components/platform/RestaurantActions';
import { EmptyState, ErrorMessage } from '../../components/ui';
import {
  RESTAURANT_STATUSES,
  formatDate,
  formatLocation,
  nextSort,
  pageToFallBackTo,
  statusLabel,
} from '../../lib/platform';

const PAGE_SIZE = 10;

const SORT_OPTIONS: { value: PlatformSortBy; label: string }[] = [
  { value: 'createdAt', label: 'Registration date' },
  { value: 'name', label: 'Restaurant name' },
  { value: 'status', label: 'Status' },
];

const TITLES: Record<RestaurantStatus, string> = {
  PENDING: 'Pending approvals',
  APPROVED: 'Approved restaurants',
  REJECTED: 'Rejected restaurants',
  SUSPENDED: 'Suspended restaurants',
};

/**
 * Every registered restaurant, searchable, filterable and sortable on the server. On the preset routes
 * (pending / approved / rejected) the status filter is locked to that status.
 */
export default function RestaurantListPage({ presetStatus }: { presetStatus?: RestaurantStatus }) {
  const [searchParams] = useSearchParams();
  const fromUrl = searchParams.get('status');
  const initialStatus: RestaurantStatus | '' =
    presetStatus ?? (RESTAURANT_STATUSES.find((s) => s === fromUrl) ?? '');

  const [search, setSearch] = useState('');
  const [q, setQ] = useState('');
  const [status, setStatus] = useState<RestaurantStatus | ''>(initialStatus);
  const [sort, setSort] = useState<{ sortBy: PlatformSortBy; direction: SortDirection }>({
    sortBy: 'createdAt',
    direction: 'desc',
  });
  const [page, setPage] = useState(0);

  // Debounce the search box so each keystroke is not a request.
  useEffect(() => {
    const t = window.setTimeout(() => {
      setQ(search.trim());
      setPage(0);
    }, 350);
    return () => window.clearTimeout(t);
  }, [search]);

  // Follow the URL's ?status= when the owner uses the dashboard cards while already on this page.
  useEffect(() => {
    if (presetStatus) return;
    setStatus(RESTAURANT_STATUSES.find((s) => s === fromUrl) ?? '');
    setPage(0);
  }, [fromUrl, presetStatus]);

  const effectiveStatus = presetStatus ?? status;
  const params = { q, status: effectiveStatus, page, size: PAGE_SIZE, ...sort };
  const list = useQuery({
    queryKey: ['platform', 'restaurants', params],
    queryFn: () => platformAdminApi.list(params),
    placeholderData: keepPreviousData,
  });

  const rows = list.data?.items ?? [];

  // Deciding on the last restaurant of a later page empties it: step back to the last page with rows
  // rather than showing "no restaurants" while earlier pages still have some.
  useEffect(() => {
    if (!list.data || list.isFetching) return;
    const fallback = pageToFallBackTo(page, list.data.totalItems, PAGE_SIZE);
    if (fallback !== null) setPage(fallback);
  }, [list.data, list.isFetching, page]);
  const title = presetStatus ? TITLES[presetStatus] : status ? TITLES[status] : 'Restaurant management';

  const sortBy = (column: PlatformSortBy) => {
    setSort((s) => nextSort(s, column));
    setPage(0);
  };

  return (
    <>
      <div className="page-head">
        <h2>{title}</h2>
      </div>

      <div className="pf-filters">
        <div className="field">
          <label htmlFor="pf-search">
            <span style={{ fontSize: 'var(--fs-sm)', fontWeight: 650 }}>Search</span>
          </label>
          <input
            id="pf-search"
            type="search"
            placeholder="Restaurant, owner, email or city"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        {!presetStatus && (
          <div className="field">
            <label htmlFor="pf-status">
              <span style={{ fontSize: 'var(--fs-sm)', fontWeight: 650 }}>Status</span>
            </label>
            <select
              id="pf-status"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value as RestaurantStatus | '');
                setPage(0);
              }}
            >
              <option value="">All statuses</option>
              {RESTAURANT_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s)}
                </option>
              ))}
            </select>
          </div>
        )}
        {/* Phones hide the table header, so sorting is offered here as well. */}
        <div className="field pf-sort-field">
          <label htmlFor="pf-sort">
            <span style={{ fontSize: 'var(--fs-sm)', fontWeight: 650 }}>Sort by</span>
          </label>
          <select
            id="pf-sort"
            value={`${sort.sortBy}:${sort.direction}`}
            onChange={(e) => {
              const [sortByValue, direction] = e.target.value.split(':') as [PlatformSortBy, SortDirection];
              setSort({ sortBy: sortByValue, direction });
              setPage(0);
            }}
          >
            {SORT_OPTIONS.flatMap((o) => [
              <option key={`${o.value}:asc`} value={`${o.value}:asc`}>
                {o.label} (ascending)
              </option>,
              <option key={`${o.value}:desc`} value={`${o.value}:desc`}>
                {o.label} (descending)
              </option>,
            ])}
          </select>
        </div>
      </div>

      {list.isLoading ? (
        <div className="skeleton-stack" role="status" aria-label="Loading restaurants">
          <div className="skeleton" />
          <div className="skeleton" />
          <div className="skeleton" />
        </div>
      ) : list.error ? (
        <div>
          <ErrorMessage error={list.error} />
          <button className="btn btn-sm" onClick={() => void list.refetch()}>
            Try again
          </button>
        </div>
      ) : rows.length === 0 ? (
        <EmptyState icon="store">
          {q || effectiveStatus ? 'No restaurants match these filters.' : 'No restaurants have registered yet.'}
        </EmptyState>
      ) : (
        <div className="table-wrap pf-table-wrap" aria-busy={list.isFetching}>
          <table className="pf-table">
            <caption className="sr-only">Registered restaurants</caption>
            <thead>
              <tr>
                <SortHeader column="name" label="Restaurant Name" sort={sort} onSort={sortBy} />
                <th scope="col">Owner Name</th>
                <th scope="col">Email / Contact</th>
                <th scope="col">Location</th>
                <SortHeader column="createdAt" label="Registration Date" sort={sort} onSort={sortBy} />
                <SortHeader column="status" label="Approval Status" sort={sort} onSort={sortBy} />
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td data-label="Restaurant Name">
                    <strong>{r.name}</strong>
                  </td>
                  <td data-label="Owner Name">{r.ownerName}</td>
                  <td data-label="Email / Contact" className="pf-contact">
                    <div>{r.email || r.ownerEmail}</div>
                    {r.phone && <div className="muted">{r.phone}</div>}
                  </td>
                  <td data-label="Location">{formatLocation(r.city, r.state, r.country)}</td>
                  <td data-label="Registration Date">{formatDate(r.createdAt)}</td>
                  <td data-label="Approval Status">
                    <RestaurantStatusBadge status={r.status} />
                  </td>
                  <td data-label="Actions">
                    <span className="row-actions">
                      <Link className="btn btn-sm" to={`/platform-admin/restaurants/${r.id}`} aria-label={`View details of ${r.name}`}>
                        View Details
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

      {rows.length > 0 && (
        <nav className="pay-pager" aria-label="Pages">
          <button
            type="button"
            className="btn btn-sm"
            disabled={!list.data?.hasPrevious}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            Previous
          </button>
          <span className="muted">
            Page {(list.data?.page ?? 0) + 1} of {Math.max(1, list.data?.totalPages ?? 1)} &middot;{' '}
            {list.data?.totalItems ?? 0} restaurants
          </span>
          <button
            type="button"
            className="btn btn-sm"
            disabled={!list.data?.hasNext}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </nav>
      )}
    </>
  );
}

function SortHeader({
  column,
  label,
  sort,
  onSort,
}: {
  column: PlatformSortBy;
  label: string;
  sort: { sortBy: PlatformSortBy; direction: SortDirection };
  onSort: (c: PlatformSortBy) => void;
}) {
  const active = sort.sortBy === column;
  return (
    <th scope="col" aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button type="button" className="pf-sort" onClick={() => onSort(column)}>
        {label}
        <span aria-hidden="true">{active ? (sort.direction === 'asc' ? ' ▲' : ' ▼') : ' ⇅'}</span>
      </button>
    </th>
  );
}
