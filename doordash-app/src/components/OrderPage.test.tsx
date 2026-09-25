import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { expect, test } from "vitest";
import { mockFetch } from "../test/fetchMock";
import type { Order } from "../types";
import OrderPage from "./OrderPage";

const order = (over: Partial<Order> = {}): Order => ({
  id: 42,
  status: "PLACED",
  customer_id: 1,
  restaurant_id: 1,
  restaurant_name: "Ember & Bun",
  total_cents: 1250,
  pay_by: new Date(Date.now() + 10 * 60_000).toISOString(),
  created_at: new Date().toISOString(),
  lines: [{ menu_item_id: 1, name: "Double Smash Burger", unit_price_cents: 1250, quantity: 1 }],
  events: [{ to_status: "PLACED", actor: "customer", at: new Date().toISOString() }],
  ...over,
});

const renderAt = () =>
  render(
    <MemoryRouter initialEntries={["/orders/42"]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/orders/:id" element={<OrderPage />} />
      </Routes>
    </MemoryRouter>,
  );

test("a declined card is explained and the customer can pay again", async () => {
  mockFetch({
    "GET /orders/42": () => ({ body: order({ payment_status: "FAILED", payment_failure_reason: "insufficient_funds" }) }),
  });
  renderAt();

  expect(await screen.findByRole("alert")).toHaveTextContent("Your card has insufficient funds.");
  expect(screen.getByText(/Nothing was charged/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: /Pay \$12\.50/ })).toBeEnabled();
});

test("paying sends the chosen test card's token", async () => {
  const calls = mockFetch({
    "GET /orders/42": () => ({ body: order() }),
    "POST /orders/42/pay": () => ({ body: { order_id: 42, status: "PENDING", amount_cents: 1250, payment_ref: "pay_1" } }),
  });
  renderAt();

  await userEvent.click(await screen.findByLabelText(/0002/));
  await userEvent.click(screen.getByRole("button", { name: /Pay \$12\.50/ }));

  await waitFor(() => expect(calls.some((c) => c.key === "POST /orders/42/pay")).toBe(true));
  const pay = calls.find((c) => c.key === "POST /orders/42/pay");
  expect(JSON.parse(String(pay?.init?.body))).toEqual({ payment_method: "tok_chargeDeclined" });
});

test("a paid order shows no payment form", async () => {
  mockFetch({ "GET /orders/42": () => ({ body: order({ status: "PAID", payment_status: "CAPTURED" }) }) });
  renderAt();
  expect(await screen.findByText("Ember & Bun")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Pay/ })).not.toBeInTheDocument();
});
