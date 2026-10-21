// Stress: where does it break?
//
// Steps up past the expected peak to find the point where latency or errors degrade. Expect
// thresholds to fail - that is the information being gathered, not a fault.
import { sleep } from 'k6';
import { assertLoadTestingAllowed, THRESHOLDS } from './lib/config.js';
import { seedRestaurant, dinerJourney } from './lib/api.js';

const peak = Number((__ENV.LOAD_VUS || __ENV.K6_VUS) || 60);

export const options = {
  stages: [
    { duration: '1m', target: Math.round(peak * 0.25) },
    { duration: '2m', target: Math.round(peak * 0.5) },
    { duration: '2m', target: peak },
    { duration: '2m', target: 0 },
  ],
  thresholds: {
    // Recorded, not enforced: abortOnFail would stop the test before it found the limit.
    http_req_failed: [{ threshold: `rate<${THRESHOLDS.errorRate}`, abortOnFail: false }],
  },
};

export function setup() {
  assertLoadTestingAllowed('stress');
  return seedRestaurant('stress');
}

export default function (shop) {
  if (!shop) return;
  dinerJourney(shop.qr, shop.menuItemId);
  sleep(1);
}
