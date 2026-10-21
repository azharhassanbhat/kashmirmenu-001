import { useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { serviceRequestApi } from '../../api';
import type { ServiceRequestStatus, StaffServiceRequest } from '../../api/types';
import { useAuth } from '../../auth/AuthContext';
import { BrandMark, EmptyState, ErrorMessage, QueryState } from '../../components/ui';
import { titleCase } from '../../lib/format';
import { nextStep, OVERDUE_AFTER_SECONDS, waitText } from '../../lib/serviceRequests';
import { useRestaurantTopic } from '../../lib/useRestaurantTopic';

const BOARD_KEY = ['service-requests-board'];

/**
 * The staff board for table-side requests. New ones arrive over the restaurant websocket topic, with
 * a short poll underneath as the fallback, so a dropped socket delays an update rather than losing it.
 * Waits are measured by the server and only extended locally between refreshes, so a tablet with the
 * wrong clock still shows the right time.
 */
export function ServiceRequestsPanel() {
  const { user } = useAuth();
  const queryClient = useQueryClient();
  const restaurantId = user?.restaurantId ?? null;

  const board = useQuery({
    queryKey: BOARD_KEY,
    queryFn: serviceRequestApi.board,
    enabled: restaurantId !== null,
    refetchInterval: 8_000,
  });

  const connected = useRestaurantTopic(restaurantId, (message) => {
    if (message.notificationType.startsWith('SERVICE_REQUEST')) {
      void queryClient.invalidateQueries({ queryKey: BOARD_KEY });
    }
  });

  // Re-render every few seconds so waits keep counting between refreshes.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 5_000);
    return () => clearInterval(t);
  }, []);

  const move = useMutation({
    mutationFn: (input: { id: number; status: ServiceRequestStatus }) =>
      serviceRequestApi.setStatus(input.id, input.status),
    // Success or failure, the server's view wins: a 409 means someone else already moved it.
    onSettled: () => void queryClient.invalidateQueries({ queryKey: BOARD_KEY }),
  });

  const all = board.data ?? [];
  const waiting = all.filter((r) => r.status === 'PENDING');
  const handling = all.filter((r) => r.status === 'ACKNOWLEDGED' || r.status === 'IN_PROGRESS');
  const done = all.filter((r) => r.status === 'COMPLETED' || r.status === 'CANCELLED');
  const elapsedSinceFetch = board.dataUpdatedAt ? Math.max(0, (now - board.dataUpdatedAt) / 1000) : 0;

  if (restaurantId === null) {
    return <EmptyState icon="bell">This account is not linked to a restaurant.</EmptyState>;
  }

  return (
    <div className="svc-board">
      <div className="svc-board-head">
        <h2>
          Table requests
          {waiting.length > 0 && <span className="svc-count">{waiting.length} waiting</span>}
        </h2>
        <span className={`svc-live${connected ? ' is-live' : ''}`} role="status">
          {connected ? 'Live' : 'Refreshing every few seconds'}
        </span>
      </div>

      {move.error && <ErrorMessage error={move.error} />}

      <QueryState isLoading={board.isLoading} error={board.error}>
        {all.length === 0 ? (
          <EmptyState icon="bell">No requests right now. New ones appear here the moment a guest asks.</EmptyState>
        ) : (
          <div className="svc-columns">
            <Column title="Waiting for staff" tone="alert" empty="Nothing waiting.">
              {waiting.map((r) => (
                <RequestCard key={r.id} request={r} extra={elapsedSinceFetch} busy={move.isPending}
                  onMove={(status) => move.mutate({ id: r.id, status })} />
              ))}
            </Column>
            <Column title="Being handled" tone="busy" empty="Nothing in progress.">
              {handling.map((r) => (
                <RequestCard key={r.id} request={r} extra={elapsedSinceFetch} busy={move.isPending}
                  onMove={(status) => move.mutate({ id: r.id, status })} />
              ))}
            </Column>
            <Column title="Done (last 2 hours)" tone="done" empty="Nothing finished yet.">
              {done.map((r) => (
                <RequestCard key={r.id} request={r} extra={0} busy={false} onMove={() => {}} />
              ))}
            </Column>
          </div>
        )}
      </QueryState>
    </div>
  );
}

function Column({
  title,
  tone,
  empty,
  children,
}: {
  title: string;
  tone: 'alert' | 'busy' | 'done';
  empty: string;
  children: ReactNode[];
}) {
  return (
    <section className={`svc-col svc-col-${tone}`} aria-label={title}>
      <h3>{title}</h3>
      {children.length === 0 ? <p className="muted svc-col-empty">{empty}</p> : <div className="svc-stack">{children}</div>}
    </section>
  );
}

function RequestCard({
  request: r,
  extra,
  busy,
  onMove,
}: {
  request: StaffServiceRequest;
  /** Seconds since the board was fetched, added to the server's measured wait for open requests. */
  extra: number;
  busy: boolean;
  onMove: (status: ServiceRequestStatus) => void;
}) {
  const open = r.status === 'PENDING' || r.status === 'ACKNOWLEDGED' || r.status === 'IN_PROGRESS';
  const waited = r.waitingSeconds + (open ? extra : 0);
  const overdue = r.status === 'PENDING' && waited >= OVERDUE_AFTER_SECONDS;
  const step = nextStep(r.status);

  return (
    <article className={`svc-card${r.urgent && open ? ' is-urgent' : ''}${overdue ? ' is-overdue' : ''}`}>
      <header>
        <strong className="svc-table">{r.tableName}</strong>
        <span className={`svc-wait${overdue ? ' is-overdue' : ''}`}>
          {open ? `Waiting ${waitText(waited)}` : titleCase(r.status)}
        </span>
      </header>
      <p className="svc-type">
        {r.typeLabel}
        {r.urgent && open && <span className="svc-flag">Priority</span>}
      </p>
      {r.note && <p className="svc-note">“{r.note}”</p>}
      <p className="muted svc-meta">
        {r.requestedBy ? `From ${r.requestedBy}` : 'From the table'}
        {r.assignedToName && ` · ${r.assignedToName} is on it`}
        {' · '}
        {r.requestNumber}
      </p>
      {open && (
        <div className="svc-actions">
          {step && (
            <button type="button" className="btn btn-primary btn-sm" disabled={busy} onClick={() => onMove(step.to)}>
              {step.label}
            </button>
          )}
          <button type="button" className="btn btn-sm" disabled={busy} onClick={() => onMove('CANCELLED')}>
            Cancel
          </button>
        </div>
      )}
      <History id={r.id} />
    </article>
  );
}

/** Who did what and when, loaded only when someone opens it. */
function History({ id }: { id: number }) {
  const [shown, setShown] = useState(false);
  const detail = useQuery({
    queryKey: ['service-request', id],
    queryFn: () => serviceRequestApi.get(id),
    enabled: shown,
  });
  return (
    <details className="svc-history" onToggle={(e) => setShown((e.currentTarget as HTMLDetailsElement).open)}>
      <summary>History</summary>
      {shown && detail.isLoading && <p className="muted">Loading…</p>}
      {detail.data && (
        <ol>
          {detail.data.history.map((h, i) => (
            <li key={i}>
              {titleCase(h.to)} <span className="muted">by {h.actor}</span>
            </li>
          ))}
        </ol>
      )}
    </details>
  );
}

/** Standalone page for every kind of staff: their own header, so waiters and cashiers need no admin console. */
export default function ServiceRequestsPage() {
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
          {user?.role !== 'CASHIER' && (
            <Link className="btn btn-sm" to="/kitchen">
              Kitchen
            </Link>
          )}
          <Link className="btn btn-sm" to="/floor">
            Live tables
          </Link>
          <button type="button" className="btn btn-sm" onClick={logout}>
            Sign out
          </button>
        </div>
      </header>
      <main className="floor-main">
        <ServiceRequestsPanel />
      </main>
    </div>
  );
}
