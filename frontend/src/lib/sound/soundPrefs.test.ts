import { describe, expect, it } from 'vitest';
import {
  DEFAULT_PREFS,
  GUEST_DEFAULT_PREFS,
  audible,
  clampVolume,
  effective,
  loadPrefs,
  prefsKey,
  sanitizePrefs,
  savePrefs,
  type StorageLike,
} from './soundPrefs';

function memoryStorage(initial: Record<string, string> = {}): StorageLike {
  const data = { ...initial };
  return {
    getItem: (k) => (k in data ? data[k] : null),
    setItem: (k, v) => {
      data[k] = v;
    },
  };
}

describe('sound preferences', () => {
  it('defaults to everything on at volume 70', () => {
    expect(DEFAULT_PREFS).toEqual({
      master: true,
      newOrder: true,
      waiterCalls: true,
      billRequests: true,
      orderReady: true,
      payments: true,
      cancellations: true,
      messages: true,
      general: true,
      volume: 70,
      theme: 'bell',
      reminderSec: 60,
    });
  });

  it('keys the record per user', () => {
    expect(prefsKey(42)).toBe('kmenu.sound.v1.42');
    expect(prefsKey('guest')).toBe('kmenu.sound.v1.guest');
  });

  it('uses the defaults when nothing is stored', () => {
    expect(loadPrefs(memoryStorage(), prefsKey(1))).toEqual(DEFAULT_PREFS);
    expect(loadPrefs(null, prefsKey(1))).toEqual(DEFAULT_PREFS);
  });

  it('falls back to the defaults for corrupt storage', () => {
    for (const bad of ['{not json', 'null', '[]', '"x"', '42', '']) {
      expect(loadPrefs(memoryStorage({ k: bad }), 'k')).toEqual(DEFAULT_PREFS);
    }
    const throwing: StorageLike = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => undefined,
    };
    expect(loadPrefs(throwing, 'k')).toEqual(DEFAULT_PREFS);
  });

  it('validates field by field, keeping what is good', () => {
    const prefs = sanitizePrefs({ master: false, newOrder: 'yes', payments: 0, volume: 'loud', general: false, extra: 1 });
    expect(prefs).toEqual({ ...DEFAULT_PREFS, master: false, general: false });
  });

  it('clamps and rounds the volume', () => {
    expect(clampVolume(150)).toBe(100);
    expect(clampVolume(-5)).toBe(0);
    expect(clampVolume(33.6)).toBe(34);
    expect(clampVolume(Number.NaN)).toBe(70);
    expect(clampVolume(Infinity)).toBe(70);
    expect(clampVolume('50')).toBe(70);
    expect(sanitizePrefs({ volume: 900 }).volume).toBe(100);
  });

  it('round-trips through storage', () => {
    const storage = memoryStorage();
    const prefs = { ...DEFAULT_PREFS, master: false, volume: 25, messages: false };
    expect(savePrefs(storage, 'k', prefs)).toBe(true);
    expect(loadPrefs(storage, 'k')).toEqual(prefs);
  });

  it('reports a refused save instead of throwing', () => {
    const full: StorageLike = {
      getItem: () => null,
      setItem: () => {
        throw new Error('quota');
      },
    };
    expect(savePrefs(full, 'k', DEFAULT_PREFS)).toBe(false);
    expect(savePrefs(null, 'k', DEFAULT_PREFS)).toBe(false);
  });

  it('lets the master switch override every group, critical included', () => {
    const off = { ...DEFAULT_PREFS, master: false };
    for (const g of ['newOrder', 'payments', 'cancellations', 'messages', 'general', 'critical'] as const) {
      expect(effective(off, g)).toBe(false);
      expect(effective(DEFAULT_PREFS, g)).toBe(true);
    }
  });

  it('switches groups independently', () => {
    const prefs = { ...DEFAULT_PREFS, payments: false };
    expect(effective(prefs, 'payments')).toBe(false);
    expect(effective(prefs, 'newOrder')).toBe(true);
  });

  it('makes critical follow the master only', () => {
    const allGroupsOff = {
      ...DEFAULT_PREFS,
      newOrder: false,
      payments: false,
      cancellations: false,
      messages: false,
      general: false,
    };
    expect(effective(allGroupsOff, 'critical')).toBe(true);
  });

  it('is not audible at volume 0', () => {
    expect(audible({ ...DEFAULT_PREFS, volume: 0 }, 'newOrder')).toBe(false);
    expect(audible(DEFAULT_PREFS, 'newOrder')).toBe(true);
  });

  it('starts diners with sound off', () => {
    expect(GUEST_DEFAULT_PREFS.master).toBe(false);
    expect(loadPrefs(memoryStorage(), 'kmenu.sound.v1.guest', GUEST_DEFAULT_PREFS).master).toBe(false);
  });
});
