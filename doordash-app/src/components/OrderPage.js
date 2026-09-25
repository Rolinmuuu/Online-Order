import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Button, Popconfirm, Skeleton, message } from "antd";
import { STATUS_LABELS, cancelOrder, getOrder, money, payOrder, subscribe } from "../api";
import StatusSteps from "./StatusSteps";

const useCountdown = (until) => {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);
  if (!until) return null;
  const s = Math.max(0, Math.floor((new Date(until).getTime() - now) / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
};

const time = (iso) => new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

const OrderPage = () => {
  const { id } = useParams();
  const [order, setOrder] = useState(null);
  const [busy, setBusy] = useState(false);
  const [live, setLive] = useState(false);
  const latest = useRef(0);
  const left = useCountdown(order && order.status === "PLACED" ? order.pay_by : null);

  // Responses can arrive out of order; only the newest request may update the screen.
  const load = useCallback(() => {
    const n = ++latest.current;
    return getOrder(id)
      .then((o) => n === latest.current && setOrder(o))
      .catch(() => n === latest.current && message.error("Order not found"));
  }, [id]);

  useEffect(() => {
    load();
    // Live: the server pushes every committed status change of my orders.
    return subscribe("/orders/stream", (u) => String(u.order_id) === String(id) && load(), load, setLive);
  }, [id, load]);

  if (!order) return <main className="page"><Skeleton active /></main>;

  const pay = async () => {
    setBusy(true);
    try {
      await payOrder(order.id);
      message.info("Payment sent to the (simulated) card processor…");
      await load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const cancel = async () => {
    try {
      setOrder(await cancelOrder(order.id));
    } catch (e) {
      message.error(e.message);
      load();
    }
  };

  const canCancel = order.status === "PLACED" || order.status === "PAID";

  return (
    <main className="page">
      <Link to="/orders" className="muted">← My orders</Link>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-end", marginTop: 10, gap: 16, flexWrap: "wrap" }}>
        <div>
          <div className="eyebrow">Order #{order.id}</div>
          <h1 style={{ fontSize: 34 }}>{order.restaurant_name}</h1>
        </div>
        <div style={{ display: "flex", gap: 12, alignItems: "center" }}>
          {live ? <span className="live">Live</span> : <span className="muted" style={{ fontSize: 12 }}>Reconnecting…</span>}
          <span className={`status-pill ${order.status}`}><span className="dot" />{STATUS_LABELS[order.status]}</span>
        </div>
      </div>

      <div className="order-wrap">
        <div>
          <section className="panel">
            <StatusSteps status={order.status} />
            {order.status === "CANCELLED" && (
              <p style={{ margin: 0 }}>
                <b>Cancelled</b>{order.cancel_reason ? `: ${order.cancel_reason}` : ""}.
                {order.payment_status === "REFUNDED" && " Your payment has been refunded."}
                {" "}The dishes went back on the menu.
              </p>
            )}
            {order.status === "PLACED" && (
              <div className="pay-box">
                <div className="muted">Pay within <strong>{left}</strong> or the order is released.</div>
                <div style={{ display: "flex", gap: 10, marginTop: 12 }}>
                  <Button type="primary" size="large" onClick={pay} loading={busy || order.payment_status === "PENDING"}>
                    {order.payment_status === "PENDING" ? "Waiting for the processor…" : `Pay ${money(order.total_cents)}`}
                  </Button>
                </div>
                <div className="cart-note">Card payments are simulated: the processor answers with a signed webhook, delivered twice on purpose.</div>
              </div>
            )}
          </section>

          <section className="panel">
            <h3>Items</h3>
            {order.lines.map((l) => (
              <div key={l.menu_item_id} className="cart-line" style={{ gridTemplateColumns: "1fr auto" }}>
                <div><b>{l.quantity}×</b> {l.name}</div>
                <div>{money(l.unit_price_cents * l.quantity)}</div>
              </div>
            ))}
            <div className="cart-total"><span>Total</span><span>{money(order.total_cents)}</span></div>
            {canCancel && (
              <Popconfirm title="Cancel this order?" okText="Cancel order" cancelText="Keep it" onConfirm={cancel}>
                <Button danger>Cancel order{order.status === "PAID" ? " and refund" : ""}</Button>
              </Popconfirm>
            )}
          </section>
        </div>

        <aside className="panel">
          <h3>History</h3>
          <ul className="audit">
            {order.events.map((e, i) => (
              <li key={i}>
                <time>{time(e.at)}</time>
                <span>
                  <b>{STATUS_LABELS[e.to_status]}</b>
                  <span className="muted"> · {e.actor.replace("kitchen:", "kitchen · ")}{e.reason ? ` · ${e.reason}` : ""}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="cart-note">Every status change is recorded in the same transaction that makes it.</p>
        </aside>
      </div>
    </main>
  );
};

export default OrderPage;
