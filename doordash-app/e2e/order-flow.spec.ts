import { expect, test } from "@playwright/test";
import { CUSTOMER, KITCHEN, placeOrder, signedIn } from "./helpers";

test("a customer orders and pays; the kitchen sees it live and the customer sees the kitchen's progress live", async ({ browser }) => {
  const customer = await signedIn(browser, CUSTOMER);
  const kitchen = await signedIn(browser, KITCHEN);
  await kitchen.getByRole("link", { name: "Kitchen" }).click();
  await expect(kitchen.getByText("Live")).toBeVisible();

  const orderId = await placeOrder(customer, "Double Smash Burger");
  await expect(customer.locator(".status-pill")).toHaveText(/Awaiting payment/);
  await customer.getByRole("button", { name: /Pay \$12\.50/ }).click();

  // The simulated processor answers with a signed webhook (twice); the page updates without a reload.
  await expect(customer.locator(".status-pill")).toHaveText(/Paid/);

  // The kitchen board, open in another session, receives the paid order over Server-Sent Events.
  const ticket = kitchen.locator(".ticket", { hasText: `#${orderId}` });
  await expect(ticket).toBeVisible();
  await ticket.getByRole("button", { name: "Accept" }).click();

  await expect(customer.locator(".status-pill")).toHaveText(/Preparing/);
  await kitchen.locator(".ticket", { hasText: `#${orderId}` }).getByRole("button", { name: "Mark ready" }).click();
  await expect(customer.locator(".status-pill")).toHaveText(/Ready for pick-up/);
  await expect(customer.locator(".audit")).toContainText("Preparing");
});

test("a declined card is explained, nothing is charged, and another card pays the same order", async ({ browser }) => {
  const customer = await signedIn(browser, CUSTOMER);
  await placeOrder(customer, "Rosemary Fries");

  await customer.getByLabel(/0002/).check();
  await customer.getByRole("button", { name: /Pay \$4\.75/ }).click();
  await expect(customer.getByRole("alert")).toContainText("Your card was declined.");
  await expect(customer.locator(".status-pill")).toHaveText(/Awaiting payment/);

  await customer.getByLabel(/4242/).check();
  await customer.getByRole("button", { name: /Pay \$4\.75/ }).click();
  await expect(customer.locator(".status-pill")).toHaveText(/Paid/);
});

test("a customer can cancel an unpaid order and the dish goes back on sale", async ({ browser }) => {
  const customer = await signedIn(browser, CUSTOMER);
  await customer.goto("/#/");
  const badge = customer.locator("article.dish", { hasText: "Truffle Smash Burger" }).locator(".stock");
  const before = Number((await badge.innerText()).match(/\d+/)?.[0]);

  await placeOrder(customer, "Truffle Smash Burger");
  await customer.getByRole("button", { name: "Cancel order" }).click();
  await customer.getByRole("button", { name: "Cancel order" }).last().click(); // confirm
  await expect(customer.locator(".status-pill")).toHaveText(/Cancelled/);

  await customer.goto("/#/");
  await expect(badge).toHaveText(`Only ${before} left today`);
});
