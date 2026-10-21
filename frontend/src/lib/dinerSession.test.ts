import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { clearStoredDiner, dinerTokenFor, getStoredDiner, storeDiner } from './dinerSession';

// The test environment is plain Node, which has no localStorage; stub it as the API client tests do.
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
});

describe('diner session storage', () => {
  it('remembers a guest per table', () => {
    storeDiner('qr-1', { token: 'tok-1', displayName: 'Rahul' });
    storeDiner('qr-2', { token: 'tok-2', displayName: 'Amit' });

    expect(getStoredDiner('qr-1')).toEqual({ token: 'tok-1', displayName: 'Rahul' });
    expect(dinerTokenFor('qr-2')).toBe('tok-2');
    expect(dinerTokenFor('qr-3')).toBeNull();
  });

  it('forgets a guest', () => {
    storeDiner('qr-1', { token: 'tok-1', displayName: 'Rahul' });
    clearStoredDiner('qr-1');
    expect(getStoredDiner('qr-1')).toBeNull();
  });

  it('ignores damaged storage instead of throwing', () => {
    store.set('kmenu.diner.qr-1', '{not json');
    expect(getStoredDiner('qr-1')).toBeNull();
    store.set('kmenu.diner.qr-1', JSON.stringify({ token: 5 }));
    expect(getStoredDiner('qr-1')).toBeNull();
  });

  it('copes with storage being unavailable', () => {
    vi.stubGlobal('localStorage', {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    });
    expect(getStoredDiner('qr-1')).toBeNull();
    expect(() => storeDiner('qr-1', { token: 't', displayName: 'n' })).not.toThrow();
    expect(() => clearStoredDiner('qr-1')).not.toThrow();
  });
});
