import { dinerTokenFor } from '../lib/dinerSession';

const BASE = import.meta.env.VITE_API_BASE_URL ?? '';
const TOKEN_KEY = 'kmenu.token';

export class ApiError extends Error {
  status: number;
  fieldErrors: { field: string; message: string }[];
  /** The server's machine-readable code, when it sent one (e.g. RESTAURANT_NOT_APPROVED). */
  code?: string;

  constructor(
    status: number,
    message: string,
    fieldErrors: { field: string; message: string }[] = [],
    code?: string,
  ) {
    super(message);
    this.status = status;
    this.fieldErrors = fieldErrors;
    this.code = code;
  }
}

/** Sent with a 403 when the caller's restaurant is pending, rejected or suspended. */
export const RESTAURANT_NOT_APPROVED = 'RESTAURANT_NOT_APPROVED';

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

export function setToken(token: string | null): void {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // storage unavailable (private mode); session simply won't persist
  }
}

const READ_TIMEOUT_MS = 10_000;
const WRITE_TIMEOUT_MS = 15_000;
const SLOW_WRITE_TIMEOUT_MS = 30_000;

let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler;
}

let onNotApproved: (() => void) | null = null;
/** Called when the server says the signed-in user's restaurant is not approved (any more). */
export function setNotApprovedHandler(handler: (() => void) | null): void {
  onNotApproved = handler;
}

// Some endpoints wrap payloads as { success, message, data }, others return the DTO directly.
function unwrap<T>(body: unknown): T {
  if (body && typeof body === 'object' && 'success' in body && 'data' in body) {
    return (body as { data: T }).data;
  }
  return body as T;
}

function messageFor(status: number, body: unknown): string {
  if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
    return body.message;
  }
  if (status === 401) return 'Your session has expired. Please sign in again.';
  if (status === 403) return 'You do not have permission to do that.';
  if (status === 429) return 'Too many requests. Please wait a moment and try again.';
  if (status >= 500) return 'Something went wrong on our side. Please try again.';
  // A 404 with no body means the route itself is missing, not that a record was absent: the
  // server is older than this app. Saying so beats "Request failed (404)", which sends people
  // looking for a bug in the feature rather than at the deployment.
  if (status === 404) return 'This server does not have that feature yet. It may need updating.';
  return `Request failed (${status})`;
}

/** Reads get the short budget; writes that do real work under a database lock get longer. */
function timeoutFor(method: string, path: string): number {
  if (method === 'GET') {
    // The CSV export is a read, but it assembles up to 50,000 rows server-side, so the ordinary
    // read budget would cut off exactly the large exports people care about.
    return path.includes('/export') ? SLOW_WRITE_TIMEOUT_MS : READ_TIMEOUT_MS;
  }
  // Placing an order locks the table, the order counter, stock and the coupon; collecting a payment
  // locks the order. Both are legitimately slower than a read under load, and failing them early
  // would be worse than waiting - the work may already have committed.
  if (
    path.includes('/orders') ||
    path.includes('/payments') ||
    path.includes('/settlements') ||
    path.includes('/collect') ||
    // A phone photo is several MB over a mobile connection, then decoded and shrunk on the server.
    path.startsWith('/api/v1/images')
  ) {
    return SLOW_WRITE_TIMEOUT_MS;
  }
  return WRITE_TIMEOUT_MS;
}

async function send(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<Response> {
  const headers: Record<string, string> = {};
  // A file upload is sent as a form: the browser must set the Content-Type itself, because it adds the
  // boundary that separates the parts. Setting it by hand, or JSON-encoding the form, breaks the upload.
  const isForm = typeof FormData !== 'undefined' && body instanceof FormData;
  if (body !== undefined && !isForm) headers['Content-Type'] = 'application/json';
  if (extraHeaders) Object.assign(headers, extraHeaders);
  const token = getToken();
  if (token && !path.startsWith('/api/public/')) headers.Authorization = `Bearer ${token}`;

  // Cart and order calls act as one guest at one table. The guest's token is sent for that table's
  // routes only, and never to anything else.
  const table = /^\/api\/public\/tables\/([^/?]+)/.exec(path);
  if (table) {
    const diner = dinerTokenFor(table[1]);
    if (diner) headers['X-Diner-Token'] = diner;
  }

  // fetch has no timeout of its own, so a connection that opens and then stalls never settles and
  // the caller's loading state spins for ever. That is the common failure on a phone moving between
  // cells, which is exactly the diner's situation.
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), timeoutFor(method, path));

  let res: Response;
  try {
    res = await fetch(`${BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : isForm ? (body as FormData) : JSON.stringify(body),
      signal: controller.signal,
    });
  } catch (error) {
    // An abort is this timeout firing, not a dead network; saying so stops people checking wifi
    // when the server is simply slow.
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new ApiError(0, 'The server took too long to respond. Please try again.');
    }
    throw new ApiError(0, 'Cannot reach the server. Check your connection and try again.');
  } finally {
    clearTimeout(timeout);
  }

  if (!res.ok) {
    let parsed: unknown = null;
    try {
      parsed = await res.json();
    } catch {
      // empty or non-JSON error body
    }
    if (res.status === 401 && token && !path.startsWith('/api/v1/auth/')) onUnauthorized?.();
    const code =
      parsed && typeof parsed === 'object' && 'code' in parsed && typeof parsed.code === 'string'
        ? parsed.code
        : undefined;
    if (res.status === 403 && code === RESTAURANT_NOT_APPROVED) onNotApproved?.();
    const errors =
      parsed && typeof parsed === 'object' && 'errors' in parsed && Array.isArray(parsed.errors)
        ? (parsed.errors as { field: string; message: string }[])
        : [];
    throw new ApiError(res.status, messageFor(res.status, parsed), errors, code);
  }
  return res;
}

export async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  extraHeaders?: Record<string, string>,
): Promise<T> {
  const res = await send(method, path, body, extraHeaders);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return text ? unwrap<T>(JSON.parse(text)) : (undefined as T);
}

export async function requestBlob(path: string): Promise<Blob> {
  const res = await send('GET', path);
  return res.blob();
}

export const http = {
  get: <T>(path: string, headers?: Record<string, string>) => request<T>('GET', path, undefined, headers),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) =>
    request<T>('POST', path, body ?? {}, headers),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, body ?? {}),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, body),
  delete: <T = void>(path: string) => request<T>('DELETE', path),
};
