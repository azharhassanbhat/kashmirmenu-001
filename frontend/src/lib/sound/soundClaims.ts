/*
 * Two tabs of one browser that are signed in to the same restaurant both receive every live event, and both
 * would chime. Each event gets a claim instead: the first tab to ask for it wins and plays, the others stay
 * silent (they still show the toast and refresh their data).
 *
 * The claim is a Web Lock named after the event, requested with `ifAvailable`, so it is atomic across tabs and
 * answers at once (no waiting window, nothing to race on). The winner holds the lock for a short time, long
 * enough that a slower tab cannot claim the same event after it was released, then lets it go. The browser
 * also releases a lock when its tab closes, so a closed tab can never block later sounds.
 *
 * Without the Web Locks API (or if it throws) every tab plays, as it did before: a repeated chime is better
 * than a missed one.
 */

export interface LockManagerLike {
  request: (
    name: string,
    options: { ifAvailable: boolean },
    callback: (lock: unknown) => Promise<void> | void,
  ) => Promise<unknown>;
}

/** Long enough to cover the scheduler's collapse window and gap, short enough that locks never pile up. */
export const CLAIM_HOLD_MS = 8_000;

export interface SoundClaims {
  /** True when this tab should play the sound for `key` (it is the first to ask, or coordination is unavailable). */
  claim: (key: string) => Promise<boolean>;
}

export function createSoundClaims(
  locks: LockManagerLike | null,
  holdMs: number = CLAIM_HOLD_MS,
  wait: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
): SoundClaims {
  return {
    claim(key) {
      if (locks === null) return Promise.resolve(true);
      return new Promise<boolean>((resolve) => {
        try {
          void locks
            .request(`kmenu.sound.${key}`, { ifAvailable: true }, (lock) => {
              if (lock === null || lock === undefined) {
                resolve(false); // another tab already claimed this event
                return;
              }
              resolve(true);
              return wait(holdMs);
            })
            .catch(() => resolve(true));
        } catch {
          resolve(true);
        }
      });
    },
  };
}

export function browserLocks(): LockManagerLike | null {
  try {
    if (typeof navigator === 'undefined') return null;
    const locks = (navigator as unknown as { locks?: LockManagerLike }).locks;
    return locks && typeof locks.request === 'function' ? locks : null;
  } catch {
    return null;
  }
}

/** The one claim registry of this tab. */
export const soundClaims: SoundClaims = createSoundClaims(browserLocks());
