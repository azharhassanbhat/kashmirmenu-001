import { componentsOf, soundTrim, themedNotes, volumeToGain, type Component, type SoundKind, type SoundTheme } from './soundDefs';

/*
 * Web Audio synthesis for the notification sounds. No audio files, no third-party URLs.
 *
 * Browsers refuse to start audio until the page has had a real user gesture, and nothing here tries to get
 * around that: the AudioContext is created lazily, inside `unlock()`, which callers only invoke from a
 * click, key press or touch. Until the context is actually running the state is 'locked' and `play()`
 * returns false, so the app never pretends a sound was heard when it was not.
 */

export type EngineState = 'locked' | 'ready' | 'unsupported';

export interface AudioContextFactory {
  supported: () => boolean;
  create: () => AudioContext;
}

export interface SoundEngine {
  getState: () => EngineState;
  /** Calls `fn` whenever the state changes. Returns the unsubscribe function. */
  subscribe: (fn: (state: EngineState) => void) => () => void;
  /**
   * Creates the context if needed and asks the browser to start it. Call ONLY from a user gesture
   * (click, key press, touch). Resolves with the state afterwards; 'locked' means the browser said no.
   */
  unlock: () => Promise<EngineState>;
  /** Tries to resume an existing context (for example when the tab comes back). Never creates one. */
  resume: () => Promise<EngineState>;
  /** Plays a sound now. False when audio is locked or unsupported, or the sound could not be started. */
  play: (kind: SoundKind, volume: number, theme?: SoundTheme) => boolean;
  /** Changes the loudness of sounds already playing (0-100). */
  setVolume: (volume: number) => void;
  /** Silences every sound that is playing right now. */
  stopAll: () => void;
  /** Closes the context. The engine can be unlocked again afterwards. */
  dispose: () => void;
}

/** How long to wait for the browser to answer resume() before giving up (some browsers never answer). */
const RESUME_TIMEOUT_MS = 1500;

type AudioWindow = { AudioContext?: typeof AudioContext; webkitAudioContext?: typeof AudioContext };

function audioContextConstructor(): typeof AudioContext | null {
  if (typeof window === 'undefined') return null;
  const w = window as unknown as AudioWindow;
  return w.AudioContext ?? w.webkitAudioContext ?? null;
}

export const browserAudioFactory: AudioContextFactory = {
  supported: () => audioContextConstructor() !== null,
  create: () => {
    const Ctor = audioContextConstructor();
    if (!Ctor) throw new Error('Web Audio is not supported');
    // 'interactive' keeps the latency of a notification low.
    return new Ctor({ latencyHint: 'interactive' });
  },
};

export function createSoundEngine(factory: AudioContextFactory = browserAudioFactory): SoundEngine {
  let ctx: AudioContext | null = null;
  let lastEmitted: EngineState | null = null;
  /** The master gain of every sound still sounding (and its loudness trim), so volume and mute reach them. */
  const active = new Map<GainNode, number>();
  const listeners = new Set<(state: EngineState) => void>();

  function getState(): EngineState {
    if (!factory.supported()) return 'unsupported';
    return ctx !== null && ctx.state === 'running' ? 'ready' : 'locked';
  }

  function emit(): EngineState {
    const state = getState();
    if (state !== lastEmitted) {
      lastEmitted = state;
      listeners.forEach((fn) => fn(state));
    }
    return state;
  }

  function withTimeout(promise: Promise<void>): Promise<void> {
    return new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, RESUME_TIMEOUT_MS);
      promise.then(
        () => {
          clearTimeout(timer);
          resolve();
        },
        () => {
          clearTimeout(timer);
          resolve();
        },
      );
    });
  }

  /** iOS Safari only counts audio as unlocked once something has been played inside the gesture. */
  function playSilentBuffer(context: AudioContext) {
    try {
      const source = context.createBufferSource();
      source.buffer = context.createBuffer(1, 1, 22050);
      source.connect(context.destination);
      source.start(0);
    } catch {
      // Not essential.
    }
  }

  async function unlock(): Promise<EngineState> {
    if (!factory.supported()) return 'unsupported';
    let pending: Promise<void> = Promise.resolve();
    try {
      // Everything up to the first await runs synchronously inside the caller's gesture.
      if (ctx === null || ctx.state === 'closed') {
        ctx = factory.create();
        ctx.addEventListener('statechange', emit);
      }
      if (ctx.state !== 'running') pending = ctx.resume();
      playSilentBuffer(ctx);
    } catch {
      return emit();
    }
    await withTimeout(pending);
    return emit();
  }

  async function resume(): Promise<EngineState> {
    if (ctx !== null && ctx.state !== 'running' && ctx.state !== 'closed') {
      await withTimeout(ctx.resume());
    }
    return emit();
  }

  function voice(context: AudioContext, out: AudioNode, start: number, c: Component, onEnded: () => void) {
    const osc = context.createOscillator();
    osc.type = c.wave;
    osc.frequency.setValueAtTime(c.freq, start);
    const env = context.createGain();
    // A short attack and an exponential release: no clicks at either end.
    env.gain.setValueAtTime(0.0001, start);
    env.gain.linearRampToValueAtTime(c.level, start + 0.012);
    env.gain.exponentialRampToValueAtTime(0.0001, start + c.dur);
    osc.connect(env);
    env.connect(out);
    osc.onended = () => {
      osc.disconnect();
      env.disconnect();
      onEnded();
    };
    osc.start(start);
    osc.stop(start + c.dur + 0.05);
  }

  function play(kind: SoundKind, volume: number, theme?: SoundTheme): boolean {
    const context = ctx;
    if (context === null || context.state !== 'running') {
      emit();
      return false;
    }
    const gain = volumeToGain(volume);
    if (gain <= 0) return true; // nothing to hear, nothing to start
    try {
      const components = themedNotes(kind, theme).flatMap(componentsOf);
      // Every sound is brought to the same perceived loudness (and kept clear of clipping) by its own trim.
      const trim = soundTrim(kind, theme);
      const master = context.createGain();
      master.gain.value = gain * trim;
      master.connect(context.destination);
      const start = context.currentTime + 0.03;
      let remaining = components.length;
      active.set(master, trim);
      const done = () => {
        remaining -= 1;
        if (remaining === 0) {
          active.delete(master);
          master.disconnect();
        }
      };
      for (const c of components) voice(context, master, start + c.at, c, done);
      return true;
    } catch {
      return false;
    }
  }

  function setVolume(volume: number) {
    const gain = volumeToGain(volume);
    for (const [node, trim] of active) {
      try {
        node.gain.cancelScheduledValues(0);
        node.gain.value = gain * trim;
      } catch {
        // The node is already gone.
      }
    }
  }

  function stopAll() {
    for (const node of active.keys()) {
      try {
        node.gain.cancelScheduledValues(0);
        node.gain.value = 0;
        node.disconnect();
      } catch {
        // The node is already gone.
      }
    }
    active.clear();
  }

  function dispose() {
    const context = ctx;
    ctx = null;
    active.clear();
    if (context && context.state !== 'closed') {
      context.removeEventListener('statechange', emit);
      void context.close().catch(() => undefined);
    }
    emit();
  }

  return {
    getState,
    subscribe(fn) {
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    unlock,
    resume,
    play,
    setVolume,
    stopAll,
    dispose,
  };
}

/** The one engine every part of the tab shares. Creating it does not touch Web Audio. */
export const soundEngine: SoundEngine = createSoundEngine();
