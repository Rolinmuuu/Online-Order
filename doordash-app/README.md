# Online Order: web client

React 18 + TypeScript, built with Vite, UI components from antd. It talks to the Spring Boot
API on the same origin (session cookie, `SameSite=Strict`), and receives live order updates
over Server-Sent Events.

| | |
|---|---|
| `src/api.ts` | every API call, the idempotent checkout retry, error messages, the SSE subscription |
| `src/types.ts` | the API's JSON shapes, mirroring the backend DTOs |
| `src/components/` | menu and cart, order page (payment, live status), order history, kitchen board |
| `e2e/` | Playwright tests against the running application |

```bash
npm ci
npm run dev          # http://localhost:3000, proxies the API to the backend on :8080
npm run lint && npm run typecheck && npm test
npm run build        # to ./build; Gradle packages it into the jar (OnlineOrder/build.gradle)
npm run e2e          # needs the app running on :8080 (see playwright.config.ts)
```

The built files are not committed. `./gradlew bootJar -PwithFrontend` in `OnlineOrder/` runs
the build and packages it; the Docker image does the same in its first stage.
