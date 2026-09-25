import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, test, vi } from "vitest";
import { mockFetch } from "../test/fetchMock";
import AuthPage from "./AuthPage";

test("a wrong password says so", async () => {
  mockFetch({ "POST /login": () => ({ status: 401 }) });
  render(<AuthPage onSignedIn={vi.fn()} />);
  await userEvent.type(screen.getByLabelText("E-mail"), "foo@mail.com");
  await userEvent.type(screen.getByLabelText("Password"), "nope");
  await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
  expect(await screen.findByText("Wrong e-mail or password")).toBeInTheDocument();
});

test("being rate limited is not reported as a wrong password", async () => {
  mockFetch({ "POST /login": () => ({ status: 429, body: { error: "RATE_LIMITED", message: "too many requests" } }) });
  render(<AuthPage onSignedIn={vi.fn()} />);
  await userEvent.type(screen.getByLabelText("E-mail"), "foo@mail.com");
  await userEvent.type(screen.getByLabelText("Password"), "123456");
  await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
  expect(await screen.findByText(/Too many attempts/)).toBeInTheDocument();
});

test("a new account needs the same 8-character password the server requires", async () => {
  const calls = mockFetch({});
  render(<AuthPage onSignedIn={vi.fn()} />);
  await userEvent.click(screen.getByText("Create account"));
  await userEvent.type(screen.getByLabelText("E-mail"), "new@mail.com");
  await userEvent.type(screen.getByLabelText("Password"), "short");
  await userEvent.click(screen.getByRole("button", { name: "Create account" }));
  expect(await screen.findByText("At least 8 characters")).toBeInTheDocument();
  await waitFor(() => expect(calls).toHaveLength(0));
});
