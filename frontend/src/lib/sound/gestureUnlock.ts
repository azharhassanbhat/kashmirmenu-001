import type { SoundEngine } from './soundEngine';

/*
 * Browsers only let audio start after a real user gesture. This does not try to get around that: it waits
 * for the person's own first click, key press or touch anywhere on the page and, inside that gesture,
 * asks the engine to unlock. Once the engine is running (or can never run) it removes itself.
 *
 * pointerdown, keydown and touchstart cover the common cases; pointerup and touchend are added because
 * touch devices only count the END of a tap as the activating gesture.
 */

export const GESTURE_EVENTS = ['pointerdown', 'pointerup', 'keydown', 'touchstart', 'touchend'] as const;

/**
 * A control that unlocks audio itself, in its own click handler, and plays its own confirmation. The page-wide
 * listener leaves presses on it alone: unlocking on pointerdown would re-render and remove the control before
 * its click is handled, and the confirmation would never play.
 */
export const ACTIVATOR_ATTR = 'data-sound-activator';

function isActivator(event: Event): boolean {
  const target = event.target as { closest?: (selector: string) => unknown } | null;
  return typeof target?.closest === 'function' && target.closest(`[${ACTIVATOR_ATTR}]`) !== null;
}

type Target = Pick<EventTarget, 'addEventListener' | 'removeEventListener'>;

export function attachGestureUnlock(target: Target, engine: Pick<SoundEngine, 'unlock' | 'getState'>): () => void {
  if (engine.getState() !== 'locked') return () => undefined;
  let attached = true;

  const detach = () => {
    if (!attached) return;
    attached = false;
    for (const type of GESTURE_EVENTS) target.removeEventListener(type, onGesture);
  };

  function onGesture(event: Event) {
    if (isActivator(event)) return;
    // unlock() creates and resumes the context synchronously, i.e. inside this gesture.
    void engine.unlock().then((state) => {
      if (state !== 'locked') detach();
    });
  }

  for (const type of GESTURE_EVENTS) target.addEventListener(type, onGesture, { passive: true });
  return detach;
}
