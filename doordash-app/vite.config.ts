import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// Paths served by the Spring Boot API. The dev server proxies them to :8080, so the browser
// talks to one origin and the SameSite=Strict session cookie works exactly as in production.
const API = ["/login", "/logout", "/signup", "/me", "/restaurants", "/inventory", "/cart", "/orders", "/kitchen", "/payments"];

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: Object.fromEntries(API.map((path) => [path, { target: "http://localhost:8080", changeOrigin: false }])),
  },
  build: {
    // Gradle copies this directory into the jar (OnlineOrder/build.gradle, processResources).
    outDir: "build",
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    include: ["src/**/*.test.{ts,tsx}"],
    css: false,
  },
});
