import { useRef, useState, type FormEvent } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { platformAdminApi } from '../../api';
import { ApiError } from '../../api/client';
import type { RestaurantStatus } from '../../api/types';
import {
  ACTION_DONE,
  ACTION_LABELS,
  REASON_MAX,
  allowedActions,
  reasonProblem,
  statusBadgeClass,
  statusLabel,
  type DecisionAction,
} from '../../lib/platform';
import { ErrorMessage, Modal } from '../ui';
import { useNotice } from './Notice';

export function RestaurantStatusBadge({ status }: { status: RestaurantStatus }) {
  return <span className={`badge ${statusBadgeClass(status)}`}>{statusLabel(status)}</span>;
}

interface Target {
  id: number;
  name: string;
  status: RestaurantStatus;
}

/**
 * Buttons for the decisions valid from the restaurant's current status, and the dialog each opens.
 * Every success refreshes the stat cards and every list, so counts never lag behind a decision.
 */
export function RestaurantActions({ restaurant, small = true }: { restaurant: Target; small?: boolean }) {
  const queryClient = useQueryClient();
  const notice = useNotice();
  const [open, setOpen] = useState<DecisionAction | null>(null);
  // isPending only changes after a re-render, so clicks landing in the same moment (a double tap, a
  // key repeat) would each send a request: one succeeds and the rest come back as 409 errors. A ref
  // closes that gap synchronously.
  const inFlight = useRef(false);

  const decide = useMutation({
    mutationFn: ({ action, reason }: { action: DecisionAction; reason?: string }) => {
      const id = restaurant.id;
      switch (action) {
        case 'approve':
          return platformAdminApi.approve(id);
        case 'reactivate':
          return platformAdminApi.reactivate(id);
        case 'reject':
          return platformAdminApi.reject(id, reason ?? '');
        case 'suspend':
          return platformAdminApi.suspend(id, reason ?? '');
      }
    },
    onSuccess: (_data, { action }) => {
      notice.success(`${restaurant.name} ${ACTION_DONE[action]}.`);
      setOpen(null);
    },
    onError: (error) => {
      // 409: someone else decided first, or the status moved. Show the server's words and reload.
      if (error instanceof ApiError && error.status === 409) {
        notice.failure(error.message);
        setOpen(null);
      }
    },
    // Success or conflict, the data on screen may be stale: refresh the cards, lists and this record.
    onSettled: () => {
      inFlight.current = false;
      void queryClient.invalidateQueries({ queryKey: ['platform'] });
    },
  });

  const submit = (variables: { action: DecisionAction; reason?: string }) => {
    if (inFlight.current) return;
    inFlight.current = true;
    decide.mutate(variables);
  };

  const actions = allowedActions(restaurant.status);
  const sz = small ? ' btn-sm' : '';
  const label = (a: DecisionAction) => `${ACTION_LABELS[a]} ${restaurant.name}`;

  const close = () => {
    decide.reset();
    setOpen(null);
  };

  return (
    <>
      {actions.map((a) => (
        <button
          key={a}
          type="button"
          className={`btn${sz}${a === 'approve' || a === 'reactivate' ? ' btn-primary' : a === 'reject' || a === 'suspend' ? ' btn-danger' : ''}`}
          aria-label={label(a)}
          onClick={() => {
            decide.reset();
            setOpen(a);
          }}
        >
          {ACTION_LABELS[a]}
        </button>
      ))}

      {(open === 'approve' || open === 'reactivate') && (
        <ConfirmDialog
          action={open}
          name={restaurant.name}
          busy={decide.isPending}
          error={decide.error}
          onClose={close}
          onConfirm={() => submit({ action: open })}
        />
      )}
      {(open === 'reject' || open === 'suspend') && (
        <ReasonDialog
          action={open}
          name={restaurant.name}
          busy={decide.isPending}
          error={decide.error}
          onClose={close}
          onSubmit={(reason) => submit({ action: open, reason })}
        />
      )}
    </>
  );
}

const CONFIRM_TEXT: Record<'approve' | 'reactivate', string> = {
  approve: 'will be able to sign in and start running their restaurant straight away.',
  reactivate: 'will get access to their restaurant again straight away.',
};

function ConfirmDialog({
  action,
  name,
  busy,
  error,
  onClose,
  onConfirm,
}: {
  action: 'approve' | 'reactivate';
  name: string;
  busy: boolean;
  error: unknown;
  onClose: () => void;
  onConfirm: () => void;
}) {
  return (
    <Modal title={`${ACTION_LABELS[action]} restaurant`} onClose={onClose}>
      <p>
        {ACTION_LABELS[action]} <strong>{name}</strong>? Its owner and staff {CONFIRM_TEXT[action]}
      </p>
      <ErrorMessage error={error} />
      <div className="actions">
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
        <button type="button" className="btn btn-primary" disabled={busy} onClick={onConfirm}>
          {busy ? 'Working…' : ACTION_LABELS[action]}
        </button>
      </div>
    </Modal>
  );
}

const REASON_HELP: Record<'reject' | 'suspend', string> = {
  reject: 'The owner will see this reason when they sign in.',
  suspend: 'The owner will see this reason, and lose access until the restaurant is reactivated.',
};

function ReasonDialog({
  action,
  name,
  busy,
  error,
  onClose,
  onSubmit,
}: {
  action: 'reject' | 'suspend';
  name: string;
  busy: boolean;
  error: unknown;
  onClose: () => void;
  onSubmit: (reason: string) => void;
}) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  const problem = reasonProblem(reason);
  const showProblem = touched && problem !== null;
  const id = `reason-${action}`;

  function submit(e: FormEvent) {
    e.preventDefault();
    setTouched(true);
    if (problem === null && !busy) onSubmit(reason.trim());
  }

  return (
    <Modal title={`${ACTION_LABELS[action]} restaurant`} onClose={onClose}>
      <form onSubmit={submit} noValidate>
        <p>
          {ACTION_LABELS[action]} <strong>{name}</strong>. {REASON_HELP[action]}
        </p>
        <div className="field">
          <label htmlFor={id}>
            <span style={{ fontSize: 'var(--fs-sm)', fontWeight: 650 }}>Reason (required)</span>
          </label>
          <textarea
            id={id}
            rows={4}
            value={reason}
            maxLength={REASON_MAX + 100}
            aria-required="true"
            aria-invalid={showProblem}
            aria-describedby={`${id}-help`}
            onChange={(e) => setReason(e.target.value)}
            onBlur={() => setTouched(true)}
          />
          <small id={`${id}-help`} className={showProblem ? 'field-error' : 'muted'}>
            <span role={showProblem ? 'alert' : undefined}>{showProblem ? problem : '5 to 500 characters.'}</span>{' '}
            <span aria-live="off" className="pf-counter">
              {reason.trim().length}/{REASON_MAX}
            </span>
          </small>
        </div>
        <ErrorMessage error={error} />
        <div className="actions">
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-danger" disabled={busy || (touched && problem !== null)}>
            {busy ? 'Working…' : ACTION_LABELS[action]}
          </button>
        </div>
      </form>
    </Modal>
  );
}
