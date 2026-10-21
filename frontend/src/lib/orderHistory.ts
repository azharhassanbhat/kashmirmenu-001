/**
 * Orders placed from a table on this device. The backend has no "my orders" endpoint for
 * anonymous diners, so the order numbers it returns are remembered locally; the tracking
 * page always reads the live status from the server.
 */
export interface RememberedOrder {
  /** Readable label, e.g. ORD-14. Shown to the diner; not used to look the order up. */
  orderNumber: string;
  /** Key for the tracking URL. Absent for orders remembered before tokens existed. */
  token?: string;
  placedAt: string;
}

const MAX_REMEMBERED = 10;

/** Pre-redesign key holding only the latest order number; still read so older sessions keep working. */
export function lastOrderKey(qr: string) {
  return `kmenu.lastOrder.${qr}`;
}

function historyKey(qr: string) {
  return `kmenu.orders.${qr}`;
}

export function rememberedOrders(qr: string): RememberedOrder[] {
  try {
    const raw = localStorage.getItem(historyKey(qr));
    const list: RememberedOrder[] = raw ? JSON.parse(raw) : [];
    const legacy = localStorage.getItem(lastOrderKey(qr));
    if (legacy && !list.some((o) => o.orderNumber === legacy)) list.unshift({ orderNumber: legacy, placedAt: '' });
    return Array.isArray(list) ? list.filter((o) => typeof o?.orderNumber === 'string') : [];
  } catch {
    return [];
  }
}

export function rememberOrder(qr: string, orderNumber: string, token?: string): void {
  try {
    const rest = rememberedOrders(qr).filter((o) => o.orderNumber !== orderNumber);
    const next = [{ orderNumber, token, placedAt: new Date().toISOString() }, ...rest].slice(0, MAX_REMEMBERED);
    localStorage.setItem(historyKey(qr), JSON.stringify(next));
    localStorage.setItem(lastOrderKey(qr), orderNumber);
  } catch {
    // storage unavailable (private mode): the tracking URL still works
  }
}
