import { describe, expect, it } from 'vitest';
import { createSoundClaims, type LockManagerLike } from './soundClaims';

/** A lock manager shared by every "tab" of a test, like the browser's per-origin one. */
function sharedLocks() {
  const held = new Set<string>();
  const manager: LockManagerLike = {
    async request(name, _options, callback) {
      if (held.has(name)) return callback(null);
      held.add(name);
      try {
        await callback({ name });
      } finally {
        held.delete(name);
      }
    },
  };
  return { manager, held };
}

function manualClock() {
  const waiting: (() => void)[] = [];
  return {
    wait: () => new Promise<void>((resolve) => waiting.push(resolve)),
    release: () => waiting.splice(0).forEach((resolve) => resolve()),
  };
}

const settle = async () => {
  for (let i = 0; i < 6; i++) await Promise.resolve();
};

describe('sound claims across tabs', () => {
  it('gives one of two simultaneous tabs the sound, for each event', async () => {
    const { manager } = sharedLocks();
    const clock = manualClock();
    const tabA = createSoundClaims(manager, 1000, clock.wait);
    const tabB = createSoundClaims(manager, 1000, clock.wait);
    const results = await Promise.all([tabA.claim('r7:n41'), tabB.claim('r7:n41')]);
    expect(results.filter(Boolean)).toHaveLength(1);
    // The next event is independent: it can go to either tab, but still to exactly one.
    const next = await Promise.all([tabB.claim('r7:n42'), tabA.claim('r7:n42')]);
    expect(next.filter(Boolean)).toHaveLength(1);
  });

  it('splits three tabs and many events: every event is claimed exactly once', async () => {
    const { manager } = sharedLocks();
    const clock = manualClock();
    const tabs = [1, 2, 3].map(() => createSoundClaims(manager, 1000, clock.wait));
    for (let id = 1; id <= 20; id++) {
      const wins = await Promise.all(tabs.map((t) => t.claim(`r7:n${id}`)));
      expect(wins.filter(Boolean)).toHaveLength(1);
    }
  });

  it('keeps a late tab out while the winner holds the claim, and frees it afterwards', async () => {
    const { manager, held } = sharedLocks();
    const clock = manualClock();
    const tabA = createSoundClaims(manager, 1000, clock.wait);
    const tabB = createSoundClaims(manager, 1000, clock.wait);
    expect(await tabA.claim('r7:n1')).toBe(true);
    expect(await tabB.claim('r7:n1')).toBe(false); // arrives later, still refused
    clock.release();
    await settle();
    expect(held.size).toBe(0); // nothing is held for ever
  });

  it('lets the other tab claim future events when the first tab is gone', async () => {
    const { manager, held } = sharedLocks();
    const clock = manualClock();
    const tabB = createSoundClaims(manager, 1000, clock.wait);
    held.clear(); // tab A closed: the browser dropped its locks
    expect(await tabB.claim('r7:n9')).toBe(true);
  });

  it('does not mix restaurants or the platform feed', async () => {
    const { manager } = sharedLocks();
    const clock = manualClock();
    const tabA = createSoundClaims(manager, 1000, clock.wait);
    const tabB = createSoundClaims(manager, 1000, clock.wait);
    expect(await tabA.claim('r7:n1')).toBe(true);
    expect(await tabB.claim('r8:n1')).toBe(true); // same notification id, another restaurant
    expect(await tabB.claim('p:evt-1')).toBe(true);
  });

  it('plays in every tab when Web Locks is unavailable or fails', async () => {
    expect(await createSoundClaims(null).claim('r7:n1')).toBe(true);
    const broken: LockManagerLike = {
      request: () => Promise.reject(new Error('SecurityError')),
    };
    expect(await createSoundClaims(broken).claim('r7:n1')).toBe(true);
    const throwing: LockManagerLike = {
      request: () => {
        throw new Error('boom');
      },
    };
    expect(await createSoundClaims(throwing).claim('r7:n1')).toBe(true);
  });
});
