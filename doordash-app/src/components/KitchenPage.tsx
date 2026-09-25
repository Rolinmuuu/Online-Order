import { useCallback, useEffect, useRef, useState } from "react";
import { Button, Popconfirm, Segmented, message } from "antd";
import { describeError, kitchenAction, kitchenBoard, kitchenRestaurants, money, subscribe, type KitchenActionName } from "../api";
import type { KitchenBoard, Order, OrderStatus, Restaurant } from "../types";

interface Column {
  status: OrderStatus | "DONE";
  title: string;
  actions: [KitchenActionName, string, "primary" | "danger"][];
}

const COLUMNS: Column[] = [
  { status: "PAID", title: "New", actions: [["accept", "Accept", "primary"], ["reject", "Reject", "danger"]] },
  { status: "ACCEPTED", title: "Preparing", actions: [["ready", "Mark ready", "primary"], ["reject", "Reject", "danger"]] },
  { status: "READY", title: "Ready for pick-up", actions: [["complete", "Picked up", "primary"]] },
  { status: "DONE", title: "Done (last 2 h)", actions: [] },
];

const since = (iso: string) => {
  const m = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 60000));
  return m === 0 ? "just now" : `${m} min ago`;
};

const KitchenPage = () => {
  const [restaurants, setRestaurants] = useState<Restaurant[]>([]);
  const [rid, setRid] = useState<number | null>(null);
  const [board, setBoard] = useState<KitchenBoard>({ orders: [], payable_cents: 0 });
  const [fresh, setFresh] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const seen = useRef<Set<number>>(new Set());
  const latest = useRef(0);

  useEffect(() => {
    kitchenRestaurants()
      .then((rs) => {
        setRestaurants(rs);
        if (rs[0]) setRid(rs[0].id);
      })
      .catch(() => message.error("Could not load your restaurants"));
  }, []);

  const load = useCallback(() => {
    if (!rid) return;
    const n = ++latest.current; // a slower response for another restaurant must not win
    kitchenBoard(rid)
      .then((b) => {
        if (n !== latest.current) return;
        const incoming = b.orders.filter((o) => o.status === "PAID" && !seen.current.has(o.id)).map((o) => o.id);
        b.orders.forEach((o) => seen.current.add(o.id));
        if (incoming.length) setFresh(new Set(incoming));
        setBoard(b);
      })
      .catch(() => {
        if (n === latest.current) message.error("Could not load the board");
      });
  }, [rid]);

  useEffect(() => {
    if (!rid) return;
    seen.current = new Set();
    setBoard({ orders: [], payable_cents: 0 });
    load();
    return subscribe(`/kitchen/restaurants/${rid}/stream`, load, load, setLive);
  }, [rid, load]);

  const act = async (id: number, action: KitchenActionName) => {
    setBusy(`${id}:${action}`);
    try {
      await kitchenAction(id, action);
      load();
    } catch (e) {
      // e.g. the customer cancelled a moment earlier: 409, the board refreshes
      message.warning(describeError(e));
      load();
    } finally {
      setBusy(null);
    }
  };

  const inColumn = (col: Column): Order[] =>
    board.orders.filter((o) => (col.status === "DONE" ? ["COMPLETED", "CANCELLED"].includes(o.status) : o.status === col.status));

  return (
    <main className="page">
      <div className="kitchen-head">
        <div>
          <div className="eyebrow">Kitchen board</div>
          <h1 style={{ fontSize: 34 }}>{restaurants.find((r) => r.id === rid)?.name ?? "…"}</h1>
          <p className="lead" style={{ marginBottom: 12 }}>Paid orders appear here the instant the payment clears. {live ? <span className="live">Live</span> : <span className="muted">Reconnecting…</span>}</p>
          {restaurants.length > 1 && (
            <Segmented value={rid ?? undefined} onChange={(v) => setRid(Number(v))} options={restaurants.map((r) => ({ label: r.name, value: r.id }))} />
          )}
        </div>
        <div className="payable">
          <small>Owed to this restaurant (from the ledger)</small>
          <strong>{money(board.payable_cents)}</strong>
        </div>
      </div>

      <section className="board">
        {COLUMNS.map((col) => {
          const orders = inColumn(col);
          return (
            <div className="column" key={col.status}>
              <h3>{col.title} <span>{orders.length}</span></h3>
              {orders.map((o) => (
                <div className={`ticket ${fresh.has(o.id) ? "fresh" : ""}`} key={o.id}>
                  <div className="ticket-head">
                    <span>#{o.id}</span>
                    <span className="muted" style={{ fontWeight: 500, fontSize: 13 }}>
                      {o.status === "CANCELLED" ? "cancelled" : since(o.created_at)}
                    </span>
                  </div>
                  <ul>
                    {o.lines.map((l) => <li key={l.menu_item_id}><b>{l.quantity}×</b>{l.name}</li>)}
                  </ul>
                  <div className="ticket-actions">
                    {col.actions.map(([action, label, kind]) =>
                      action === "reject" ? (
                        <Popconfirm key={action} title="Reject and refund this order?" onConfirm={() => act(o.id, action)}>
                          <Button size="small" danger loading={busy === `${o.id}:${action}`}>{label}</Button>
                        </Popconfirm>
                      ) : (
                        <Button key={action} size="small" type={kind === "primary" ? "primary" : "default"} loading={busy === `${o.id}:${action}`} onClick={() => act(o.id, action)}>
                          {label}
                        </Button>
                      )
                    )}
                    {col.status === "DONE" && <span className="muted" style={{ fontSize: 13 }}>{money(o.total_cents)}</span>}
                  </div>
                </div>
              ))}
            </div>
          );
        })}
      </section>
    </main>
  );
};

export default KitchenPage;
