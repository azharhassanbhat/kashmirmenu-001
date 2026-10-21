import { useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { publicApi } from '../../api';
import { ApiError } from '../../api/client';
import type { PaymentMethod, SettlementKind } from '../../api/types';
import { newIdempotencyKey, pendingSettlement } from '../../lib/payment';

/** Settlements are checked quickly while one is waiting for staff, and rarely otherwise. */
const WAITING_POLL_MS = 4_000;
const IDLE_POLL_MS = 15_000;
const BILL_POLL_MS = 10_000;

interface Attempt {
  key: string;
  kind: SettlementKind;
  method: PaymentMethod;
}

/**
 * Paying at a table: the guest's own bill, the table's bill, and the payments they have started.
 * Every figure comes from the server; the browser only says which option and which method.
 *
 * Starting a payment sends an Idempotency-Key. The key is kept across failed attempts that might have
 * reached the server (a dropped connection, a timeout, a 5xx), so tapping again replays the SAME
 * attempt and cannot take the money twice. It is dropped once the server has given a definite answer,
 * and whenever the guest changes what they are asking for.
 */
export function usePayment(qr: string, enabled: boolean) {
  const queryClient = useQueryClient();
  const attempt = useRef<Attempt | null>(null);

  const bill = useQuery({
    queryKey: ['public-bill', qr],
    queryFn: () => publicApi.bill(qr),
    enabled,
    retry: false,
    refetchInterval: BILL_POLL_MS,
  });
  const tableBill = useQuery({
    queryKey: ['public-table-bill', qr],
    queryFn: () => publicApi.tableBill(qr),
    enabled,
    retry: false,
    refetchInterval: BILL_POLL_MS,
  });
  const settlements = useQuery({
    queryKey: ['public-settlements', qr],
    queryFn: () => publicApi.settlements(qr),
    enabled,
    retry: false,
    refetchInterval: (query) => (pendingSettlement(query.state.data) ? WAITING_POLL_MS : IDLE_POLL_MS),
  });

  const refreshAll = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['public-bill', qr] }),
      queryClient.invalidateQueries({ queryKey: ['public-table-bill', qr] }),
      queryClient.invalidateQueries({ queryKey: ['public-settlements', qr] }),
    ]);

  const start = useMutation({
    mutationFn: (choice: { kind: SettlementKind; method: PaymentMethod }) => {
      const current = attempt.current;
      // The same key only for the same request: a different choice is a different payment.
      if (!current || current.kind !== choice.kind || current.method !== choice.method) {
        attempt.current = { key: newIdempotencyKey(), kind: choice.kind, method: choice.method };
      }
      return publicApi.settle(qr, choice.kind, choice.method, attempt.current!.key);
    },
    onSuccess: () => {
      attempt.current = null;
      return refreshAll();
    },
    onError: (error) => {
      // A 4xx is a definite answer (nothing owed, method not allowed, ...): the attempt is over, and
      // the bills may have moved. A dropped connection, a timeout or a 5xx may or may not have reached
      // the server, so the key is kept and the next tap replays the same attempt safely.
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
        attempt.current = null;
        void refreshAll();
      }
    },
  });

  // UPI: one tap starts the payment (the server works out the amount) and then tells the restaurant the
  // guest has paid. Both are claims for staff to verify; nothing becomes paid here. If the first call
  // succeeds and the second fails, the settlement exists and the waiting screen offers the claim again.
  const startAndClaim = useMutation({
    mutationFn: async (choice: { kind: SettlementKind; reference: string }) => {
      const current = attempt.current;
      if (!current || current.kind !== choice.kind || current.method !== 'UPI') {
        attempt.current = { key: newIdempotencyKey(), kind: choice.kind, method: 'UPI' };
      }
      const settlement = await publicApi.settle(qr, choice.kind, 'UPI', attempt.current!.key);
      attempt.current = null;
      try {
        return await publicApi.claimPaid(qr, settlement.code, choice.reference);
      } finally {
        await refreshAll();
      }
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status >= 400 && error.status < 500) {
        attempt.current = null;
        void refreshAll();
      }
    },
  });

  const claim = useMutation({
    mutationFn: (v: { code: string; reference: string }) => publicApi.claimPaid(qr, v.code, v.reference),
    onSuccess: () => refreshAll(),
  });

  const cancel = useMutation({
    mutationFn: (code: string) => publicApi.cancelSettlement(qr, code),
    onSuccess: () => refreshAll(),
  });

  return { bill, tableBill, settlements, start, startAndClaim, claim, cancel, refreshAll };
}
