// All HTTP calls to the Spring Boot API. Session cookie auth (Spring Security form login).
import type {
  Cart,
  ErrorBody,
  KitchenBoard,
  Me,
  Order,
  OrderStatus,
  OrderUpdate,
  Payment,
  Restaurant,
  Stock,
} from "./types";

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly body?: ErrorBody;

  constructor(status: number, code?: string, message?: string, body?: ErrorBody) {
    super(message || code || `HTTP ${status}`);
    this.status = status;
    this.code = code;
    this.body = body;
  }
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  form?: URLSearchParams;
}

export type Send = <T>(path: string, options?: RequestOptions) => Promise<T>;

export async function request<T>(path: string, { method = "GET", body, headers = {}, form }: RequestOptions = {}): Promise<T> {
  const init: RequestInit & { headers: Record<string, string> } = { method, headers: { ...headers }, credentials: "same-origin" };
  if (form) {
    init.body = form;
  } else if (body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = text;
  }
  if (!res.ok) {
    const err = (data && typeof data === "object" ? data : {}) as ErrorBody;
    throw new ApiError(res.status, err.error, err.message, err);
  }
  return data as T;
}

/** A message a customer can act on, for any failed call. */
export function describeError(e: unknown, fallback = "Something went wrong, please try again"): string {
  if (!(e instanceof ApiError)) return "Can't reach the server. Check your connection and try again.";
  switch (e.code) {
    case "RATE_LIMITED":
      return "Too many attempts. Please wait a minute and try again.";
    case "EMAIL_TAKEN":
      return "An account with this e-mail already exists. Sign in instead.";
    case "VALIDATION_FAILED":
      return Object.values(e.body?.fields ?? {})[0] ?? "Some fields are invalid.";
    default:
      return e.message || fallback;
  }
}

// ───────── money ─────────
export const toCents = (price: number | string): number => Math.round(Number(price) * 100);
export const money = (cents: number): string =>
  (cents / 100).toLocaleString("en-US", { style: "currency", currency: "USD" });

// ───────── session ─────────
// Credentials go in a form body, not the query string (query strings end up in access logs).
export const login = (email: string, password: string) =>
  request<void>("/login", { method: "POST", form: new URLSearchParams({ username: email, password }) });
export const logout = () => request<void>("/logout", { method: "POST" });
export const signup = (data: { email: string; password: string; first_name?: string; last_name?: string }) =>
  request<void>("/signup", { method: "POST", body: data });
export const me = () => request<Me>("/me");

// ───────── menu, stock, cart ─────────
export const getRestaurants = () => request<Restaurant[]>("/restaurants/menu");
export const getInventory = () => request<Stock>("/inventory");
export const getCart = () => request<Cart>("/cart");
export const addToCart = (menuId: number) => request<void>("/cart", { method: "POST", body: { menu_id: menuId } });
export const clearCart = () => request<void>("/cart/clear", { method: "POST" });

// ───────── orders ─────────
export function newIdempotencyKey(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}-${Math.random().toString(36).slice(2)}`;
}

// Retry only when we cannot know whether the server acted: no response at all, or a gateway error.
export const isAmbiguous = (e: unknown): boolean => !(e instanceof ApiError) || [502, 503, 504].includes(e.status);

/**
 * Places the order. One Idempotency-Key per click, reused on every retry, so a dropped
 * connection can never create two orders: the server replays the first result.
 */
export async function checkout(
  expectedTotalCents: number,
  { attempts = 3, send = request as Send, sleep }: { attempts?: number; send?: Send; sleep?: (ms: number) => Promise<void> } = {},
): Promise<Order> {
  const key = newIdempotencyKey();
  const wait = sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)));
  for (let attempt = 1; ; attempt++) {
    try {
      return await send<Order>("/orders", {
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

export const getOrders = () => request<Order[]>("/orders");
export const getOrder = (id: number | string) => request<Order>(`/orders/${id}`);
export const cancelOrder = (id: number) => request<Order>(`/orders/${id}/cancel`, { method: "POST" });

/**
 * Test cards of the simulated processor. A real integration would get this token from the
 * processor's own card form in the browser; card numbers never touch our server.
 */
export const TEST_CARDS = [
  { token: "tok_visa", label: "Visa ···· 4242", note: "approved" },
  { token: "tok_chargeDeclined", label: "Visa ···· 0002", note: "declined" },
  { token: "tok_chargeDeclinedInsufficientFunds", label: "Visa ···· 9995", note: "insufficient funds" },
] as const;

export const payOrder = (id: number, paymentMethod: string = TEST_CARDS[0].token) =>
  request<Payment>(`/orders/${id}/pay`, { method: "POST", body: { payment_method: paymentMethod } });

const DECLINE_MESSAGES: Record<string, string> = {
  card_declined: "Your card was declined.",
  insufficient_funds: "Your card has insufficient funds.",
  invalid_payment_method: "This card can't be used.",
};
export const declineMessage = (reason?: string): string =>
  (reason && DECLINE_MESSAGES[reason]) || "The payment didn't go through.";

// ───────── kitchen ─────────
export type KitchenActionName = "accept" | "ready" | "complete" | "reject";
export const kitchenRestaurants = () => request<Restaurant[]>("/kitchen/restaurants");
export const kitchenBoard = (rid: number) => request<KitchenBoard>(`/kitchen/restaurants/${rid}/orders`);
export const kitchenAction = (orderId: number, action: KitchenActionName) =>
  request<Order>(`/kitchen/orders/${orderId}/${action}`, { method: "POST" });

// ───────── live updates ─────────
/**
 * Subscribes to Server-Sent Events; returns an unsubscribe function.
 *
 * "ready" arrives on every (re)connect and whenever the server's listener had to reconnect:
 * the caller re-fetches, because updates may have been missed meanwhile. EventSource retries
 * dropped connections itself, but gives up for good on an HTTP error (e.g. 401 after a server
 * restart), so we reopen it ourselves every 5 s. onStatus(true|false) drives the "Live" badge.
 */
export function subscribe(
  path: string,
  onUpdate: (u: OrderUpdate) => void,
  onReconnect?: () => void,
  onStatus: (live: boolean) => void = () => {},
): () => void {
  if (typeof EventSource === "undefined") return () => {};
  let es: EventSource | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let closed = false;
  const open = () => {
    es = new EventSource(path, { withCredentials: true });
    es.addEventListener("order", (e) => {
      try {
        onUpdate(JSON.parse((e as MessageEvent<string>).data) as OrderUpdate);
      } catch {
        /* ignore malformed event */
      }
    });
    es.addEventListener("ready", () => {
      onStatus(true);
      onReconnect?.();
    });
    es.onerror = () => {
      onStatus(false);
      if (es?.readyState === EventSource.CLOSED && !closed) {
        timer = setTimeout(open, 5000);
      }
    };
  };
  open();
  return () => {
    closed = true;
    clearTimeout(timer);
    es?.close();
  };
}

export const STATUS_LABELS: Record<OrderStatus, string> = {
  PLACED: "Awaiting payment",
  PAID: "Paid",
  ACCEPTED: "Preparing",
  READY: "Ready for pick-up",
  COMPLETED: "Picked up",
  CANCELLED: "Cancelled",
};
