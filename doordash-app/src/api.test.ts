import { describe, expect, test } from "vitest";
import { ApiError, checkout, declineMessage, describeError, isAmbiguous, money, toCents, type Send } from "./api";
import { stepIndex } from "./components/StatusSteps";

test("money is handled in integer cents", () => {
  expect(toCents("12.50")).toBe(1250);
  expect(toCents(0.1 + 0.2)).toBe(30); // no floating-point drift
  expect(money(2975)).toBe("$29.75");
});

describe("checkout", () => {
  const noSleep = () => Promise.resolve();

  test("a checkout retried after a network error reuses one Idempotency-Key", async () => {
    const keys: string[] = [];
    let calls = 0;
    const send = (async (_path, init) => {
      keys.push(init?.headers?.["Idempotency-Key"] ?? "");
      calls += 1;
      if (calls < 3) throw new TypeError("Failed to fetch");
      return { id: 7 };
    }) as Send;
    const order = await checkout(1250, { send, sleep: noSleep });
    expect(order.id).toBe(7);
    expect(keys).toHaveLength(3);
    expect(new Set(keys).size).toBe(1);
  });

  test("business errors are not retried", async () => {
    let calls = 0;
    const send = (async () => {
      calls += 1;
      throw new ApiError(409, "OUT_OF_STOCK", "sold out");
    }) as Send;
    await expect(checkout(100, { send, sleep: noSleep })).rejects.toMatchObject({ code: "OUT_OF_STOCK" });
    expect(calls).toBe(1);
    expect(isAmbiguous(new ApiError(503))).toBe(true);
    expect(isAmbiguous(new ApiError(409))).toBe(false);
  });

  test("each click gets a fresh key", async () => {
    const keys: string[] = [];
    const send = (async (_path, init) => {
      keys.push(init?.headers?.["Idempotency-Key"] ?? "");
      return { id: 1 };
    }) as Send;
    await checkout(100, { send });
    await checkout(100, { send });
    expect(keys[0]).not.toBe(keys[1]);
  });
});

describe("describeError turns API errors into something a customer can act on", () => {
  test("rate limiting", () => {
    expect(describeError(new ApiError(429, "RATE_LIMITED", "too many requests"))).toMatch(/wait a minute/);
  });
  test("validation shows the first field message", () => {
    const e = new ApiError(400, "VALIDATION_FAILED", "invalid", { fields: { password: "size must be between 8 and 128" } });
    expect(describeError(e)).toBe("size must be between 8 and 128");
  });
  test("no response at all is a connection problem", () => {
    expect(describeError(new TypeError("Failed to fetch"))).toMatch(/connection/);
  });
  test("other errors use the server's message", () => {
    expect(describeError(new ApiError(409, "TOO_LATE_TO_CANCEL", "the restaurant is already working on this order")))
      .toBe("the restaurant is already working on this order");
  });
});

test("decline reasons have customer-facing messages", () => {
  expect(declineMessage("insufficient_funds")).toMatch(/insufficient funds/);
  expect(declineMessage("something_new")).toMatch(/didn't go through/);
});

test("order status maps to the progress steps", () => {
  expect(stepIndex("PLACED")).toBe(0);
  expect(stepIndex("READY")).toBe(3);
  expect(stepIndex("CANCELLED")).toBe(-1);
});
