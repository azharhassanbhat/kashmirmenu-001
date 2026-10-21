import { useEffect, useRef, useState } from 'react';
import type { PlatformRealtimeEvent, RealtimeMessage } from '../api/types';
import { isPlatformEvent, isRealtimeMessage } from './notificationCenter';
import { PLATFORM_TOPIC, restaurantTopic, subscribeTopic } from './realtime';

// Subscribes to /topic/restaurants/{id} through the shared connection hub: however many components ask
// for the same restaurant, the tab holds one socket, opened by the first and closed by the last.
// Callers should keep polling as a fallback: the socket auto-reconnects but messages sent while
// disconnected are not replayed.
export function useRestaurantTopic(
  restaurantId: number | null | undefined,
  onMessage: (message: RealtimeMessage) => void,
): boolean {
  const [connected, setConnected] = useState(false);
  const handler = useRef(onMessage);
  handler.current = onMessage;

  useEffect(() => {
    if (!restaurantId) return;
    const unsubscribe = subscribeTopic(restaurantTopic(restaurantId), {
      onMessage: (payload) => {
        if (isRealtimeMessage(payload)) handler.current(payload);
      },
      onStatus: setConnected,
    });
    return () => {
      unsubscribe();
      setConnected(false);
    };
  }, [restaurantId]);

  return connected;
}

/** Subscribes to /topic/platform (the platform owner only; the server refuses everyone else). */
export function usePlatformTopic(enabled: boolean, onMessage: (event: PlatformRealtimeEvent) => void): boolean {
  const [connected, setConnected] = useState(false);
  const handler = useRef(onMessage);
  handler.current = onMessage;

  useEffect(() => {
    if (!enabled) return;
    const unsubscribe = subscribeTopic(PLATFORM_TOPIC, {
      onMessage: (payload) => {
        if (isPlatformEvent(payload)) handler.current(payload);
      },
      onStatus: setConnected,
    });
    return () => {
      unsubscribe();
      setConnected(false);
    };
  }, [enabled]);

  return connected;
}
