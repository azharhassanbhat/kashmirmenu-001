import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { feedbackApi } from '../../api';
import { EmptyState, ErrorMessage, QueryState } from '../../components/ui';
import { averageText, barPercent, ratingsText, ratingWord, stars } from '../../lib/feedback';
import { parseServerTime } from '../../lib/format';

const PERIODS = [
  { days: 7, label: 'Last 7 days' },
  { days: 30, label: 'Last 30 days' },
  { days: 90, label: 'Last 90 days' },
  { days: 365, label: 'Last year' },
];

/**
 * What guests said about their meals. Private to the restaurant: guests rate once their order is served and
 * are told only the restaurant sees it. Nothing about the guest is stored, so none is shown here.
 */
export default function FeedbackPage() {
  const [days, setDays] = useState(30);
  const [lowOnly, setLowOnly] = useState(false);
  const [page, setPage] = useState(0);

  const summary = useQuery({ queryKey: ['feedback-summary', days], queryFn: () => feedbackApi.summary(days) });
  const list = useQuery({
    queryKey: ['feedback', lowOnly, page],
    queryFn: () => feedbackApi.list({ maxRating: lowOnly ? 2 : undefined, page, size: 20 }),
    placeholderData: (previous) => previous,
  });

  const s = summary.data;
  const rows = list.data?.items ?? [];

  return (
    <>
      <div className="page-head">
        <h2>Customer feedback</h2>
        <select aria-label="Period" value={days} onChange={(e) => setDays(Number(e.target.value))}>
          {PERIODS.map((p) => (
            <option key={p.days} value={p.days}>
              {p.label}
            </option>
          ))}
        </select>
      </div>
      <p className="muted">These are ratings guests give for served orders.</p>

      <QueryState isLoading={summary.isLoading} error={summary.error}>
        {s && (
          <div className="card">
            <div className="fb-summary">
              <div className="fb-average">
                <strong>{averageText(s.average)}</strong>
                <span className="muted">out of 5</span>
                <span className="fb-stars" aria-hidden="true">
                  {s.average === null ? '' : stars(s.average)}
                </span>
                <span className="muted">{ratingsText(s.count)}</span>
              </div>
              <div className="fb-bars" role="list" aria-label="How many ratings of each size">
                {[5, 4, 3, 2, 1].map((n) => (
                  <div className="fb-bar" role="listitem" key={n}>
                    <span>
                      {n} <small className="muted">★</small>
                    </span>
                    <span className="fb-track">
                      <span className="fb-fill" style={{ width: `${barPercent(s.distribution[n - 1], s.distribution)}%` }} />
                    </span>
                    <span className="num">{s.distribution[n - 1]}</span>
                  </div>
                ))}
              </div>
            </div>
            {s.count > 0 && (
              <p className={s.lowPercent >= 20 ? 'fb-note is-warn' : 'fb-note muted'}>
                {s.lowCount} of {s.count} ({s.lowPercent}%) were 1 or 2 stars.
              </p>
            )}
          </div>
        )}
      </QueryState>

      <div className="card">
        <div className="page-head" style={{ marginBottom: 'var(--space-3)' }}>
          <h3 style={{ margin: 0 }}>Comments and ratings</h3>
          <label className="check">
            <input
              type="checkbox"
              checked={lowOnly}
              onChange={(e) => {
                setLowOnly(e.target.checked);
                setPage(0);
              }}
            />{' '}
            Low ratings only (1 or 2 stars)
          </label>
        </div>
        <ErrorMessage error={list.error} />
        {list.isLoading ? (
          <p className="muted">Loading…</p>
        ) : rows.length === 0 ? (
          <EmptyState icon="star">
            {lowOnly
              ? 'No low ratings. That is good news.'
              : 'No feedback yet. Guests are asked how their meal was once it has been served.'}
          </EmptyState>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>When</th>
                  <th>Order</th>
                  <th>Table</th>
                  <th>Rating</th>
                  <th>Comment</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((f) => (
                  <tr key={f.id}>
                    <td data-label="When">
                      {parseServerTime(f.createdAt)?.toLocaleString('en-IN', {
                        day: 'numeric',
                        month: 'short',
                        hour: 'numeric',
                        minute: '2-digit',
                      }) ?? ''}
                    </td>
                    <td data-label="Order">{f.orderNumber}</td>
                    <td data-label="Table">{f.tableName ?? '—'}</td>
                    <td data-label="Rating">
                      <span className={f.rating <= 2 ? 'fb-stars is-low' : 'fb-stars'} title={ratingWord(f.rating)}>
                        {stars(f.rating)}
                      </span>
                    </td>
                    <td data-label="Comment">{f.comment ?? <span className="muted">No comment</span>}</td>
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
              Page {(list.data?.page ?? 0) + 1} of {Math.max(1, list.data?.totalPages ?? 1)} · {list.data?.totalItems ?? 0}{' '}
              {list.data?.totalItems === 1 ? 'entry' : 'entries'}
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
      </div>
    </>
  );
}
