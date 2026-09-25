import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Button, Modal, message } from "antd";
import { checkout, clearCart, money, toCents } from "../api";
import { useCart } from "../context/CartContext";

const MyCart = () => {
  const { cart, restaurants, refreshCart, refreshStock } = useCart();
  const [placing, setPlacing] = useState(false);
  const navigate = useNavigate();

  const lines = (cart && cart.order_items) || [];
  const totalCents = lines.reduce((sum, l) => sum + toCents(l.price) * l.quantity, 0);
  const restaurant = lines.length ? restaurants.find((r) => r.id === lines[0].restaurant_id) : null;

  const place = async () => {
    setPlacing(true);
    try {
      // The total we show is sent along: if a price changed meanwhile the server refuses (409)
      // instead of charging an amount the customer never saw.
      const order = await checkout(totalCents);
      await Promise.all([refreshCart(), refreshStock()]);
      navigate(`/orders/${order.id}`);
    } catch (e) {
      if (e.code === "OUT_OF_STOCK") {
        Modal.warning({ title: "Sold out", content: e.message + ". Nothing was charged; your cart is unchanged." });
        refreshStock();
      } else if (e.code === "PRICE_CHANGED" && e.body && e.body.total_cents) {
        // Show the new total and let the customer decide; never charge an unseen amount.
        const newTotal = e.body.total_cents;
        Modal.confirm({
          title: "Prices have changed",
          content: `The total is now ${money(newTotal)} (was ${money(totalCents)}). Place the order at the new total?`,
          okText: `Place order · ${money(newTotal)}`,
          onOk: () =>
            checkout(newTotal)
              .then((order) => Promise.all([refreshCart(), refreshStock()]).then(() => navigate(`/orders/${order.id}`)))
              .catch((err) => message.error(err.message || "Could not place the order")),
        });
      } else {
        message.error(e.message || "Could not place the order");
      }
    } finally {
      setPlacing(false);
    }
  };

  return (
    <aside className="cart">
      <h2>Your order</h2>
      <div className="cart-from">{restaurant ? `from ${restaurant.name}` : "Add dishes to start an order"}</div>
      {lines.length === 0 ? (
        <div className="cart-empty">Your cart is empty.</div>
      ) : (
        <>
          {lines.map((l) => (
            <div className="cart-line" key={l.order_item_id}>
              <img src={l.menu_item_image_url} alt="" />
              <div>
                <div style={{ fontWeight: 600 }}>{l.menu_item_name}</div>
                <div className="qty">{l.quantity} × {money(toCents(l.price))}</div>
              </div>
              <div style={{ fontWeight: 600 }}>{money(toCents(l.price) * l.quantity)}</div>
            </div>
          ))}
          <div className="cart-total"><span>Total</span><span>{money(totalCents)}</span></div>
          <Button type="primary" size="large" block loading={placing} onClick={place}>
            Place order · {money(totalCents)}
          </Button>
          <Button type="link" block onClick={() => clearCart().then(refreshCart)} style={{ marginTop: 6 }}>
            Empty cart
          </Button>
          <div className="cart-note">
            Placing the order holds limited dishes for 15 minutes while you pay. Retrying after a
            network error never creates a second order.
          </div>
        </>
      )}
    </aside>
  );
};

export default MyCart;
