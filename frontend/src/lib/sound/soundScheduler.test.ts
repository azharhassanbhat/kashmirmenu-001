import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  COLLAPSE_WINDOW_MS,
  MAX_PENDING,
  MIN_GAP_MS,
  browserSchedulerDeps,
  createSoundScheduler,
  type SoundRequest,
} from './soundScheduler';
import type { SoundKind } from './soundDefs';

const req = (kind: SoundKind, priority: number, volume = 70): SoundRequest => ({ kind, priority, volume });

function setup(playResult: () => boolean = () => true) {
  const played: { kind: SoundKind; at: number }[] = [];
  const blocked: SoundKind[] = [];
  const scheduler = createSoundScheduler(
    browserSchedulerDeps(
      (kind) => {
        const ok = playResult();
        if (ok) played.push({ kind, at: Date.now() });
        return ok;
      },
      (kind) => blocked.push(kind),
    ),
  );
  return { scheduler, played, blocked };
}

describe('sound scheduler', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
  });
  afterEach(() => vi.useRealTimers());

  it('plays a lone sound after the short collapse window', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('MESSAGE', 15));
    expect(played).toEqual([]);
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS);
    expect(played).toEqual([{ kind: 'MESSAGE', at: COLLAPSE_WINDOW_MS }]);
    expect(scheduler.pending()).toBe(0);
  });

  it('collapses events that arrive together into the highest priority', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('MESSAGE', 15));
    scheduler.enqueue(req('WARNING', 70));
    scheduler.enqueue(req('MESSAGE', 20));
    vi.advanceTimersByTime(5000);
    expect(played.map((p) => p.kind)).toEqual(['WARNING']);
  });

  it('lets the new-order chime go first, then the critical alert; the ordinary success sound is dropped', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('CRITICAL', 90));
    scheduler.enqueue(req('NEW_ORDER', 100));
    scheduler.enqueue(req('SUCCESS', 50));
    vi.advanceTimersByTime(5000);
    expect(played.map((p) => p.kind)).toEqual(['NEW_ORDER', 'CRITICAL']);
  });

  it('does not swallow a waiter call that lands with a new order: it follows, a gap later', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('WAITER_CALL', 80));
    scheduler.enqueue(req('NEW_ORDER', 100));
    vi.advanceTimersByTime(5000);
    expect(played.map((p) => p.kind)).toEqual(['NEW_ORDER', 'WAITER_CALL']);
    expect(played[1].at - played[0].at).toBeGreaterThanOrEqual(900); // after the order chime has finished
  });

  it('plays one sound per must-hear kind, most important first, however many events there were', () => {
    const { scheduler, played } = setup();
    for (let i = 0; i < 5; i++) scheduler.enqueue(req('NEW_ORDER', 100));
    for (let i = 0; i < 10; i++) scheduler.enqueue(req('WAITER_CALL', 80));
    for (let i = 0; i < 3; i++) scheduler.enqueue(req('BILL_REQUEST', 62));
    for (let i = 0; i < 4; i++) scheduler.enqueue(req('MESSAGE', 20));
    vi.advanceTimersByTime(30_000);
    expect(played.map((p) => p.kind)).toEqual(['NEW_ORDER', 'WAITER_CALL', 'BILL_REQUEST']);
    for (let i = 1; i < played.length; i++) expect(played[i].at - played[i - 1].at).toBeGreaterThanOrEqual(MIN_GAP_MS);
  });

  it('does not keep retrying followers when audio is blocked', () => {
    const { scheduler, blocked } = setup(() => false);
    scheduler.enqueue(req('NEW_ORDER', 100));
    scheduler.enqueue(req('WAITER_CALL', 80));
    vi.advanceTimersByTime(30_000);
    expect(blocked).toEqual(['NEW_ORDER']);
    expect(scheduler.pending()).toBe(0);
  });

  it('keeps at least the minimum gap between sounds and never overlaps', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('MESSAGE', 15));
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS); // the first plays at 120ms
    scheduler.enqueue(req('SUCCESS', 50)); // arrives while the gap is running
    vi.advanceTimersByTime(5000);
    expect(played.map((p) => p.kind)).toEqual(['MESSAGE', 'SUCCESS']);
    expect(played[1].at - played[0].at).toBeGreaterThanOrEqual(MIN_GAP_MS);
  });

  it('waits for a long sound to finish, not just the minimum gap', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('NEW_ORDER', 100));
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS);
    scheduler.enqueue(req('MESSAGE', 15));
    vi.advanceTimersByTime(5000);
    // The order chime lasts 1000ms, longer than the 700ms minimum.
    expect(played[1].at - played[0].at).toBeGreaterThanOrEqual(1000);
  });

  it('never drops a new-order sound: it plays right after what is playing', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('SUCCESS', 50));
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS);
    scheduler.enqueue(req('NEW_ORDER', 100));
    vi.advanceTimersByTime(5000);
    expect(played.map((p) => p.kind)).toEqual(['SUCCESS', 'NEW_ORDER']);
    expect(played[1].at - played[0].at).toBeGreaterThanOrEqual(MIN_GAP_MS);
  });

  it('collapses several waiting new-order sounds into one', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('MESSAGE', 15));
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS);
    scheduler.enqueue(req('NEW_ORDER', 100));
    scheduler.enqueue(req('NEW_ORDER', 100));
    scheduler.enqueue(req('NEW_ORDER', 100));
    expect(scheduler.pending()).toBe(1);
    vi.advanceTimersByTime(10_000);
    expect(played.map((p) => p.kind)).toEqual(['MESSAGE', 'NEW_ORDER']);
  });

  it('caps the waiting list and keeps the more important requests', () => {
    const { scheduler, played } = setup();
    for (let i = 0; i < 50; i++) scheduler.enqueue(req('MESSAGE', 10 + (i % 3)));
    expect(scheduler.pending()).toBeLessThanOrEqual(MAX_PENDING);
    scheduler.enqueue(req('NEW_ORDER', 100));
    expect(scheduler.pending()).toBeLessThanOrEqual(MAX_PENDING);
    vi.advanceTimersByTime(5000);
    expect(played.map((p) => p.kind)).toEqual(['NEW_ORDER']);
  });

  it('reports a blocked sound and does not start the gap clock', () => {
    let allow = false;
    const { scheduler, played, blocked } = setup(() => allow);
    scheduler.enqueue(req('MESSAGE', 15));
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS);
    expect(blocked).toEqual(['MESSAGE']);
    allow = true;
    scheduler.enqueue(req('MESSAGE', 15));
    vi.advanceTimersByTime(COLLAPSE_WINDOW_MS);
    expect(played).toHaveLength(1);
    expect(played[0].at).toBe(2 * COLLAPSE_WINDOW_MS);
  });

  it('forgets everything on clear', () => {
    const { scheduler, played } = setup();
    scheduler.enqueue(req('NEW_ORDER', 100));
    scheduler.clear();
    vi.advanceTimersByTime(5000);
    expect(played).toEqual([]);
    expect(scheduler.pending()).toBe(0);
  });
});

describe('sound scheduler: changes while a sound waits', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
  });
  afterEach(() => vi.useRealTimers());

  it('drops a waiting sound that is no longer allowed when its turn comes', () => {
    const played: SoundKind[] = [];
    let muted = false;
    const scheduler = createSoundScheduler({
      ...browserSchedulerDeps((kind) => {
        played.push(kind);
        return true;
      }),
      allow: () => !muted,
    });
    scheduler.enqueue({ kind: 'NEW_ORDER', priority: 100, volume: 70, group: 'newOrder' });
    muted = true;
    vi.advanceTimersByTime(5000);
    expect(played).toEqual([]);
    expect(scheduler.pending()).toBe(0);
  });

  it('plays the allowed request when a vetoed one would have won', () => {
    const played: SoundKind[] = [];
    const scheduler = createSoundScheduler({
      ...browserSchedulerDeps((kind) => {
        played.push(kind);
        return true;
      }),
      allow: (r) => r.group !== 'cancellations',
    });
    scheduler.enqueue({ kind: 'WARNING', priority: 65, volume: 70, group: 'cancellations' });
    scheduler.enqueue({ kind: 'MESSAGE', priority: 20, volume: 70, group: 'general' });
    vi.advanceTimersByTime(5000);
    expect(played).toEqual(['MESSAGE']);
  });

  it('starts a free slot at once when the collapse window is 0 (a hidden tab)', () => {
    const played: SoundKind[] = [];
    const scheduler = createSoundScheduler({
      ...browserSchedulerDeps((kind) => {
        played.push(kind);
        return true;
      }),
      collapseWindowMs: () => 0,
    });
    scheduler.enqueue({ kind: 'NEW_ORDER', priority: 100, volume: 70 });
    expect(played).toEqual(['NEW_ORDER']); // no timer needed
    // The next one still waits for the gap.
    scheduler.enqueue({ kind: 'NEW_ORDER', priority: 100, volume: 70 });
    expect(played).toHaveLength(1);
    vi.advanceTimersByTime(MIN_GAP_MS + 300);
    expect(played).toHaveLength(2);
  });
});
