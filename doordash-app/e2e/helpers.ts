import { expect, type Browser, type Page } from "@playwright/test";

export const CUSTOMER = "foo@mail.com";
export const KITCHEN = "kitchen@mail.com";
export const PASSWORD = "123456";

/** A signed-in page in its own browser context (its own session cookie). */
export async function signedIn(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/");
  await page.getByLabel("E-mail").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  return page;
}

/** Empties the cart, adds one dish of the first restaurant, places the order; returns its id. */
export async function placeOrder(page: Page, dish: string): Promise<string> {
  await page.goto("/#/");
  const emptyCart = page.getByRole("button", { name: "Empty cart" });
  if (await emptyCart.isVisible()) await emptyCart.click();
  const card = page.locator("article.dish", { hasText: dish });
  await card.getByRole("button", { name: /Add/ }).click();
  await expect(page.locator(".cart-line", { hasText: dish })).toBeVisible();
  await page.getByRole("button", { name: /Place order/ }).click();
  await expect(page).toHaveURL(/#\/orders\/\d+$/);
  return page.url().split("/").pop() as string;
}
