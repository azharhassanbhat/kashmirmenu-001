import type { OrderStatus } from '../../api/types';
import type { SoundKind } from './soundDefs';

/*
 * When the diner's order page should chime. The page polls the order; only a CHANGE to a later status
 * counts. The first status seen is just the starting point, the same status never chimes twice, and a
 * refetch that returns what we already know is silent.
 */

const RANK: Record<OrderStatus, number> = {
  PENDING: 0,
  CONFIRMED: 1,
  PREPARING: 2,
  READY: 3,
  SERVED: 4,
  CANCELLED: 5,
};

export interface StatusChimeTracker {
  /** Feed every polled status in. True when this one deserves a chime. */
  observe: (status: OrderStatus) => boolean;
}

export function createStatusChimeTracker(): StatusChimeTracker {
  let last: OrderStatus | null = null;
  const chimed = new Set<OrderStatus>();
  return {
    observe(status) {
      const previous = last;
      last = status;
      if (previous === null) return false; // first load: nothing has changed yet
      if (status === previous || chimed.has(status)) return false;
      // CANCELLED is a change from anywhere; otherwise only forward progress counts.
      const later = status === 'CANCELLED' || RANK[status] > RANK[previous];
      if (!later || status === 'PENDING') return false;
      chimed.add(status);
      return true;
    },
  };
}

/** The guest-facing sound for a status; each step of the order sounds different. */
export function statusSound(status: OrderStatus): SoundKind | null {
  switch (status) {
    case 'CONFIRMED':
      return 'STATUS_ACCEPTED';
    case 'PREPARING':
      return 'STATUS_PREPARING';
    case 'READY':
      return 'ORDER_READY';
    case 'SERVED':
      return 'STATUS_SERVED';
    case 'CANCELLED':
      return 'ORDER_CANCELLED';
    default:
      return null;
  }
}
