import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const store = new Map<string, string>();

beforeEach(() => {
  store.clear();
  vi.stubGlobal('localStorage', {
    getItem: (k: string) => store.get(k) ?? null,
    setItem: (k: string, v: string) => void store.set(k, v),
    removeItem: (k: string) => void store.delete(k),
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetModules();
});

function mockFetch(status: number, body: unknown) {
  const fn = vi.fn().mockImplementation(async () =>
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { 'Content-Type': 'application/json' },
    }),
  );
  vi.stubGlobal('fetch', fn);
  return fn;
}

describe('api client', () => {
  it('unwraps ApiResponse envelopes', async () => {
    mockFetch(200, { success: true, message: 'ok', data: { id: 1 } });
    const { http } = await import('./client');
    expect(await http.get('/api/categories/1')).toEqual({ id: 1 });
  });

  it('returns raw DTOs untouched', async () => {
    mockFetch(200, { orderNumber: 'ABC', status: 'PENDING' });
    const { http } = await import('./client');
    expect(await http.get('/api/public/orders/ABC')).toEqual({ orderNumber: 'ABC', status: 'PENDING' });
  });

  it('sends the bearer token on private routes only', async () => {
    const fetchMock = mockFetch(200, {});
    const { http, setToken } = await import('./client');
    setToken('tok');
    await http.get('/api/v1/me');
    await http.get('/api/public/tables/x');
    const [, privateInit] = fetchMock.mock.calls[0];
    const [, publicInit] = fetchMock.mock.calls[1];
    expect(privateInit.headers.Authorization).toBe('Bearer tok');
    expect(publicInit.headers.Authorization).toBeUndefined();
  });

  it("sends a guest's token on that table's routes only", async () => {
    const fetchMock = mockFetch(200, {});
    const { http } = await import('./client');
    const { storeDiner, clearStoredDiner } = await import('../lib/dinerSession');
    storeDiner('qr-1', { token: 'guest-tok', displayName: 'Rahul' });
    try {
      await http.get('/api/public/tables/qr-1/cart');
      await http.get('/api/public/tables/qr-2/cart');
      await http.get('/api/public/orders/by-token/abc');
      expect(fetchMock.mock.calls[0][1].headers['X-Diner-Token']).toBe('guest-tok');
      expect(fetchMock.mock.calls[1][1].headers['X-Diner-Token']).toBeUndefined();
      expect(fetchMock.mock.calls[2][1].headers['X-Diner-Token']).toBeUndefined();
    } finally {
      clearStoredDiner('qr-1');
    }
  });

  it('sends extra headers on a request, such as an Idempotency-Key', async () => {
    const fetchMock = mockFetch(200, {});
    const { http } = await import('./client');
    await http.post('/api/public/tables/qr-1/settlements', { kind: 'SEPARATE' }, { 'Idempotency-Key': 'attempt-1' });
    await http.post('/api/public/tables/qr-1/join', { name: 'Rahul' });
    expect(fetchMock.mock.calls[0][1].headers['Idempotency-Key']).toBe('attempt-1');
    expect(fetchMock.mock.calls[0][1].headers['Content-Type']).toBe('application/json');
    expect(fetchMock.mock.calls[1][1].headers['Idempotency-Key']).toBeUndefined();
  });

  it('raises ApiError with field errors and triggers the 401 handler', async () => {
    mockFetch(401, { success: false, message: 'Bad token', errors: [{ field: 'x', message: 'y' }] });
    const { http, setToken, setUnauthorizedHandler, ApiError } = await import('./client');
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    setToken('tok');
    const err = (await http.get('/api/v1/me').catch((e: unknown) => e)) as InstanceType<typeof ApiError>;
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(401);
    expect(err.message).toBe('Bad token');
    expect(err.fieldErrors).toEqual([{ field: 'x', message: 'y' }]);
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });

  it('does not log out on a failed login', async () => {
    mockFetch(401, { success: false, message: 'Invalid credentials' });
    const { http, setToken, setUnauthorizedHandler } = await import('./client');
    const onUnauthorized = vi.fn();
    setUnauthorizedHandler(onUnauthorized);
    setToken('tok');
    await http.post('/api/v1/auth/login', {}).catch(() => undefined);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('keeps a server-supplied 404 message and explains a bare one', async () => {
    // A 404 carrying a message means a record was not found, and that message is the useful one.
    mockFetch(404, { success: false, message: 'Order not found.' });
    const { http } = await import('./client');
    await expect(http.get('/api/public/orders/NOPE')).rejects.toThrow('Order not found.');

    // A 404 with no body means the route itself is missing, i.e. the server is out of date.
    vi.resetModules();
    mockFetch(404, undefined);
    const { http: http2 } = await import('./client');
    await expect(http2.post('/api/public/tables/x/cart/coupon', { couponCode: 'WELCOME' }))
      .rejects.toThrow(/does not have that feature yet/);
  });

  it('reports network failures clearly', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('failed')));
    const { http } = await import('./client');
    await expect(http.get('/api/v1/me')).rejects.toThrow(/Cannot reach the server/);
  });
});

describe('request timeouts', () => {
  it('fails a stalled request instead of hanging for ever', async () => {
    // A connection that opens and never settles is the common phone-network failure, and fetch has
    // no timeout of its own: before this, the caller's spinner spun indefinitely.
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () =>
          reject(new DOMException('aborted', 'AbortError')));
      })));

    const { http } = await import('./client');
    const pending = http.get('/api/v1/me');
    const assertion = expect(pending).rejects.toThrow(/took too long/);
    await vi.advanceTimersByTimeAsync(11_000);
    await assertion;

    vi.useRealTimers();
  });

  it('gives order placement a longer budget than a read', async () => {
    // Placing an order locks the table, the counter, stock and the coupon; cutting it off at the
    // read budget would abandon work that may already have committed.
    vi.useFakeTimers();
    const aborted: string[] = [];
    vi.stubGlobal('fetch', vi.fn((url: string, init?: RequestInit) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          aborted.push(url);
          reject(new DOMException('aborted', 'AbortError'));
        });
      })));

    const { http } = await import('./client');
    const pending = http.post('/api/public/tables/abc/orders', {});
    const assertion = expect(pending).rejects.toThrow(/took too long/);

    await vi.advanceTimersByTimeAsync(11_000);
    expect(aborted).toHaveLength(0);          // still waiting past the read budget
    await vi.advanceTimersByTimeAsync(20_000);
    await assertion;

    vi.useRealTimers();
  });
});
