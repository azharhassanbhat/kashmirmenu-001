import { describe, expect, it } from 'vitest';
import { createSoundEngine } from './soundEngine';

/** A context that remembers every node, so a test can prove each one is released. */
function trackingContext() {
  const listeners = new Set<() => void>();
  const nodes: { connected: boolean; kind: string; onended?: () => void }[] = [];
  const param = () => ({
    value: 1,
    setValueAtTime() {},
    cancelScheduledValues() {},
    linearRampToValueAtTime() {},
    exponentialRampToValueAtTime() {},
  });
  const ctx = {
    state: 'running' as string,
    currentTime: 0,
    destination: {},
    closed: false,
    addEventListener: (_: string, fn: () => void) => void listeners.add(fn),
    removeEventListener: (_: string, fn: () => void) => void listeners.delete(fn),
    listenerCount: () => listeners.size,
    suspend() {
      ctx.state = 'suspended';
      listeners.forEach((fn) => fn());
    },
    async resume() {
      ctx.state = 'running';
      listeners.forEach((fn) => fn());
    },
    async close() {
      ctx.state = 'closed';
      ctx.closed = true;
    },
    createGain() {
      const node = { connected: true, kind: 'gain', gain: param(), connect() {}, disconnect: () => void (node.connected = false) };
      nodes.push(node);
      return node;
    },
    createOscillator() {
      const node = {
        connected: true,
        kind: 'osc',
        type: 'sine',
        frequency: param(),
        connect() {},
        disconnect: () => void (node.connected = false),
        start() {},
        stop() {},
        onended: undefined as undefined | (() => void),
      };
      nodes.push(node);
      return node;
    },
    createBufferSource: () => ({ buffer: null, connect() {}, start() {} }),
    createBuffer: () => ({}),
  };
  const endAll = () => nodes.forEach((n) => n.onended?.());
  return { ctx, nodes, endAll };
}

function engineOver(t: ReturnType<typeof trackingContext>) {
  return createSoundEngine({ supported: () => true, create: () => t.ctx as unknown as AudioContext });
}

describe('sound engine cleanup', () => {
  it('releases every node of a sound once it has finished', async () => {
    const t = trackingContext();
    const engine = engineOver(t);
    await engine.unlock();
    engine.play('NEW_ORDER', 70);
    expect(t.nodes.some((n) => n.connected)).toBe(true);
    t.endAll();
    expect(t.nodes.filter((n) => n.connected)).toEqual([]);
  });

  it('leaves nothing behind after hundreds of sounds', async () => {
    const t = trackingContext();
    const engine = engineOver(t);
    await engine.unlock();
    for (let i = 0; i < 300; i++) {
      engine.play(i % 2 === 0 ? 'WAITER_CALL' : 'ORDER_READY', 70);
      t.endAll();
    }
    expect(t.nodes.filter((n) => n.connected)).toEqual([]);
  });

  it('creates exactly one audio context however often it is unlocked and used', async () => {
    const t = trackingContext();
    let created = 0;
    const engine = createSoundEngine({
      supported: () => true,
      create: () => {
        created += 1;
        return t.ctx as unknown as AudioContext;
      },
    });
    for (let i = 0; i < 50; i++) await engine.unlock();
    for (let i = 0; i < 50; i++) engine.play('MESSAGE', 70);
    expect(created).toBe(1);
  });

  it('closes the context and drops its listener on dispose, and can start again', async () => {
    const t = trackingContext();
    const engine = engineOver(t);
    await engine.unlock();
    expect(t.ctx.listenerCount()).toBe(1);
    engine.dispose();
    expect(t.ctx.closed).toBe(true);
    expect(t.ctx.listenerCount()).toBe(0);
    expect(engine.getState()).toBe('locked');
  });

  it('recovers from the browser suspending the context after inactivity', async () => {
    const t = trackingContext();
    const engine = engineOver(t);
    await engine.unlock();
    expect(engine.getState()).toBe('ready');
    t.ctx.suspend(); // the browser parked the audio while the tab sat idle
    expect(engine.getState()).toBe('locked');
    expect(engine.play('NEW_ORDER', 70)).toBe(false); // honest: nothing was heard
    expect(await engine.resume()).toBe('ready');
    expect(engine.play('NEW_ORDER', 70)).toBe(true);
  });

  it('keeps notifying subscribers of state changes and stops after unsubscribe', async () => {
    const t = trackingContext();
    const engine = engineOver(t);
    const seen: string[] = [];
    const off = engine.subscribe((s) => seen.push(s));
    await engine.unlock();
    t.ctx.suspend();
    off();
    await engine.resume();
    expect(seen).toEqual(['ready', 'locked']);
  });
});
