/**
 * The guest's place at a table, kept on this device.
 *
 * Scanning a table's QR code and entering a name gives the guest a secret token. It is the only
 * thing that identifies them to the server, so it stays on this phone, per table, and is sent with
 * every cart and order request. It is never put in a URL.
 */
export interface StoredDiner {
  token: string;
  displayName: string;
}

const key = (qr: string) => `kmenu.diner.${qr}`;

export function getStoredDiner(qr: string): StoredDiner | null {
  try {
    const raw = localStorage.getItem(key(qr));
    if (!raw) return null;
    const parsed: unknown = JSON.parse(raw);
    if (
      parsed &&
      typeof parsed === 'object' &&
      typeof (parsed as StoredDiner).token === 'string' &&
      typeof (parsed as StoredDiner).displayName === 'string'
    ) {
      return parsed as StoredDiner;
    }
    return null;
  } catch {
    return null;
  }
}

export function storeDiner(qr: string, diner: StoredDiner): void {
  try {
    localStorage.setItem(key(qr), JSON.stringify(diner));
  } catch {
    // storage unavailable (private mode): the guest simply has to enter their name again next visit
  }
}

export function clearStoredDiner(qr: string): void {
  try {
    localStorage.removeItem(key(qr));
  } catch {
    // nothing to clear
  }
}

/** Just the token, for the API client. */
export function dinerTokenFor(qr: string): string | null {
  return getStoredDiner(qr)?.token ?? null;
}
