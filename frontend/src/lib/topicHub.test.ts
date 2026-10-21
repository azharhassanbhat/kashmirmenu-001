import { describe, expect, it } from 'vitest';
import { createTopicHub, type TransportEvents } from './topicHub';

function setup() {
  const opened: { destination: string; events: TransportEvents; closed: boolean }[] = [];
  const hub = createTopicHub((destination, events) => {
    const rec = { destination, events, closed: false };
    opened.push(rec);
    return {
      close: () => {
        rec.closed = true;
      },
    };
  });
  return { hub, opened };
}

describe('topic hub', () => {
  it('shares one transport per destination however many subscribe', () => {
    const { hub, opened } = setup();
    const a = hub.subscribe('/topic/restaurants/1', { onMessage: () => undefined });
    const b = hub.subscribe('/topic/restaurants/1', { onMessage: () => undefined });
    hub.subscribe('/topic/restaurants/2', { onMessage: () => undefined });
    expect(opened).toHaveLength(2);
    expect(hub.subscriberCount('/topic/restaurants/1')).toBe(2);
    a();
    b();
  });

  it('closes the transport when the last subscriber leaves, not before', () => {
    const { hub, opened } = setup();
    const a = hub.subscribe('/t', { onMessage: () => undefined });
    const b = hub.subscribe('/t', { onMessage: () => undefined });
    a();
    expect(opened[0].closed).toBe(false);
    b();
    expect(opened[0].closed).toBe(true);
    expect(hub.openCount()).toBe(0);
  });

  it('opens a fresh transport after everyone left', () => {
    const { hub, opened } = setup();
    hub.subscribe('/t', { onMessage: () => undefined })();
    hub.subscribe('/t', { onMessage: () => undefined });
    expect(opened).toHaveLength(2);
    expect(opened[1].closed).toBe(false);
  });

  it('fans each message out to every subscriber, parsed once, and stops after unsubscribe', () => {
    const { hub, opened } = setup();
    const got1: unknown[] = [];
    const got2: unknown[] = [];
    const off1 = hub.subscribe('/t', { onMessage: (p) => got1.push(p) });
    hub.subscribe('/t', { onMessage: (p) => got2.push(p) });
    opened[0].events.onMessage('{"id":1}');
    off1();
    opened[0].events.onMessage('{"id":2}');
    expect(got1).toEqual([{ id: 1 }]);
    expect(got2).toEqual([{ id: 1 }, { id: 2 }]);
  });

  it('ignores malformed frames and survives a throwing listener', () => {
    const { hub, opened } = setup();
    const got: unknown[] = [];
    hub.subscribe('/t', {
      onMessage: () => {
        throw new Error('boom');
      },
    });
    hub.subscribe('/t', { onMessage: (p) => got.push(p) });
    opened[0].events.onMessage('not json');
    opened[0].events.onMessage('{"ok":true}');
    expect(got).toEqual([{ ok: true }]);
  });

  it('tells new and existing subscribers the connection state', () => {
    const { hub, opened } = setup();
    const a: boolean[] = [];
    hub.subscribe('/t', { onMessage: () => undefined, onStatus: (c) => a.push(c) });
    opened[0].events.onConnected(true);
    const b: boolean[] = [];
    hub.subscribe('/t', { onMessage: () => undefined, onStatus: (c) => b.push(c) });
    opened[0].events.onConnected(false);
    expect(a).toEqual([false, true, false]);
    expect(b).toEqual([true, false]);
  });

  it('makes unsubscribe idempotent', () => {
    const { hub, opened } = setup();
    const off = hub.subscribe('/t', { onMessage: () => undefined });
    const other = hub.subscribe('/t', { onMessage: () => undefined });
    off();
    off();
    expect(opened[0].closed).toBe(false);
    other();
    expect(opened[0].closed).toBe(true);
  });
});
