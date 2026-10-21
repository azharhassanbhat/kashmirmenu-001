// Baseline load: what does normal traffic look like?
//
// Run this FIRST and set the real thresholds from its results. The values in lib/config.js are
// hypotheses until this has been run against representative data.
import { sleep } from 'k6';
import { assertLoadTestingAllowed, thresholdsFor, THRESHOLDS } from './lib/config.js';
import { seedRestaurant, dinerJourney } from './lib/api.js';

export const options = {
  stages: [
    { duration: __ENV.RAMP_UP || '30s', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 10) },
    { duration: (__ENV.LOAD_DURATION || __ENV.K6_DURATION) || '3m', target: Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 10) },
    { duration: '30s', target: 0 },
  ],
  thresholds: thresholdsFor({
    'http_req_duration{name:public:menu}': [`p(95)<${THRESHOLDS.menuRead}`],
    'http_req_duration{name:public:cart-add}': [`p(95)<${THRESHOLDS.cartWrite}`],
    'http_req_duration{name:public:order-place}': [`p(95)<${THRESHOLDS.orderPlace}`],
  }),
};

export function setup() {
  assertLoadTestingAllowed('baseline');
  // Seeded once, shared by every VU: the point is to load the ordering path, not registration.
  return seedRestaurant('baseline');
}

export default function (shop) {
  if (!shop) return;
  dinerJourney(shop.qr, shop.menuItemId);
  // Think time. Without it a VU is a tight loop, which measures nothing a real diner does.
  sleep(Number(__ENV.THINK_TIME || 3));
}
