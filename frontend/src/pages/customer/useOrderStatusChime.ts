import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { OrderStatus } from '../../api/types';
import { attachGestureUnlock } from '../../lib/sound/gestureUnlock';
import { soundEngine } from '../../lib/sound/soundEngine';
import {
  GUEST_DEFAULT_PREFS,
  GUEST_PREFS_KEY,
  loadPrefs,
  safeStorage,
  savePrefs,
  type SoundPrefs,
} from '../../lib/sound/soundPrefs';
import { createStatusChimeTracker, statusSound } from '../../lib/sound/statusChime';

/**
 * The diner's own, optional sound: one soft chime when the order they are watching moves to a later
 * status. Off until they switch it on (that click is also what lets the browser start audio), remembered
 * on this device, and never played for the first status seen or for one that was already announced.
 */
export function useOrderStatusChime(orderToken: string, status: OrderStatus | undefined) {
  const [prefs, setPrefs] = useState<SoundPrefs>(() =>
    loadPrefs(safeStorage('local'), GUEST_PREFS_KEY, GUEST_DEFAULT_PREFS),
  );
  const prefsRef = useRef(prefs);
  prefsRef.current = prefs;
  const supported = soundEngine.getState() !== 'unsupported';

  // A new order gets a fresh tracker, so its first status is a starting point again.
  const tracker = useMemo(() => createStatusChimeTracker(), [orderToken]);

  useEffect(() => {
    if (status === undefined) return;
    if (tracker.observe(status) && prefsRef.current.master) {
      soundEngine.play(statusSound(status) ?? 'MESSAGE', prefsRef.current.volume, prefsRef.current.theme);
    }
  }, [status, tracker]);

  // Switched on in an earlier visit: the first tap anywhere on the page lets the browser start audio.
  useEffect(() => {
    if (!prefs.master) return;
    return attachGestureUnlock(document, soundEngine);
  }, [prefs.master]);

  const toggle = useCallback(() => {
    const next: SoundPrefs = { ...prefsRef.current, master: !prefsRef.current.master };
    prefsRef.current = next;
    setPrefs(next);
    savePrefs(safeStorage('local'), GUEST_PREFS_KEY, next);
    if (next.master) {
      // Called inside the click, so the browser accepts it. The chime confirms that sound works.
      void soundEngine.unlock().then((state) => {
        if (state === 'ready') soundEngine.play('MESSAGE', next.volume);
      });
    }
  }, []);

  return { enabled: prefs.master, supported, toggle };
}
