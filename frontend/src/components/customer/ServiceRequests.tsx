import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { publicServiceRequestApi } from '../../api';
import type { PublicServiceRequest, ServiceRequestType } from '../../api/types';
import { guestStatusText, isOpenRequest, REQUEST_CHOICES } from '../../lib/serviceRequests';
import { CIcon } from './CustomerIcon';
import { Sheet } from './Sheet';

/** How often an open sheet re-checks its requests. Diners have no live socket, so this is the update path. */
const POLL_MS = 6_000;

const OTHER_MAX = 200;

/** The "Need something?" sheet: ask for table service without placing an order, and follow each request. */
export function ServiceRequestSheet({ qr, onClose }: { qr: string; onClose: () => void }) {
  const queryClient = useQueryClient();
  const key = ['service-requests', qr];
  const [note, setNote] = useState('');
  const [feedback, setFeedback] = useState<string | null>(null);

  const requests = useQuery({
    queryKey: key,
    queryFn: () => publicServiceRequestApi.list(qr),
    refetchInterval: POLL_MS,
    retry: false,
  });

  const create = useMutation({
    mutationFn: (input: { type: ServiceRequestType; note?: string }) =>
      publicServiceRequestApi.create(qr, input.type, input.note),
    onSuccess: (result, input) => {
      setFeedback(
        result.alreadyRequested
          ? `${result.typeLabel} was already requested. Staff have it.`
          : `${result.typeLabel} requested. We'll be right with you.`,
      );
      if (input.type === 'OTHER') setNote('');
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onError: () => setFeedback(null),
  });

  const cancel = useMutation({
    mutationFn: (requestNumber: string) => publicServiceRequestApi.cancel(qr, requestNumber),
    onSuccess: () => {
      setFeedback('Request cancelled.');
      void queryClient.invalidateQueries({ queryKey: key });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: key }),
  });

  const all = requests.data ?? [];
  const open = all.filter((r) => isOpenRequest(r.status));
  const finished = all.filter((r) => !isOpenRequest(r.status)).slice(0, 4);
  const openByType = new Map<ServiceRequestType, PublicServiceRequest>(open.map((r) => [r.type, r]));
  const busy = create.isPending;
  const error = (create.error ?? cancel.error ?? requests.error) as Error | null;

  return (
    <Sheet title="Need something?" onClose={onClose}>
      <p className="km-sheet-note km-svc-intro">
        Ask for anything at your table. You don't need to place an order first.
      </p>

      <div className="km-svc-grid">
        {REQUEST_CHOICES.map((choice) => {
          const existing = openByType.get(choice.type);
          return (
            <button
              key={choice.type}
              type="button"
              className="km-svc-btn"
              // A request that is already open is shown as such, so a second tap is not even offered.
              disabled={busy || Boolean(existing)}
              aria-describedby={existing ? `km-svc-state-${choice.type}` : undefined}
              onClick={() => {
                setFeedback(null);
                create.mutate({ type: choice.type });
              }}
            >
              <CIcon name={choice.icon} size={22} />
              <span>{choice.label}</span>
              {existing && (
                <small id={`km-svc-state-${choice.type}`}>{guestStatusText(existing.status)}</small>
              )}
            </button>
          );
        })}
      </div>

      <form
        className="km-svc-other"
        onSubmit={(event) => {
          event.preventDefault();
          if (note.trim() && !busy) {
            setFeedback(null);
            create.mutate({ type: 'OTHER', note });
          }
        }}
      >
        <label htmlFor="km-svc-note">Something else</label>
        <textarea
          id="km-svc-note"
          value={note}
          maxLength={OTHER_MAX}
          rows={2}
          placeholder="Tell us what you need"
          onChange={(e) => setNote(e.target.value)}
        />
        <button type="submit" className="km-btn-fire km-btn-block" disabled={busy || !note.trim()}>
          {busy ? 'Sending…' : 'Send request'}
        </button>
      </form>

      <div role="status" aria-live="polite" className="km-svc-feedback">
        {feedback}
      </div>
      {error && (
        <p className="km-join-error" role="alert">
          {error.message}
        </p>
      )}

      {open.length > 0 && (
        <section aria-labelledby="km-svc-open">
          <h3 id="km-svc-open" className="km-svc-heading">
            Waiting
          </h3>
          <ul className="km-svc-list">
            {open.map((r) => (
              <RequestRow key={r.requestNumber} request={r} onCancel={() => cancel.mutate(r.requestNumber)} />
            ))}
          </ul>
        </section>
      )}

      {finished.length > 0 && (
        <section aria-labelledby="km-svc-done">
          <h3 id="km-svc-done" className="km-svc-heading">
            Recent
          </h3>
          <ul className="km-svc-list km-svc-list-done">
            {finished.map((r) => (
              <RequestRow key={r.requestNumber} request={r} />
            ))}
          </ul>
        </section>
      )}
    </Sheet>
  );
}

function RequestRow({ request, onCancel }: { request: PublicServiceRequest; onCancel?: () => void }) {
  return (
    <li className={`km-svc-item km-svc-${request.status.toLowerCase().replace('_', '-')}`}>
      <span>
        <strong>{request.typeLabel}</strong>
        {!request.mine && <small> · someone at your table</small>}
        {request.note && <small className="km-svc-note"> “{request.note}”</small>}
        <small className="km-svc-state">{guestStatusText(request.status)}</small>
      </span>
      {request.mine && request.status === 'PENDING' && onCancel && (
        <button type="button" className="km-link-btn" onClick={onCancel}>
          Cancel
        </button>
      )}
    </li>
  );
}
