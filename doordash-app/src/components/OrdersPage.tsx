import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Empty, Skeleton } from "antd";
import { STATUS_LABELS, getOrders, money, subscribe } from "../api";
import { useCart } from "../context/CartContext";
import type { Order } from "../types";

const OrdersPage = () => {
  const [orders, setOrders] = useState<Order[] | null>(null);
  const { restaurants } = useCart();

  useEffect(() => {
    const load = () => getOrders().then(setOrders).catch(() => setOrders([]));
    load();
    return subscribe("/orders/stream", load, load);
  }, []);

  const cover = (rid: number) => restaurants.find((r) => r.id === rid)?.image_url;

  return (
    <main className="page">
      <div className="eyebrow">History</div>
      <h1 style={{ fontSize: 34 }}>My orders</h1>
      {orders === null ? <Skeleton active /> : orders.length === 0 ? (
        <Empty style={{ marginTop: 40 }} description="No orders yet" />
      ) : (
        orders.map((o) => (
          <Link key={o.id} to={`/orders/${o.id}`} className="order-row">
            <img src={cover(o.restaurant_id)} alt="" />
            <div>
              <div style={{ fontWeight: 700 }}>#{o.id} · {o.restaurant_name}</div>
              <div className="muted" style={{ fontSize: 13 }}>
                {o.lines.map((l) => `${l.quantity}× ${l.name}`).join(", ")}
              </div>
            </div>
            <span className={`status-pill ${o.status}`}><span className="dot" />{STATUS_LABELS[o.status]}</span>
            <b>{money(o.total_cents)}</b>
          </Link>
        ))
      )}
    </main>
  );
};

export default OrdersPage;
