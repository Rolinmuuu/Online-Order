import { vi } from "vitest";

type Handler = (init: RequestInit | undefined) => { status?: number; body?: unknown };

/** Replaces fetch with canned responses by "METHOD path"; unknown routes are 404s. */
export function mockFetch(routes: Record<string, Handler>) {
  const calls: { key: string; init?: RequestInit }[] = [];
  vi.stubGlobal("fetch", vi.fn(async (path: string, init?: RequestInit) => {
    const key = `${init?.method ?? "GET"} ${path}`;
    calls.push({ key, init });
    const handler = routes[key];
    const { status = 200, body } = handler ? handler(init) : { status: 404, body: { error: "NOT_FOUND" } };
    return new Response(body === undefined ? "" : JSON.stringify(body), { status });
  }));
  return calls;
}
