// Authenticated staff APIs: the dashboards and analytics a manager actually opens.
//
// Separate from the diner journey because the cost profile is different - these aggregate over a
// date range, where the diner path is mostly single-row reads and writes.
import { sleep } from 'k6';
import { assertLoadTestingAllowed, thresholdsFor, THRESHOLDS } from './lib/config.js';
import { seedRestaurant, login, staffJourney, dinerJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '30s', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 5) },
    { duration: (__ENV.LOAD_DURATION || __ENV.K6_DURATION) || '2m', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 5) },
    { duration: '20s', target: 0 },
  ],
  thresholds: thresholdsFor({
    'http_req_duration{name:staff:payment-analytics}': [`p(95)<${THRESHOLDS.staffRead}`],
    'http_req_duration{name:staff:dashboard}': [`p(95)<${THRESHOLDS.staffRead}`],
  }),
};

export function setup() {
  assertLoadTestingAllowed('authenticated');
  const shop = seedRestaurant('auth');
  if (shop) {
    // A few orders, so the analytics queries have something to aggregate rather than measuring
    // the cost of returning zero rows.
    for (let i = 0; i < 5; i++) {
      dinerJourney(shop.qr, shop.menuItemId);
    }
  }
  return shop;
}

export default function (shop) {
  if (!shop) return;
  // Signing in each iteration deliberately: it exercises the password hash, which is a real and
  // intentionally expensive part of the request path.
  const token = login(shop.ownerEmail, shop.password);
  if (token) {
    staffJourney(token);
  }
  sleep(Number(__ENV.THINK_TIME || 2));
}
