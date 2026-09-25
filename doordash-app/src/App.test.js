import { ApiError, checkout, isAmbiguous, money, toCents } from "./api";
import { stepIndex } from "./components/StatusSteps";

test("money is handled in integer cents", () => {
  expect(toCents("12.50")).toBe(1250);
  expect(toCents(0.1 + 0.2)).toBe(30); // no floating-point drift
  expect(money(2975)).toBe("$29.75");
});

test("a checkout retried after a network error reuses one Idempotency-Key", async () => {
  const keys = [];
  let calls = 0;
  const send = async (_path, init) => {
    keys.push(init.headers["Idempotency-Key"]);
    calls += 1;
    if (calls < 3) throw new TypeError("Failed to fetch");
    return { id: 7 };
  };
  const order = await checkout(1250, { send, sleep: () => Promise.resolve() });
  expect(order.id).toBe(7);
  expect(keys).toHaveLength(3);
  expect(new Set(keys).size).toBe(1);
});

test("business errors are not retried", async () => {
  let calls = 0;
  const send = async () => {
    calls += 1;
    throw new ApiError(409, "OUT_OF_STOCK", "sold out");
  };
  await expect(checkout(100, { send, sleep: () => Promise.resolve() })).rejects.toMatchObject({ code: "OUT_OF_STOCK" });
  expect(calls).toBe(1);
  expect(isAmbiguous(new ApiError(503))).toBe(true);
  expect(isAmbiguous(new ApiError(409))).toBe(false);
});

test("order status maps to the progress steps", () => {
  expect(stepIndex("PLACED")).toBe(0);
  expect(stepIndex("READY")).toBe(3);
  expect(stepIndex("CANCELLED")).toBe(-1);
});
