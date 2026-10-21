// Soak: does it still work after hours of modest load?
//
// Looks for leaks and slow degradation: climbing heap between collections, connections not being
// returned, caches growing without bound. The JVM dashboard matters more here than throughput.
//
// NOTE: this writes a lot of rows (orders, payments, audit entries). On a shared test database that
// is a disk-space and data-hygiene problem - see the cleanup section of
// PERFORMANCE_TESTING_GUIDE.md before running it overnight.
import { sleep } from 'k6';
import { assertLoadTestingAllowed, thresholdsFor, THRESHOLDS } from './lib/config.js';
import { seedRestaurant, dinerJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: '2m', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 10) },
    { duration: (__ENV.LOAD_DURATION || __ENV.K6_DURATION) || '2h', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 10) },
    { duration: '2m', target: 0 },
  ],
  thresholds: thresholdsFor({
    // Flat across the whole run: a soak that passes on average but degrades towards the end has
    // found exactly the thing a soak is for, so the threshold is applied to the full duration.
    'http_req_duration{name:public:order-place}': [`p(95)<${THRESHOLDS.orderPlace}`],
  }),
};

export function setup() {
  assertLoadTestingAllowed('soak');
  return seedRestaurant('soak');
}

export default function (shop) {
  if (!shop) return;
  dinerJourney(shop.qr, shop.menuItemId);
  sleep(Number(__ENV.THINK_TIME || 5));
}
