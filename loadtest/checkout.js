// HTTP load test: signed-in customers checking out, half of them competing for one limited dish.
// It measures the whole path (Tomcat, Spring Security with a database session, JSON, the
// checkout transaction), unlike CheckoutBenchmark, which calls the service directly.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e HOT_STOCK=400 loadtest/checkout.js
//
// The app must run with RATE_LIMIT_ENABLED=false (this is one client address signing in many
// accounts), and the hot dish's stock must equal HOT_STOCK when the test starts
// (loadtest/run.sh sets both up). Thresholds encode the SLO from docs/OPERATIONS.md.
import http from "k6/http";
import { check, fail } from "k6";
import { Counter, Trend } from "k6/metrics";
import exec from "k6/execution";

const BASE = __ENV.BASE_URL || "http://localhost:8080";
const HOT_DISH = 2;        // Truffle Smash Burger: limited stock, every buyer locks the same row
const COLD_DISH = 1;       // Double Smash Burger: unlimited, no shared row
const HOT_STOCK = Number(__ENV.HOT_STOCK || 400);
// One account per VU. VU ids are numbered across both scenarios, so there must be at least as
// many accounts as VUs in total; two VUs sharing an account would empty each other's cart.
const USERS = Number(__ENV.USERS || 100);
const JSON_HEADERS = { "Content-Type": "application/json" };

const placedHot = new Counter("orders_placed_hot");
const placedCold = new Counter("orders_placed_cold");
const soldOut = new Counter("checkout_sold_out");
const checkoutHot = new Trend("checkout_hot_ms", true);
const checkoutCold = new Trend("checkout_cold_ms", true);

export const options = {
  summaryTrendStats: ["avg", "min", "med", "max", "p(90)", "p(95)", "p(99)"],
  noCookiesReset: true, // keep each VU's SESSION cookie across iterations (k6 clears it by default)
  scenarios: {
    checkout: {
      executor: "ramping-vus",
      exec: "checkout",
      startVUs: 0,
      stages: [
        { duration: "15s", target: 16 },
        { duration: "30s", target: 32 },
        { duration: "30s", target: 64 },
        { duration: "10s", target: 0 },
      ],
    },
    browse: {
      executor: "constant-arrival-rate",
      exec: "browse",
      rate: 50,
      timeUnit: "1s",
      duration: "85s",
      preAllocatedVUs: 20,
    },
  },
  thresholds: {
    // SLO: checkout p99 < 300 ms. Sold out (409) is an expected answer, not a failure.
    "http_req_duration{name:checkout}": ["p(99)<300"],
    "http_req_failed{name:checkout}": ["rate<0.01"],
    "http_req_duration{name:menu}": ["p(95)<100"],
    checks: ["rate>0.99"],
  },
};

export function setup() {
  const password = "load-test-password";
  const run = Date.now().toString(36);
  const users = [];
  for (let i = 0; i < USERS; i++) {
    const email = `load-${run}-${i}@test.com`;
    const r = http.post(`${BASE}/signup`, JSON.stringify({ email, password }), { headers: JSON_HEADERS });
    if (r.status !== 201) fail(`signup failed: ${r.status} ${r.body}`);
    users.push({ email, password });
  }
  return { users };
}

// Each VU signs in once; k6 keeps the SESSION cookie in the VU's cookie jar.
let signedIn = false;
function signIn(users) {
  if (signedIn) return;
  const u = users[exec.vu.idInTest - 1];
  if (!u) fail(`VU ${exec.vu.idInTest} has no account of its own: raise USERS`);
  const r = http.post(`${BASE}/login`, { username: u.email, password: u.password }, { tags: { name: "login" } });
  if (r.status !== 200) fail(`login failed: ${r.status}`);
  http.post(`${BASE}/cart/clear`, null, { tags: { name: "cart" } });
  signedIn = true;
}

export function checkout(data) {
  signIn(data.users);
  const hot = Math.random() < 0.5;
  const add = http.post(`${BASE}/cart`, JSON.stringify({ menu_id: hot ? HOT_DISH : COLD_DISH }),
    { headers: JSON_HEADERS, tags: { name: "add_to_cart" } });
  check(add, { "added to cart": (r) => r.status === 200 });

  const key = `${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${Math.random()}`;
  const r = http.post(`${BASE}/orders`, "{}", {
    headers: { ...JSON_HEADERS, "Idempotency-Key": key },
    tags: { name: "checkout" },
    responseCallback: http.expectedStatuses(201, 409),
  });
  const ok = check(r, {
    "order placed or sold out": (res) => res.status === 201 || (res.status === 409 && res.json("error") === "OUT_OF_STOCK"),
  });
  if (!ok) console.warn(`unexpected checkout answer ${r.status}: ${r.body}`);
  (hot ? checkoutHot : checkoutCold).add(r.timings.duration);
  if (r.status === 201) {
    (hot ? placedHot : placedCold).add(1);
  } else {
    if (ok) soldOut.add(1);
    http.post(`${BASE}/cart/clear`, null, { tags: { name: "cart" } }); // the dish stayed in the cart
  }
}

export function browse() {
  const r = http.get(`${BASE}/restaurants/menu`, { tags: { name: "menu" } });
  check(r, { "menu served": (res) => res.status === 200 });
  http.get(`${BASE}/inventory`, { tags: { name: "inventory" } });
}

export function teardown() {
  const left = http.get(`${BASE}/inventory`).json()[String(HOT_DISH)];
  console.log(`hot dish: started with ${HOT_STOCK}, ${left} left`);
  if (left < 0) fail("stock went negative");
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, stat) => (m[name] ? m[name].values[stat] : 0);
  const hotSold = v("orders_placed_hot", "count");
  const line = (label, name) =>
    `${label.padEnd(30)} p50 ${v(name, "med").toFixed(1).padStart(7)} ms   p95 ${v(name, "p(95)").toFixed(1).padStart(7)} ms   p99 ${v(name, "p(99)").toFixed(1).padStart(7)} ms`;
  const summary = [
    "",
    `orders placed: ${v("orders_placed_hot", "count") + v("orders_placed_cold", "count")} ` +
      `(${(v("orders_placed_hot", "rate") + v("orders_placed_cold", "rate")).toFixed(1)}/s); ` +
      `hot dish sold ${hotSold} of ${HOT_STOCK}; sold-out answers ${v("checkout_sold_out", "count")}`,
    `oversold: ${hotSold > HOT_STOCK ? "YES" : "no"}`,
    line("checkout, unlimited dish", "checkout_cold_ms"),
    line("checkout, contended dish", "checkout_hot_ms"),
    line("checkout, all (SLO p99<300)", "http_req_duration{name:checkout}"),
    line("menu", "http_req_duration{name:menu}"),
    `http requests: ${v("http_reqs", "count")} (${v("http_reqs", "rate").toFixed(0)}/s), checks passed ${(v("checks", "rate") * 100).toFixed(2)}%`,
    "",
  ].join("\n");
  return { stdout: summary, "loadtest/last-summary.json": JSON.stringify(data, null, 2) };
}
