import { defineConfig, devices } from "@playwright/test";

// End-to-end tests drive the real application: the Spring Boot jar (which serves this app)
// against PostgreSQL, with the simulated card processor. Start it first, e.g.
//   RATE_LIMIT_ENABLED=false java -jar OnlineOrder/build/libs/*.jar --app.db.reset-on-start=true
// then: npm run e2e (E2E_BASE_URL defaults to http://localhost:8080).
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false, // the tests share one database and its limited stock
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [["github"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
