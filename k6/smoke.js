// Smoke test: does the application work at all?
//
// One virtual user, a handful of requests. Safe to run anywhere, including in CI against an
// ephemeral environment, and the only script here that is not gated by the environment guard -
// a single user is not load.
import { sleep } from 'k6';
import { BASE_URL, thresholdsFor, THRESHOLDS } from './lib/config.js';
import { seedRestaurant, dinerJourney, login, staffJourney } from './lib/api.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: thresholdsFor({
    // Generous: a cold JVM serves the first request slowly and that is not a regression.
    'http_req_duration{name:public:menu}': [`p(95)<${THRESHOLDS.menuRead * 3}`],
  }),
};

export default function () {
  const shop = seedRestaurant('smoke');
  if (!shop) {
    throw new Error(`Could not seed test data against ${BASE_URL}. Is the application running?`);
  }

  dinerJourney(shop.qr, shop.menuItemId);
  sleep(1);

  const token = login(shop.ownerEmail, shop.password);
  if (token) {
    staffJourney(token);
  }
}
