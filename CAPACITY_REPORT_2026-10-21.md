# Kashmir Menu: capacity and scalability report (2026-10-21)

Every number below is labelled **MEASURED** (a load test I ran and recorded), **CODE** (read from the source or configuration), or **ESTIMATE / ASSUMPTION** (reasoning, not a test). Anything not tested is listed in section 8. Nothing here is a guarantee for another machine.

## 1. How it was measured

- **Where:** a second, isolated copy of the stack (own empty MySQL volume, own ports, same images and settings as your running app) on this laptop: HP EliteBook, Ryzen 5 PRO 230, 12 logical cores, 15.3 GB RAM; Docker given 12 CPUs and 7.9 GB. Your real database and running containers were not touched. The copy has been deleted.
- **Tool:** k6 (`grafana/k6` in Docker) with the project's own scripts in `k6/`. The load generator, the app, nginx and MySQL all shared the same laptop, so every figure is conservative for the server alone, and real networks would add latency.
- **Changes to the test setup only:** rate limits raised (they are per client IP and all test traffic came from one address), a table visit allowed 200 guests, new restaurants not held for approval. Payment was cash (the app has no payment gateway; staff confirm money by hand).
- **App settings under test (CODE):** Spring Boot, Tomcat default 200 threads, JVM heap about 1.9 GB (default for the container), database pool 10 connections, MySQL 8.4 with the default 128 MB buffer pool, one app instance, one database.

## 2. Measured results

Workload ("floor" test): guests join a table, add a dish, place an order, and pay; staff list tables, confirm orders and payments, and close tables. Think time 0.5 to 2 s, far faster than real diners.

| Run | Concurrent guests + staff | Requests/s | Failed | Server errors (5xx) | p95 latency, all | p95 order-place | App CPU (of 12 cores) |
|---|---|---|---|---|---|---|---|
| Baseline, 1 table | 10 | 11 | 0 real (guest cap refused joins, by design) | 0 | 43 ms | 54 ms | not recorded |
| Floor, original code | 60 + 2 | 108 | 1.7 % | **486 (deadlocks)** | 54 ms | 96 ms | not recorded |
| Floor, after lock fix | 60 + 2 | 106 | 0 | 0 | 62 ms | 97 ms | not recorded |
| Floor | 150 + 6 | 321 | 0 | 0 | 70 ms | 115 ms | 3.9 cores |
| Floor, original nginx config | 400 + 10 | 462 | **13.7 %** | **19,164 (nginx 502s)** | 411 ms | 341 ms | 4.5 cores |
| Floor, after nginx keepalive | 400 + 10 | 618 | 0 | 0 | 103 ms | 172 ms | 4.4 cores |
| Floor, after both fixes | 1,000 + 15 | 726 | 0 | 0 | 634 ms | 849 ms | 4.7 cores |

Other measured facts:
- **Orders processed:** about 21.5 guest journeys (each includes an order and a payment) per second at 400 guests, about 18.5 per second at 1,000 guests: roughly **1,100 to 1,300 orders per minute** on this laptop before latency climbs; the order rate stopped growing between 400 and 1,000 guests.
- **Database connections:** the pool peaked at 11 connections in use (pool of 10 plus one); the database allows 151. No request waited for a connection (no pool timeouts logged).
- **Database size:** 81 MB after about 22,000 orders, 17,000 payments, 22,000 guest sessions: roughly **3.7 KB per order** including carts, notifications and sessions. MySQL's buffer pool hit rate was 99.998 % at that size (128 MB pool).
- **Memory:** app up to 1.3 GB, MySQL up to 0.6 GB, nginx under 25 MB.
- **Slow queries:** none over MySQL's 10-second default threshold. Query-level timing was not captured.

## 3. Bottlenecks found

1. **Two staff changing the same order at once deadlocked in MySQL** (MEASURED: 486 errors in one run; reproduced in a real-MySQL test). Cause: the order was read without a lock, so both requests inserted their history row (shared lock) and then both updated the order. **Fixed in source** (`OrderRepository.findByIdAndRestaurantForUpdate`, used by the kitchen and order-status services). Regression test `MysqlConcurrencyIT.twoStaffChangingTheSameOrderAtOnceNeverDeadlock`: fails without the fix (503 "table is busy" in round 0), passes with it.
2. **nginx ran out of local ports at about 450 requests/s** (MEASURED: `connect() failed (99: Address not available)`, 19,164 502 errors while the app was nearly idle). Cause: no connection reuse between nginx and the app. **Fixed in source** (`frontend/nginx.conf`: upstream with `keepalive 64`, HTTP/1.1). nginx CPU fell from 633 % to 38 %.
3. **The JVM is the next limit** (MEASURED): at 1,000 guests the app used about 4.7 of 12 cores and latency began to rise steeply (p95 634 ms overall, 849 ms for placing an order). MySQL used about 2.8 cores. This is one app instance; it is CPU-bound well before memory, database connections or the pool.
4. **Not binding yet, but will be:** MySQL's 128 MB buffer pool (CODE) is already the default for a database that will grow past that after about 35,000 orders (ESTIMATE from the 3.7 KB per order); photos are stored in MySQL and served by the app (CODE), which is expensive at scale.
5. **Test-tool findings, fixed:** the k6 seed built `/restaurants//payment-settings` (so no restaurant had payment settings and every payment in the old floor test would have been refused), and k6 2.x ignores the scripts' scenarios if `K6_VUS` or `K6_DURATION` are set (the scripts now accept `LOAD_VUS` and `LOAD_DURATION`).

## 4. Capacity table (this laptop, one instance, one database)

| Metric | Current capacity | Estimated maximum | Bottleneck |
|---|---|---|---|
| Registered customers | **No limit that matters**: diners have no accounts (CODE); each visit stores a guest session, about 3.7 KB per order | Database size, not users: about 1 million orders is about 3.7 GB (ESTIMATE) | Disk and MySQL memory, much later |
| Registered restaurants | No hard limit in the code (CODE); 11 were created in the test. Per-restaurant data is tenant-scoped | Limited by how many are *active at once* (see below) | Dashboard polling, not storage |
| Concurrent users | **1,000 concurrent guests with 15 staff, 0 failures, p95 under 1 s** (MEASURED). At 400 guests p95 was about 0.1 s | About 1,200 to 6,000 concurrent real diners (ESTIMATE: 700 requests/s ceiling divided by 0.1 to 0.5 requests/s per diner, from the screens' polling intervals) | One JVM instance's CPU |
| Orders per minute | **About 1,100 to 1,300** on this laptop (MEASURED, at unrealistic speed) | A busy restaurant places perhaps 1 to 5 per minute (ASSUMPTION), so order throughput is not the limit | JVM and MySQL CPU |
| API requests per second | **About 620 with p95 near 100 ms; about 730 with p95 near 0.6 to 0.85 s** (MEASURED) | About 750 (MEASURED ceiling on this hardware) | JVM CPU (about 4.7 cores used) |
| Database connections | Pool of 10; peak 11 used; none waited (MEASURED); server allows 151 (CODE) | The pool of 10 would become the limit on a faster app server (ESTIMATE) | `DB_POOL_MAX_SIZE` |

Restaurants managing orders at once (ESTIMATE, uses assumptions): each open staff screen refreshes every 5 to 30 s; two screens per restaurant is roughly 1 request/s, so about 300 to 600 restaurants could have dashboards open at once within the measured ceiling, if no diners were using the system. Polling, not order volume, is what limits the number of active restaurants. Per-IP rate limits in the code (CODE: 1,200 order-tracking reads per minute per address, 600 writes per minute) cap one restaurant's shared Wi-Fi at roughly 100 diners tracking orders at the same time; this was not tested because the test raised those limits.

## 5. Customers and restaurants at five sizes (ESTIMATES)

"Customers" here means diners in the restaurant at the same moment, since there are no customer accounts. Assumed 0.1 to 0.5 requests per second each from screen polling.

| Concurrent diners | Request rate | Restaurants (about 30 diners each) | Outcome on current setup | Evidence |
|---|---|---|---|---|
| 100 | 10 to 50/s | about 3 | Comfortable | Measured at far higher load |
| 1,000 | 100 to 500/s | about 35 | Comfortable | Measured: 1,000 guests, 0 failures, p95 0.6 s |
| 10,000 | 1,000 to 5,000/s | about 330 | **Overloaded**: above the 730/s ceiling, latency climbs into seconds, request queues fill, errors begin | Extrapolation, not tested |
| 50,000 | 5,000 to 25,000/s | about 1,700 | Needs several app servers, a bigger database, caching | Extrapolation |
| 100,000 | 10,000 to 50,000/s | about 3,300 | Needs a redesigned deployment (section 6) | Extrapolation |

## 6. Recommended improvements, by impact

1. **Deploy the two fixes** (lock fix and nginx keepalive). They removed all server errors in testing. They are in source only; your running containers still have the old behaviour.
2. **Raise MySQL's buffer pool** (`innodb_buffer_pool_size`, for example 25 to 50 % of the database host's memory) and give the app explicit JVM memory settings. Cheap, and needed before the data passes about 100 MB.
3. **Stop polling every few seconds from every screen.** The app already has a WebSocket channel for notifications; using pushed events to trigger refreshes (and slower fallback polling) would cut request volume several-fold. This raises the number of active diners and restaurants more than any hardware change.
4. **Cache the public menu and table lookups** for a few seconds (nginx or in-app), and serve dish photos through nginx caching or a CDN instead of reading the database blob per request.
5. **Run two or more app instances behind a load balancer** once CPU is regularly above about 60 %. This needs three code changes first, because the following are in memory per instance today (CODE): the rate limiters, the login failure counter, and the WebSocket message broker (`enableSimpleBroker`); scheduled jobs would also run on every instance. Use a shared store (Redis) for limits, a broker relay for WebSockets, and a leader lock for jobs.
6. **Database:** a managed MySQL with automated backups and a read replica for the analytics endpoints; indexes already exist (about 80 index or unique definitions across 43 migrations) and no slow queries appeared, but analytics were only tested on 22,000 orders.
7. **Observability:** the Prometheus and Grafana files exist; keep the pool-wait, heap and 5xx panels on during every load test.

**Rough monthly cost per stage (ESTIMATE; typical public pricing from memory, not looked up; verify with your provider):**

| Stage | Setup | About |
|---|---|---|
| Up to roughly 1,000 concurrent diners | One server with 4 vCPU and 8 GB RAM running everything, plus off-site backup | ₹3,000 to ₹8,000 |
| 1,000 to 5,000 | App server 8 vCPU, separate MySQL host or managed database, CDN for photos | ₹10,000 to ₹30,000 |
| 5,000 to 20,000 | 2 to 4 app servers, load balancer, managed MySQL with replica, Redis | ₹40,000 to ₹1,50,000 |
| 100,000 | Multi-node, horizontally scaled, plus engineering time for the changes above | Far above this; scope after real traffic data |

## 7. Final assessment

1. **How many customers can the app handle now?** On this laptop, about 1,000 concurrent diners with fast interactions and no failures (measured); realistically 1,200 to 6,000 active real diners at once (estimate). There is no limit on registered customers, because there are none.
2. **How many restaurants?** No hard limit in the code. Practically about 300 to 600 restaurants with live dashboards at once (estimate, dominated by polling); the database holds far more.
3. **How many can place orders simultaneously?** About 1,100 to 1,300 orders per minute (measured, 400 to 1,000 guests, 0 failures), far above what restaurants generate.
4. **10,000 customers at once?** Not tested. By extrapolation the request rate is 1.5 to 7 times the measured ceiling, so response times would rise to seconds, then timeouts and 5xx errors, and staff screens would lag. A single instance would not hold.
5. **For 100,000?** The changes in section 6 (pushed updates instead of polling, caching, photos off the database, several app servers with shared rate limiting and WebSocket relay, a managed database with replicas), then a load test of the new setup.
6. **Production-ready for large-scale use?** Ready for a modest number of restaurants on one server (measured with zero errors up to 1,000 concurrent guests after the two fixes). **Not ready for large scale** as it stands: it is a single instance with in-memory state, polling-heavy screens and photo storage in the database. The deadlock and proxy problems found here would have caused real errors under moderate load; they are fixed in source but not yet deployed.

## 8. Not tested, and what is needed

- **WebSocket/notification capacity** (concurrent connections, message fan-out). Needs a k6 WebSocket scenario against the STOMP endpoint.
- **Real diner behaviour** (polling mix, 5 to 60 s think times, phone networks, TLS). The test used faster-than-human think times; a polling-accurate scenario would replace my 0.1 to 0.5 requests/s per diner estimate with a measured one.
- **Photo-heavy menus** (images served from MySQL) and image upload bursts.
- **Analytics on large history** (hundreds of thousands of orders) and long soak runs (the existing `soak.js` runs two hours).
- **Run on production-class hardware** with the load generator on a separate machine; this laptop shared 12 cores between generator, proxy, app and database.
- **Per-IP rate limits** (turned off for the run), and **Hikari pool wait metrics** (not scraped; no timeouts were logged).
- **Payment provider behaviour**: there is no gateway; "UPI" is a manual claim and staff confirmation, so no external payment latency applies.

## 9. What changed in the project

`frontend/nginx.conf` (upstream keepalive); `OrderRepository`, `KitchenServiceImpl`, `OrderServiceImpl` (row lock on staff status changes); `MysqlConcurrencyIT` (new regression test); `k6/lib/api.js` (restaurant id from `/me`, UPI off), and `k6/*.js` (`LOAD_VUS`, `LOAD_DURATION`). Nothing was deployed to your running containers. Test infrastructure created for this work (the second stack and its database volume, two temporary images, the throwaway test database) was removed; the `grafana/k6` image remains for future runs.

## 10. Regression checks after the changes

Full backend suite (`gradlew test`, H2): BUILD SUCCESSFUL, 892 tests, 0 failures, 0 errors, 5 skipped (the MySQL-only ones, including the new deadlock test). Real-MySQL suite `MysqlConcurrencyIT` (`gradlew mysqlTest`): passed, 5 tests; the new test fails when the lock fix is removed. The full `mysqlTest` suite was not run.
