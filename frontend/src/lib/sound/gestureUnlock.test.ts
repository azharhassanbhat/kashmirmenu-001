import { describe, expect, it } from 'vitest';
import { ACTIVATOR_ATTR, GESTURE_EVENTS, attachGestureUnlock } from './gestureUnlock';
import type { EngineState } from './soundEngine';

function fakeTarget() {
  const handlers = new Map<string, EventListener>();
  return {
    handlers,
    addEventListener: (type: string, fn: EventListenerOrEventListenerObject) => void handlers.set(type, fn as EventListener),
    removeEventListener: (type: string) => void handlers.delete(type),
  };
}

const flush = async () => {
  for (let i = 0; i < 4; i++) await Promise.resolve();
};

describe('gesture unlock', () => {
  it('listens for the user gestures and unlocks on the first one', async () => {
    const target = fakeTarget();
    let state: EngineState = 'locked';
    let calls = 0;
    const engine = {
      getState: () => state,
      unlock: async () => {
        calls += 1;
        state = 'ready';
        return state;
      },
    };
    attachGestureUnlock(target, engine);
    expect([...target.handlers.keys()].sort()).toEqual([...GESTURE_EVENTS].sort());
    target.handlers.get('pointerdown')?.(new Event('pointerdown'));
    await flush();
    expect(calls).toBe(1);
    expect(target.handlers.size).toBe(0); // removed itself once ready
  });

  it('stays attached while the browser still refuses', async () => {
    const target = fakeTarget();
    const engine = { getState: () => 'locked' as EngineState, unlock: async () => 'locked' as EngineState };
    const detach = attachGestureUnlock(target, engine);
    target.handlers.get('keydown')?.(new Event('keydown'));
    await flush();
    expect(target.handlers.size).toBe(GESTURE_EVENTS.length);
    detach();
    expect(target.handlers.size).toBe(0);
  });

  it('attaches nothing when audio is already running or unsupported', () => {
    for (const state of ['ready', 'unsupported'] as const) {
      const target = fakeTarget();
      attachGestureUnlock(target, { getState: () => state, unlock: async () => state });
      expect(target.handlers.size).toBe(0);
    }
  });

  it('leaves presses on a self-unlocking control to that control, but unlocks for anything else', async () => {
    const target = fakeTarget();
    let calls = 0;
    const engine = {
      getState: () => 'locked' as EngineState,
      unlock: async () => {
        calls += 1;
        return 'locked' as EngineState;
      },
    };
    attachGestureUnlock(target, engine);
    const press = (type: string, inside: boolean) => {
      const event = new Event(type);
      Object.defineProperty(event, 'target', {
        value: { closest: (selector: string) => (inside && selector === `[${ACTIVATOR_ATTR}]` ? {} : null) },
      });
      target.handlers.get(type)?.(event);
    };
    for (const type of GESTURE_EVENTS) press(type, true);
    await flush();
    expect(calls).toBe(0);
    press('pointerdown', false);
    await flush();
    expect(calls).toBe(1);
  });
});
