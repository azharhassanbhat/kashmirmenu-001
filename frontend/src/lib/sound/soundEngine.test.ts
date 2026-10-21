import { describe, expect, it } from 'vitest';
import { MAX_MASTER_GAIN, SOUND_DEFS, SOUND_KINDS, soundTrim, volumeToGain } from './soundDefs';
import { createSoundEngine, type EngineState } from './soundEngine';

function fakeContext(opts: { allowResume: boolean }) {
  const listeners = new Set<() => void>();
  const stats = { oscillators: 0, disconnects: 0, gains: [] as { gain: { value: number } }[] };
  const param = () => ({
    value: 1,
    setValueAtTime() {},
    cancelScheduledValues() {},
    linearRampToValueAtTime() {},
    exponentialRampToValueAtTime() {},
  });
  const ctx = {
    state: 'suspended',
    currentTime: 0,
    destination: {},
    addEventListener: (_: string, fn: () => void) => void listeners.add(fn),
    removeEventListener: (_: string, fn: () => void) => void listeners.delete(fn),
    async resume() {
      if (opts.allowResume) {
        ctx.state = 'running';
        listeners.forEach((fn) => fn());
      }
    },
    async close() {
      ctx.state = 'closed';
    },
    createGain: () => {
      const node = { gain: param(), connect() {}, disconnect: () => void stats.disconnects++ };
      stats.gains.push(node);
      return node;
    },
    createOscillator() {
      stats.oscillators += 1;
      return { type: 'sine', frequency: param(), connect() {}, disconnect() {}, start() {}, stop() {}, onended: null };
    },
    createBufferSource: () => ({ buffer: null, connect() {}, start() {} }),
    createBuffer: () => ({}),
  };
  return { ctx: ctx as unknown as AudioContext, stats };
}

function engineWith(opts: { allowResume: boolean; supported?: boolean }) {
  const fake = fakeContext(opts);
  let created = 0;
  const engine = createSoundEngine({
    supported: () => opts.supported ?? true,
    create: () => {
      created += 1;
      return fake.ctx;
    },
  });
  return { engine, fake, created: () => created };
}

describe('sound definitions', () => {
  it('describes every sound with the intended shape', () => {
    expect(SOUND_KINDS).toHaveLength(12);
    expect(SOUND_DEFS.NEW_ORDER.notes).toHaveLength(4);
    expect(SOUND_DEFS.SUCCESS.notes).toHaveLength(3);
    expect(SOUND_DEFS.WARNING.notes).toHaveLength(3);
    expect(SOUND_DEFS.MESSAGE.notes).toHaveLength(1);
    expect(SOUND_DEFS.CRITICAL.notes).toHaveLength(3);
    const [a, b, c, d] = SOUND_DEFS.NEW_ORDER.notes.map((n) => n.freq);
    expect(a < b && b < c && c < d).toBe(true); // ascending
    const [w1, w2, w3] = SOUND_DEFS.WARNING.notes.map((n) => n.freq);
    expect(w1 > w2 && w2 > w3).toBe(true); // descending
    const [s1, s2, s3] = SOUND_DEFS.SUCCESS.notes.map((n) => n.freq);
    expect(s1 < s2 && s2 < s3).toBe(true); // rising
  });

  it('keeps every note inside its stated duration', () => {
    for (const kind of SOUND_KINDS) {
      const def = SOUND_DEFS[kind];
      for (const n of def.notes) expect((n.at + n.dur) * 1000).toBeLessThanOrEqual(def.durationMs + 1);
    }
  });

  it('maps volume to a capped gain that is silent at zero', () => {
    expect(volumeToGain(0)).toBe(0);
    expect(volumeToGain(100)).toBe(MAX_MASTER_GAIN);
    expect(MAX_MASTER_GAIN).toBeLessThan(0.75);
    expect(volumeToGain(50)).toBeLessThan(volumeToGain(100));
    expect(volumeToGain(500)).toBe(MAX_MASTER_GAIN);
    expect(volumeToGain(-3)).toBe(0);
    expect(volumeToGain(Number.NaN)).toBe(0);
  });
});

describe('sound engine', () => {
  it('reports unsupported and refuses to play', async () => {
    const { engine } = engineWith({ allowResume: true, supported: false });
    expect(engine.getState()).toBe('unsupported');
    expect(engine.play('MESSAGE', 70)).toBe(false);
    expect(await engine.unlock()).toBe('unsupported');
  });

  it('is locked, creates nothing and plays nothing before a gesture', () => {
    const { engine, created } = engineWith({ allowResume: true });
    expect(engine.getState()).toBe('locked');
    expect(engine.play('NEW_ORDER', 70)).toBe(false);
    expect(created()).toBe(0);
  });

  it('becomes ready after unlock and then plays', async () => {
    const { engine, fake } = engineWith({ allowResume: true });
    const seen: EngineState[] = [];
    engine.subscribe((s) => seen.push(s));
    expect(await engine.unlock()).toBe('ready');
    expect(engine.getState()).toBe('ready');
    expect(seen).toContain('ready');
    expect(engine.play('NEW_ORDER', 70)).toBe(true);
    // Four bell notes, each a fundamental, a shimmer copy and two partials.
    expect(fake.stats.oscillators).toBe(16);
    expect(engine.play('MESSAGE', 70)).toBe(true);
  });

  it('stays locked and does not pretend when the browser refuses to resume', async () => {
    const { engine, fake } = engineWith({ allowResume: false });
    expect(await engine.unlock()).toBe('locked');
    expect(engine.getState()).toBe('locked');
    expect(engine.play('NEW_ORDER', 70)).toBe(false);
    expect(fake.stats.oscillators).toBe(0);
  });

  it('creates one shared context however often it is unlocked', async () => {
    const { engine, created } = engineWith({ allowResume: true });
    await engine.unlock();
    await engine.unlock();
    expect(created()).toBe(1);
  });

  it('starts nothing at volume 0 but is not reported as blocked', async () => {
    const { engine, fake } = engineWith({ allowResume: true });
    await engine.unlock();
    expect(engine.play('MESSAGE', 0)).toBe(true);
    expect(fake.stats.oscillators).toBe(0);
  });

  it('goes back to locked after dispose', async () => {
    const { engine } = engineWith({ allowResume: true });
    await engine.unlock();
    engine.dispose();
    expect(engine.getState()).toBe('locked');
    expect(engine.play('MESSAGE', 70)).toBe(false);
  });
});

describe('sound engine: changing what is already sounding', () => {
  it('applies a new volume to a sound that is playing', async () => {
    const { engine, fake } = engineWith({ allowResume: true });
    await engine.unlock();
    expect(engine.play('NEW_ORDER', 100)).toBe(true);
    const master = fake.stats.gains[0]; // the first gain a play() creates is its master
    expect(master.gain.value).toBeCloseTo(volumeToGain(100) * soundTrim('NEW_ORDER'));
    engine.setVolume(30);
    expect(master.gain.value).toBeCloseTo(volumeToGain(30) * soundTrim('NEW_ORDER'));
  });

  it('silences and disconnects a playing sound on stopAll, and a later sound still plays', async () => {
    const { engine, fake } = engineWith({ allowResume: true });
    await engine.unlock();
    engine.play('NEW_ORDER', 80);
    const before = fake.stats.disconnects;
    engine.stopAll();
    expect(fake.stats.disconnects).toBeGreaterThan(before);
    expect(engine.play('SUCCESS', 80)).toBe(true);
  });
});
