/*
 * The notification sounds as plain data, so the synthesis is described in one place and the timing
 * (which the scheduler needs) is known without touching Web Audio. Nothing here loads a file or a URL:
 * the engine builds every sound from oscillators.
 *
 * Design notes:
 *   - Every event has its own contour AND its own timbre, so it is recognisable by ear alone:
 *       new order     four rising BELL notes, the last one held        (energetic, "something to cook")
 *       waiter call   one high bell struck twice, same pitch            (a service bell)
 *       bill request  two soft GLASS notes falling a fifth              (polite, unhurried)
 *       order ready   five fast GLASS notes rising, a held top note     (sparkle, "done")
 *       cancelled     two slow falling notes, rounded                   (gentle warning)
 *       payment ok    three rising notes ending on an octave shimmer    (satisfying)
 *       payment fail  two low falling triangle notes                    (clear, not shrill)
 *   - A little detuned shimmer layer on the main notes gives the "premium" width a bare sine lacks.
 *   - Loudness is normalised per sound (see `soundTrim`) so no event is louder than another by accident.
 */

export type SoundKind =
  | 'NEW_ORDER'
  | 'WAITER_CALL'
  | 'BILL_REQUEST'
  | 'ORDER_READY'
  | 'ORDER_CANCELLED'
  | 'SUCCESS'
  | 'WARNING'
  | 'MESSAGE'
  | 'CRITICAL'
  | 'STATUS_ACCEPTED'
  | 'STATUS_PREPARING'
  | 'STATUS_SERVED';

export const SOUND_KINDS: readonly SoundKind[] = [
  'NEW_ORDER',
  'WAITER_CALL',
  'BILL_REQUEST',
  'ORDER_READY',
  'ORDER_CANCELLED',
  'SUCCESS',
  'WARNING',
  'MESSAGE',
  'CRITICAL',
  'STATUS_ACCEPTED',
  'STATUS_PREPARING',
  'STATUS_SERVED',
];

/** Small collection of sound themes. Every theme keeps each event's own melody and timing. */
export type SoundTheme = 'bell' | 'crystal' | 'warm';

export const SOUND_THEMES: readonly { id: SoundTheme; label: string; hint: string }[] = [
  { id: 'bell', label: 'Bell', hint: 'Bright, ringing bells. The default.' },
  { id: 'crystal', label: 'Crystal', hint: 'Higher, glassy and airy.' },
  { id: 'warm', label: 'Warm', hint: 'Rounder and softer.' },
];

export const DEFAULT_THEME: SoundTheme = 'bell';

export function isSoundTheme(value: unknown): value is SoundTheme {
  return value === 'bell' || value === 'crystal' || value === 'warm';
}

export interface NoteDef {
  /** Seconds after the start of the sound. */
  at: number;
  /** Fundamental frequency in Hz. */
  freq: number;
  /** Seconds until the note has faded out. */
  dur: number;
  /** Peak level of this note before the master gain, 0..1. */
  level: number;
  wave: 'sine' | 'triangle';
  /** Extra inharmonic partials make a note bell-like; each decays faster than the fundamental. */
  partials?: { ratio: number; level: number; dur: number }[];
  /** Adds a faintly detuned copy of the note: a slow, shimmering beat that makes it sound wider and richer. */
  shimmer?: boolean;
}

export interface SoundDef {
  /** How long the sound is audible, in ms. The scheduler never starts another before it ends. */
  durationMs: number;
  notes: NoteDef[];
}

/** A struck bell: a clear tone with a ringing partial and a quick high "tick". */
const BELL = [
  { ratio: 2.76, level: 0.22, dur: 0.5, },
  { ratio: 5.4, level: 0.08, dur: 0.25 },
];
/** Glass: one bright octave partial, short. */
const GLASS = [{ ratio: 2, level: 0.14, dur: 0.3 }];

export const SOUND_DEFS: Record<SoundKind, SoundDef> = {
  // C5 E5 G5, then C6 held: an energetic rising arpeggio that lands on a high bell.
  NEW_ORDER: {
    durationMs: 1000,
    notes: [
      { at: 0, freq: 523.25, dur: 0.4, level: 0.5, wave: 'sine', partials: BELL, shimmer: true },
      { at: 0.12, freq: 659.25, dur: 0.4, level: 0.5, wave: 'sine', partials: BELL, shimmer: true },
      { at: 0.24, freq: 783.99, dur: 0.45, level: 0.52, wave: 'sine', partials: BELL, shimmer: true },
      { at: 0.38, freq: 1046.5, dur: 0.6, level: 0.56, wave: 'sine', partials: BELL, shimmer: true },
    ],
  },
  // The same high bell (C6) struck twice, like a hotel service bell. Two equal strikes cannot be mistaken for
  // the four rising notes of an order.
  WAITER_CALL: {
    durationMs: 1100,
    notes: [
      { at: 0, freq: 1046.5, dur: 0.7, level: 0.5, wave: 'sine', partials: BELL, shimmer: true },
      { at: 0.32, freq: 1046.5, dur: 0.78, level: 0.52, wave: 'sine', partials: BELL, shimmer: true },
    ],
  },
  // B5 down to E5 (a falling fifth), soft glass tones: polite rather than urgent.
  BILL_REQUEST: {
    durationMs: 950,
    notes: [
      { at: 0, freq: 987.77, dur: 0.4, level: 0.4, wave: 'sine', partials: GLASS, shimmer: true },
      { at: 0.24, freq: 659.25, dur: 0.7, level: 0.4, wave: 'sine', partials: GLASS, shimmer: true },
    ],
  },
  // A fast pentatonic run (G5 A5 B5 D6) up to a held G6: a bright "done!" with no bell ring.
  ORDER_READY: {
    durationMs: 800,
    notes: [
      { at: 0, freq: 783.99, dur: 0.16, level: 0.4, wave: 'sine', partials: GLASS },
      { at: 0.07, freq: 880, dur: 0.16, level: 0.4, wave: 'sine', partials: GLASS },
      { at: 0.14, freq: 987.77, dur: 0.16, level: 0.4, wave: 'sine', partials: GLASS },
      { at: 0.21, freq: 1174.66, dur: 0.18, level: 0.42, wave: 'sine', partials: GLASS },
      { at: 0.29, freq: 1567.98, dur: 0.5, level: 0.46, wave: 'sine', partials: GLASS, shimmer: true },
    ],
  },
  // D5 down to B4 (a falling minor third), slow and rounded: noticeable, never alarming.
  ORDER_CANCELLED: {
    durationMs: 950,
    notes: [
      { at: 0, freq: 587.33, dur: 0.5, level: 0.44, wave: 'triangle', partials: [BELL[0]] },
      { at: 0.28, freq: 493.88, dur: 0.66, level: 0.44, wave: 'triangle', partials: [BELL[0]], shimmer: true },
    ],
  },
  // Payment received: A5, E6, then an A6 shimmer an octave above the start. Rising, resolved, satisfying.
  SUCCESS: {
    durationMs: 750,
    notes: [
      { at: 0, freq: 880, dur: 0.18, level: 0.42, wave: 'sine', partials: GLASS },
      { at: 0.1, freq: 1318.5, dur: 0.2, level: 0.42, wave: 'sine', partials: GLASS },
      { at: 0.2, freq: 1760, dur: 0.54, level: 0.42, wave: 'sine', partials: GLASS, shimmer: true },
    ],
  },
  // Payment failed: three falling steps (B4, G4, E4) in a rounded triangle voice: clearly "something needs a
  // look", never shrill.
  WARNING: {
    durationMs: 850,
    notes: [
      { at: 0, freq: 493.88, dur: 0.26, level: 0.55, wave: 'triangle' },
      { at: 0.2, freq: 392, dur: 0.26, level: 0.55, wave: 'triangle' },
      { at: 0.4, freq: 329.63, dur: 0.44, level: 0.55, wave: 'triangle', shimmer: true },
    ],
  },
  // One soft ping.
  MESSAGE: {
    durationMs: 300,
    notes: [{ at: 0, freq: 1174.66, dur: 0.26, level: 0.5, wave: 'sine', partials: [GLASS[0]] }],
  },
  // Three pulses alternating between two pitches. Triangle waves keep it noticeable without a harsh edge.
  CRITICAL: {
    durationMs: 1000,
    notes: [
      { at: 0, freq: 880, dur: 0.26, level: 0.5, wave: 'triangle' },
      { at: 0.34, freq: 659.25, dur: 0.26, level: 0.5, wave: 'triangle' },
      { at: 0.68, freq: 880, dur: 0.3, level: 0.5, wave: 'triangle' },
    ],
  },
  // Guest-facing progress sounds, quiet and short.
  STATUS_ACCEPTED: {
    durationMs: 350,
    notes: [
      { at: 0, freq: 987.77, dur: 0.14, level: 0.4, wave: 'sine' },
      { at: 0.09, freq: 1318.5, dur: 0.24, level: 0.4, wave: 'sine', partials: GLASS },
    ],
  },
  STATUS_PREPARING: {
    durationMs: 250,
    notes: [{ at: 0, freq: 880, dur: 0.22, level: 0.3, wave: 'triangle' }],
  },
  STATUS_SERVED: {
    durationMs: 800,
    notes: [
      { at: 0, freq: 659.25, dur: 0.3, level: 0.38, wave: 'sine' },
      { at: 0.18, freq: 880, dur: 0.3, level: 0.38, wave: 'sine' },
      { at: 0.36, freq: 1046.5, dur: 0.44, level: 0.38, wave: 'sine', partials: GLASS, shimmer: true },
    ],
  },
};

/**
 * The notes of a sound under a theme. Timing never changes (the scheduler relies on `durationMs`); only
 * pitch and timbre do. The default theme returns the notes as they are.
 */
export function themedNotes(kind: SoundKind, theme: SoundTheme = DEFAULT_THEME): NoteDef[] {
  const notes = SOUND_DEFS[kind].notes;
  if (theme === 'bell') return notes;
  const pitch = theme === 'crystal' ? 1.2599 : 0.8409;
  return notes.map((n) => ({
    ...n,
    freq: n.freq * pitch,
    wave: theme === 'crystal' ? 'sine' : 'triangle',
    partials: n.partials?.map((p) => ({ ...p, level: p.level * (theme === 'crystal' ? 1.3 : 0.5) })),
  }));
}

/** One oscillator of a note: its fundamental, each partial, and the shimmer copy. */
export interface Component {
  freq: number;
  level: number;
  dur: number;
  wave: OscillatorType;
  /** Start offset within the sound, seconds. */
  at: number;
}

/** The detune of the shimmer layer: about 6 cents, slow enough to beat gently rather than warble. */
export const SHIMMER_RATIO = 1.0035;
export const SHIMMER_LEVEL = 0.55;

/** Every oscillator a note needs. The engine plays exactly these, and the loudness estimate measures exactly these. */
export function componentsOf(note: NoteDef): Component[] {
  const out: Component[] = [{ freq: note.freq, level: note.level, dur: note.dur, wave: note.wave, at: note.at }];
  if (note.shimmer) {
    out.push({ freq: note.freq * SHIMMER_RATIO, level: note.level * SHIMMER_LEVEL, dur: note.dur, wave: 'sine', at: note.at });
  }
  for (const p of note.partials ?? []) {
    out.push({ freq: note.freq * p.ratio, level: note.level * p.level, dur: p.dur, wave: 'sine', at: note.at });
  }
  return out;
}

/* ---------- loudness ---------- */

const ATTACK_S = 0.012;
const FLOOR = 0.0001;

/** The amplitude envelope the engine applies: a short linear attack, then an exponential fade. */
export function envelope(c: Component, t: number): number {
  if (t < 0 || t >= c.dur) return 0;
  if (t < ATTACK_S) return (c.level * t) / ATTACK_S;
  const x = (t - ATTACK_S) / (c.dur - ATTACK_S);
  return c.level * Math.pow(FLOOR / c.level, x);
}

/** A-weighting (1 at 1 kHz): the ear is far less sensitive to low notes, so they need more level to feel equal. */
export function aWeight(freq: number): number {
  const f2 = freq * freq;
  const ra = (12194 ** 2 * f2 * f2) / ((f2 + 20.6 ** 2) * Math.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194 ** 2));
  const ra1k = 0.7943; // RA at 1 kHz, so the weight is 1 there
  return ra / ra1k;
}

/** Weighted energy of one component over its whole life (a triangle has more upper harmonics: counted as +25% energy). */
function energyOf(c: Component): number {
  const T = c.dur - ATTACK_S;
  const r = FLOOR / c.level;
  const attack = (c.level ** 2 * ATTACK_S) / 3;
  const decay = (c.level ** 2 * T * (1 - r * r)) / (-2 * Math.log(r));
  return (attack + decay) * aWeight(c.freq) ** 2 * (c.wave === 'triangle' ? 1.25 : 1);
}

/** The perceived "size" of a sound: the square root of its weighted energy. Equal sizes sound equally loud. */
export function loudness(kind: SoundKind, theme: SoundTheme = DEFAULT_THEME): number {
  let energy = 0;
  for (const note of themedNotes(kind, theme)) for (const c of componentsOf(note)) energy += energyOf(c);
  return Math.sqrt(energy);
}

/** The highest instantaneous level the sound can reach if every sounding oscillator peaked together (worst case). */
export function peakLevel(kind: SoundKind, theme: SoundTheme = DEFAULT_THEME): number {
  const comps = themedNotes(kind, theme).flatMap(componentsOf);
  const end = Math.max(...comps.map((c) => c.at + c.dur));
  let peak = 0;
  for (let t = 0; t <= end; t += 0.005) {
    let sum = 0;
    for (const c of comps) sum += envelope(c, t - c.at);
    peak = Math.max(peak, sum);
  }
  return peak;
}

/** How loud each event should be relative to the reference (1 = a staff alert; guests' phone sounds sit lower). */
const RELATIVE_LEVEL: Partial<Record<SoundKind, number>> = {
  MESSAGE: 0.8,
  BILL_REQUEST: 0.9,
  STATUS_ACCEPTED: 0.7,
  STATUS_PREPARING: 0.6,
  STATUS_SERVED: 0.7,
};

const TRIM_MIN = 0.4;
const TRIM_MAX = 2.2;
/** The master gain times the peak must stay below this fraction of full scale, so nothing ever clips. */
const HEADROOM = 0.9;

let reference: number | null = null;
const trims = new Map<string, number>();

/**
 * A per-sound multiplier on the master gain. It brings every sound to the same weighted loudness as the
 * reference (a new order) times its `RELATIVE_LEVEL`, then limits it so the loudest instant stays inside the
 * headroom. Computed once per sound and theme.
 */
export function soundTrim(kind: SoundKind, theme: SoundTheme = DEFAULT_THEME): number {
  const key = `${kind}:${theme}`;
  const known = trims.get(key);
  if (known !== undefined) return known;
  if (reference === null) reference = loudness('NEW_ORDER', 'bell');
  const wanted = (reference * (RELATIVE_LEVEL[kind] ?? 1)) / loudness(kind, theme);
  const safe = HEADROOM / (peakLevel(kind, theme) * MAX_MASTER_GAIN);
  const trim = Math.min(Math.max(wanted, TRIM_MIN), TRIM_MAX, safe);
  trims.set(key, trim);
  return trim;
}

/** The master gain at 100%. Kept well below full scale so the loudest setting is still comfortable. */
export const MAX_MASTER_GAIN = 0.5;

/** 0-100 volume to a gain. Squared so the slider feels even to the ear; 0 is silent. */
export function volumeToGain(volume: number): number {
  const v = Number.isFinite(volume) ? Math.min(100, Math.max(0, volume)) / 100 : 0;
  return MAX_MASTER_GAIN * v * v;
}
