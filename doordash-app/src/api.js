// All HTTP calls to the Spring Boot API. Session cookie auth (Spring Security form login).

export class ApiError extends Error {
  constructor(status, code, message, body) {
    super(message || code || `HTTP ${status}`);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

async function request(path, { method = "GET", body, headers = {}, form } = {}) {
  const init = { method, headers: { ...headers }, credentials: "same-origin" };
  if (form) {
    init.body = form;
  } else if (body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  const text = await res.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    throw new ApiError(res.status, data && data.error, data && data.message, data);
  }
  return data;
}

// ───────── money ─────────
export const toCents = (price) => Math.round(Number(price) * 100);
export const money = (cents) =>
  (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });

// ───────── session ─────────
// Credentials go in a form body, not the query string (query strings end up in access logs).
export const login = (email, password) =>
  request("/login", { method: "POST", form: new URLSearchParams({ username: email, password }) });
export const logout = () => request("/logout", { method: "POST" });
export const signup = (data) => request("/signup", { method: "POST", body: data });
export const me = () => request("/me");

// ───────── menu, stock, cart ─────────
export const getRestaurants = () => request("/restaurants/menu");
export const getInventory = () => request("/inventory");
export const getCart = () => request("/cart");
export const addToCart = (menuId) => request("/cart", { method: "POST", body: { menu_id: menuId } });
export const clearCart = () => request("/cart/clear", { method: "POST" });

// ───────── orders ─────────
export function newIdempotencyKey() {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

// Retry only when we cannot know whether the server acted: no response at all, or a gateway error.
export const isAmbiguous = (e) => !(e instanceof ApiError) || [502, 503, 504].includes(e.status);

/**
 * Places the order. One Idempotency-Key per click, reused on every retry, so a dropped
 * connection can never create two orders: the server replays the first result.
 */
export async function checkout(expectedTotalCents, { attempts = 3, send = request, sleep } = {}) {
  const key = newIdempotencyKey();
  const wait = sleep || ((ms) => new Promise((r) => setTimeout(r, ms)));
  for (let attempt = 1; ; attempt++) {
    try {
      return await send("/orders", {
        method: "POST",
        headers: { "Idempotency-Key": key },
        body: { expected_total_cents: expectedTotalCents },
      });
    } catch (e) {
      if (attempt >= attempts || !isAmbiguous(e)) throw e;
      await wait(500 * 2 ** (attempt - 1));
    }
  }
}

export const getOrders = () => request("/orders");
export const getOrder = (id) => request(`/orders/${id}`);
export const cancelOrder = (id) => request(`/orders/${id}/cancel`, { method: "POST" });
export const payOrder = (id) => request(`/orders/${id}/pay`, { method: "POST" });

// ───────── kitchen ─────────
export const kitchenRestaurants = () => request("/kitchen/restaurants");
export const kitchenBoard = (rid) => request(`/kitchen/restaurants/${rid}/orders`);
export const kitchenAction = (orderId, action) => request(`/kitchen/orders/${orderId}/${action}`, { method: "POST" });

// ───────── live updates ─────────
/**
 * Subscribes to Server-Sent Events; returns an unsubscribe function.
 *
 * "ready" arrives on every (re)connect and whenever the server's listener had to reconnect:
 * the caller re-fetches, because updates may have been missed meanwhile. EventSource retries
 * dropped connections itself, but gives up for good on an HTTP error (e.g. 401 after a server
 * restart), so we reopen it ourselves every 5 s. onStatus(true|false) drives the "Live" badge.
 */
export function subscribe(path, onUpdate, onReconnect, onStatus = () => {}) {
  if (typeof EventSource === "undefined") return () => {};
  let es = null;
  let timer = null;
  let closed = false;
  const open = () => {
    es = new EventSource(path, { withCredentials: true });
    es.addEventListener("order", (e) => {
      try {
        onUpdate(JSON.parse(e.data));
      } catch {
        /* ignore malformed event */
      }
    });
    es.addEventListener("ready", () => {
      onStatus(true);
      if (onReconnect) onReconnect();
    });
    es.onerror = () => {
      onStatus(false);
      if (es.readyState === EventSource.CLOSED && !closed) {
        timer = setTimeout(open, 5000);
      }
    };
  };
  open();
  return () => {
    closed = true;
    clearTimeout(timer);
    if (es) es.close();
  };
}

export const STATUS_LABELS = {
  PLACED: "Awaiting payment",
  PAID: "Paid",
  ACCEPTED: "Preparing",
  READY: "Ready for pick-up",
  COMPLETED: "Picked up",
  CANCELLED: "Cancelled",
};
