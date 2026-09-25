import { expect, test } from "@playwright/test";

test("a new customer signs up and lands on the menu", async ({ page }) => {
  await page.goto("/");
  await page.getByText("Create account").click();
  await page.getByLabel("E-mail").fill(`e2e-${Date.now()}@test.com`);
  await page.getByLabel("Password").fill("short");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByText("At least 8 characters")).toBeVisible();

  await page.getByLabel("Password").fill("long-enough-password");
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { name: "What are you craving?" })).toBeVisible();
});

test("a wrong password is refused", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("E-mail").fill("foo@mail.com");
  await page.getByLabel("Password").fill("not-the-password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Wrong e-mail or password")).toBeVisible();
});
