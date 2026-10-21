// Spike: what happens when a queue of diners all scan at once?
//
// A sudden jump rather than a ramp. Watch recovery after the spike as closely as behaviour during
// it: the Database dashboard's pending-connections panel is usually where this shows first.
import { sleep } from 'k6';
import { assertLoadTestingAllowed, THRESHOLDS } from './lib/config.js';
import { seedRestaurant, dinerJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '30s', target: 5 },
    { duration: '10s', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 80) },
    { duration: '1m', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 80) },
    { duration: '10s', target: 5 },
    // Held at low load deliberately, to see whether it recovers.
    { duration: '1m', target: 5 },
  ],
  thresholds: {
    http_req_failed: [{ threshold: `rate<${THRESHOLDS.errorRate * 5}`, abortOnFail: false }],
  },
};

export function setup() {
  assertLoadTestingAllowed('spike');
  return seedRestaurant('spike');
}

export default function (shop) {
  if (!shop) return;
  dinerJourney(shop.qr, shop.menuItemId);
  sleep(1);
}
