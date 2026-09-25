import { useState } from "react";
import { Button, Form, Input, message, Segmented } from "antd";
import { ApiError, describeError, login, signup } from "../api";

type Mode = "Sign in" | "Create account";
interface Values { email: string; password: string; firstName?: string; lastName?: string }

const DEMO = [
  { label: "Customer", email: "foo@mail.com" },
  { label: "Kitchen staff", email: "kitchen@mail.com" },
];

const AuthPage = ({ onSignedIn }: { onSignedIn: () => void }) => {
  const [mode, setMode] = useState<Mode>("Sign in");
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm<Values>();

  const submit = async (v: Values) => {
    setBusy(true);
    try {
      if (mode === "Create account") {
        await signup({ email: v.email, password: v.password, first_name: v.firstName, last_name: v.lastName });
      }
      await login(v.email, v.password);
      onSignedIn();
    } catch (e) {
      // A failed sign-in is a 401 with no body; anything else (429, validation, taken e-mail) says why.
      const wrongPassword = mode === "Sign in" && e instanceof ApiError && e.status === 401;
      message.error(wrongPassword ? "Wrong e-mail or password" : describeError(e, "Could not create the account"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth">
      <section className="auth-art">
        <div>
          <div className="eyebrow" style={{ color: "#f2b233" }}>Online Order</div>
          <h1>Order from the kitchens around you.</h1>
          <p>Limited dishes are held for you the moment you order, you pay once, and you can watch the kitchen work on it live.</p>
        </div>
        <div className="auth-plates">
          {["truffle-burger", "tofu-stew", "dumplings", "pancake", "dan-dan", "shake"].map((f) => (
            <img key={f} src={`/food/${f}.svg`} alt="" />
          ))}
        </div>
      </section>
      <section className="auth-form">
        <div className="auth-card">
          <h2>{mode === "Sign in" ? "Welcome back" : "Create your account"}</h2>
          <p className="muted" style={{ marginBottom: 20 }}>
            {mode === "Sign in" ? "Sign in to order or to run a kitchen board." : "It takes ten seconds."}
          </p>
          <Segmented block options={["Sign in", "Create account"]} value={mode} onChange={(v) => setMode(v as Mode)} style={{ marginBottom: 20 }} />
          <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}>
            {mode === "Create account" && (
              <div style={{ display: "flex", gap: 12 }}>
                <Form.Item name="firstName" label="First name" style={{ flex: 1 }}><Input /></Form.Item>
                <Form.Item name="lastName" label="Last name" style={{ flex: 1 }}><Input /></Form.Item>
              </div>
            )}
            <Form.Item name="email" label="E-mail" rules={[{ required: true, type: "email" }]}><Input size="large" /></Form.Item>
            <Form.Item
              name="password"
              label="Password"
              // Same rule as the server (RegisterBody): at least 8 characters for a new account.
              rules={mode === "Create account" ? [{ required: true, min: 8, message: "At least 8 characters" }] : [{ required: true }]}
            >
              <Input.Password size="large" />
            </Form.Item>
            <Button type="primary" htmlType="submit" size="large" block loading={busy}>{mode}</Button>
          </Form>
          <div className="demo-accounts">
            Demo accounts (password <code>123456</code>):
            <div>
              {DEMO.map((d) => (
                <Button key={d.email} size="small" onClick={() => { setMode("Sign in"); form.setFieldsValue({ email: d.email, password: "123456" }); }}>
                  {d.label}
                </Button>
              ))}
            </div>
          </div>
        </div>
      </section>
    </div>
  );
};

export default AuthPage;
