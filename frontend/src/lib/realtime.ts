import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { getToken } from '../api/client';
import { createTopicHub, type OpenTransport, type TopicSubscriber } from './topicHub';

const BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export const PLATFORM_TOPIC = '/topic/platform';
export const restaurantTopic = (restaurantId: number) => `/topic/restaurants/${restaurantId}`;

// The real transport: STOMP over SockJS. The server authenticates the CONNECT frame and only allows a
// subscription to the subscriber's own restaurant (and /topic/platform to the platform owner), so the
// token travels with the handshake. It is read on each connect rather than captured once, so a
// reconnect after a new sign-in uses the current one.
const openStomp: OpenTransport = (destination, events) => {
  const client = new Client({
    webSocketFactory: () => new SockJS(`${BASE}/ws`),
    reconnectDelay: 5000,
    beforeConnect: () => {
      client.connectHeaders = { Authorization: `Bearer ${getToken() ?? ''}` };
    },
    onConnect: () => {
      events.onConnected(true);
      client.subscribe(destination, (frame) => events.onMessage(frame.body));
    },
    onWebSocketClose: () => events.onConnected(false),
    // The server sends an ERROR frame and closes when the token is missing, expired or revoked, or
    // when the topic is not the subscriber's to read. Callers fall back to polling.
    onStompError: () => events.onConnected(false),
  });
  client.activate();
  return { close: () => void client.deactivate() };
};

/** One hub per tab: every hook and the notification centre share a single socket per topic. */
const hub = createTopicHub(openStomp);

export function subscribeTopic(destination: string, subscriber: TopicSubscriber): () => void {
  return hub.subscribe(destination, subscriber);
}
