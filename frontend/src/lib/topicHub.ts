/*
 * A ref-counted hub of topic connections. Every caller that wants a topic shares ONE transport (one
 * socket) per destination; the transport opens with the first subscriber and closes when the last one
 * leaves. The transport itself is injected, so the counting logic is testable without a socket.
 */

export interface TopicSubscriber {
  /** The parsed JSON body of a frame. Shape checks belong to the subscriber. */
  onMessage: (payload: unknown) => void;
  /** Called with the current connection state when subscribing, and on every change after that. */
  onStatus?: (connected: boolean) => void;
}

export interface TransportEvents {
  onMessage: (body: string) => void;
  onConnected: (connected: boolean) => void;
}

export interface TransportHandle {
  close: () => void;
}

export type OpenTransport = (destination: string, events: TransportEvents) => TransportHandle;

interface Entry {
  handle: TransportHandle;
  subscribers: Set<TopicSubscriber>;
  connected: boolean;
}

export interface TopicHub {
  /** Returns an idempotent unsubscribe function. */
  subscribe: (destination: string, subscriber: TopicSubscriber) => () => void;
  /** How many subscribers share the destination's transport (0 when it is closed). */
  subscriberCount: (destination: string) => number;
  /** How many transports are open. */
  openCount: () => number;
}

export function createTopicHub(open: OpenTransport): TopicHub {
  const entries = new Map<string, Entry>();

  function ensure(destination: string): Entry {
    const existing = entries.get(destination);
    if (existing) return existing;
    const entry: Entry = { handle: { close: () => undefined }, subscribers: new Set(), connected: false };
    entries.set(destination, entry);
    const live = () => entries.get(destination) === entry;
    entry.handle = open(destination, {
      onMessage: (body) => {
        if (!live()) return;
        let payload: unknown;
        try {
          payload = JSON.parse(body) as unknown;
        } catch {
          return; // malformed frame
        }
        for (const sub of [...entry.subscribers]) {
          try {
            sub.onMessage(payload);
          } catch {
            // One failing listener must not stop the others.
          }
        }
      },
      onConnected: (connected) => {
        if (!live()) return;
        entry.connected = connected;
        for (const sub of [...entry.subscribers]) sub.onStatus?.(connected);
      },
    });
    return entry;
  }

  return {
    subscribe(destination, subscriber) {
      const entry = ensure(destination);
      entry.subscribers.add(subscriber);
      subscriber.onStatus?.(entry.connected);
      let active = true;
      return () => {
        if (!active) return;
        active = false;
        entry.subscribers.delete(subscriber);
        if (entry.subscribers.size === 0 && entries.get(destination) === entry) {
          entries.delete(destination);
          entry.handle.close();
        }
      };
    },
    subscriberCount: (destination) => entries.get(destination)?.subscribers.size ?? 0,
    openCount: () => entries.size,
  };
}
