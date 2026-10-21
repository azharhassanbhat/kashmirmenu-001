import { describe, expect, it } from 'vitest';
import {
  MAX_MASTER_GAIN,
  SOUND_DEFS,
  SOUND_KINDS,
  SOUND_THEMES,
  aWeight,
  componentsOf,
  loudness,
  peakLevel,
  soundTrim,
  type SoundKind,
} from './soundDefs';

/** The staff-facing alerts: these must feel equally loud. Guest sounds and the soft ping are deliberately quieter. */
const STAFF_ALERTS: SoundKind[] = ['NEW_ORDER', 'WAITER_CALL', 'ORDER_READY', 'ORDER_CANCELLED', 'SUCCESS', 'WARNING', 'CRITICAL'];

const dB = (ratio: number) => 20 * Math.log10(ratio);

describe('sound quality', () => {
  it('weights the ear honestly: 1 at 1 kHz, much less at low pitch', () => {
    expect(aWeight(1000)).toBeCloseTo(1, 1);
    expect(aWeight(200)).toBeLessThan(0.5);
  });

  it.each(SOUND_THEMES.map((t) => t.id))('brings every staff alert to the same perceived loudness (%s theme)', (theme) => {
    const levels = STAFF_ALERTS.map((k) => loudness(k, theme) * soundTrim(k, theme));
    const spread = dB(Math.max(...levels) / Math.min(...levels));
    // Equal by construction unless a trim hit its limit; allow 1.5 dB.
    expect(spread).toBeLessThan(1.5);
  });

  it('keeps guest sounds and the soft ping quieter than the staff alerts', () => {
    const ref = loudness('NEW_ORDER') * soundTrim('NEW_ORDER');
    for (const k of ['MESSAGE', 'STATUS_ACCEPTED', 'STATUS_PREPARING', 'STATUS_SERVED'] as SoundKind[]) {
      expect(loudness(k) * soundTrim(k), k).toBeLessThan(ref);
    }
  });

  it.each(SOUND_THEMES.map((t) => t.id))('never clips at full volume (%s theme)', (theme) => {
    for (const kind of SOUND_KINDS) {
      expect(peakLevel(kind, theme) * soundTrim(kind, theme) * MAX_MASTER_GAIN, `${kind}/${theme}`).toBeLessThanOrEqual(0.9001);
    }
  });

  it('never applies an extreme trim (the sounds are designed close to level already)', () => {
    for (const kind of SOUND_KINDS) {
      const trim = soundTrim(kind);
      expect(trim, kind).toBeGreaterThanOrEqual(0.4);
      expect(trim, kind).toBeLessThanOrEqual(2.2);
    }
  });

  it('has a new order that is the longest-ringing attention sound and a waiter call that is a clear double strike', () => {
    expect(SOUND_DEFS.NEW_ORDER.notes).toHaveLength(4);
    const strikes = SOUND_DEFS.WAITER_CALL.notes;
    expect(strikes).toHaveLength(2);
    expect(strikes[0].freq).toBe(strikes[1].freq);
    expect(strikes[1].at - strikes[0].at).toBeGreaterThan(0.2);
  });

  it('describes the shimmer as a faint detune, not a second pitch', () => {
    const note = SOUND_DEFS.WAITER_CALL.notes[0];
    const [main, shimmer] = componentsOf(note);
    expect(shimmer.freq / main.freq).toBeGreaterThan(1.001);
    expect(shimmer.freq / main.freq).toBeLessThan(1.01);
    expect(shimmer.level).toBeLessThan(main.level);
  });

  it('gives each guest-facing and staff sound a unique shape', () => {
    const shape = (k: SoundKind) => JSON.stringify(componentsOf(SOUND_DEFS[k].notes[0]).map((c) => c.freq.toFixed(1)));
    expect(new Set(SOUND_KINDS.map((k) => shape(k) + SOUND_DEFS[k].notes.length)).size).toBe(SOUND_KINDS.length);
  });
});
