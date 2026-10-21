import type { ServiceRequestStatus, ServiceRequestType } from '../api/types';
import type { CustomerIconName } from '../components/customer/CustomerIcon';

/** The things a guest can ask for, in the order they are offered. Labels match the server's. */
export const REQUEST_CHOICES: { type: ServiceRequestType; label: string; icon: CustomerIconName }[] = [
  { type: 'CALL_WAITER', label: 'Call waiter', icon: 'bell' },
  { type: 'DRINKING_WATER', label: 'Drinking water', icon: 'cup' },
  { type: 'REQUEST_BILL', label: 'Bring the bill', icon: 'bill' },
  { type: 'CUTLERY', label: 'Cutlery', icon: 'skewer' },
  { type: 'NAPKINS', label: 'Tissues / napkins', icon: 'note' },
  { type: 'EXTRA_PLATES', label: 'Extra plates', icon: 'plate' },
  { type: 'CLEANING', label: 'Clean the table', icon: 'table' },
  { type: 'ASSISTANCE', label: 'Assistance', icon: 'info' },
];

/** What a guest is told about each status. Never claims more than the server has recorded. */
export function guestStatusText(status: ServiceRequestStatus): string {
  switch (status) {
    case 'PENDING':
      return 'Sent. Waiting for staff to see it';
    case 'ACKNOWLEDGED':
      return 'Staff have seen your request';
    case 'IN_PROGRESS':
      return 'On its way';
    case 'COMPLETED':
      return 'Done';
    case 'CANCELLED':
      return 'Cancelled';
  }
}

export const isOpenRequest = (status: ServiceRequestStatus) =>
  status === 'PENDING' || status === 'ACKNOWLEDGED' || status === 'IN_PROGRESS';

/** "4 min", "1h 05m": a wait for the staff board. */
export function waitText(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  if (s < 60) return 'under 1 min';
  const minutes = Math.floor(s / 60);
  if (minutes < 60) return `${minutes} min`;
  return `${Math.floor(minutes / 60)}h ${String(minutes % 60).padStart(2, '0')}m`;
}

/** A request waiting this long without being picked up is shown as overdue. */
export const OVERDUE_AFTER_SECONDS = 180;

/** The next step a member of staff can take, with the label for the button. */
export function nextStep(status: ServiceRequestStatus): { to: ServiceRequestStatus; label: string } | null {
  switch (status) {
    case 'PENDING':
      return { to: 'ACKNOWLEDGED', label: 'Acknowledge' };
    case 'ACKNOWLEDGED':
      return { to: 'IN_PROGRESS', label: 'Start' };
    case 'IN_PROGRESS':
      return { to: 'COMPLETED', label: 'Complete' };
    default:
      return null;
  }
}
