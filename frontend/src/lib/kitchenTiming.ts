/** How the kitchen board words an order's timing. The server decides what is late; this only words it. */

export interface Timing {
  elapsedMinutes?: number;
  prepTargetMinutes?: number;
  delayed?: boolean;
  overdueMinutes?: number;
}

/** "12 of 15 min": time open against the target. Empty when the server did not say. */
export function timingLine(t: Timing): string {
  if (t.elapsedMinutes === undefined || t.prepTargetMinutes === undefined) return '';
  return `${t.elapsedMinutes} of ${t.prepTargetMinutes} min`;
}

/** "Delayed 7 min", or null when the order is not late. */
export function delayText(t: Timing): string | null {
  if (!t.delayed) return null;
  const over = t.overdueMinutes ?? 0;
  return over > 0 ? `Delayed ${over} min` : 'Delayed';
}
