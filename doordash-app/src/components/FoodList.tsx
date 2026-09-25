import { useEffect, useMemo, useState } from "react";
import { Button, Modal, message } from "antd";
import { PlusOutlined } from "@ant-design/icons";
import { addToCart, clearCart, describeError, money, toCents } from "../api";
import { useCart } from "../context/CartContext";
import type { MenuItem } from "../types";
import MyCart from "./MyCart";

const StockBadge = ({ left }: { left: number | undefined }) => {
  if (left === undefined) return null;
  if (left === 0) return <span className="stock out">Sold out today</span>;
  return <span className={`stock ${left <= 5 ? "low" : ""}`}>Only {left} left today</span>;
};

const FoodList = () => {
  const { cart, restaurants, stock, refreshCart } = useCart();
  const [current, setCurrent] = useState<number | null>(null);
  const [adding, setAdding] = useState<number | null>(null);

  useEffect(() => {
    if (!current && restaurants[0]) setCurrent(restaurants[0].id);
  }, [restaurants, current]);

  const restaurant = useMemo(() => restaurants.find((r) => r.id === current), [restaurants, current]);
  const cartRestaurant = cart?.order_items?.[0]?.restaurant_id ?? null;

  const add = async (item: MenuItem & { restaurant_id: number }) => {
    // One restaurant per order, like the backend enforces: offer to start a new cart.
    if (cartRestaurant && cartRestaurant !== item.restaurant_id) {
      const from = restaurants.find((r) => r.id === cartRestaurant);
      const ok = await new Promise<boolean>((resolve) =>
        Modal.confirm({
          title: "Start a new cart?",
          content: `Your cart has items from ${from ? from.name : "another restaurant"}. An order can only come from one kitchen.`,
          okText: "Start new cart",
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        })
      );
      if (!ok) return;
      await clearCart();
    }
    setAdding(item.id);
    try {
      await addToCart(item.id);
      await refreshCart();
    } catch (e) {
      message.error(describeError(e, "Could not add it to your cart"));
    } finally {
      setAdding(null);
    }
  };

  return (
    <main className="page">
      <div className="eyebrow">Tonight's kitchens</div>
      <h1 style={{ fontSize: 38 }}>What are you craving?</h1>
      <p className="lead">Dishes marked "left today" are cooked in limited batches. Your portion is held the moment you place the order.</p>

      <div className="rest-tabs">
        {restaurants.map((r) => (
          <button key={r.id} className={`rest-tab ${r.id === current ? "active" : ""}`} onClick={() => setCurrent(r.id)}>
            <img src={r.image_url} alt="" />
            <div><strong>{r.name}</strong><span>{r.address}</span></div>
          </button>
        ))}
      </div>

      <div className="menu-layout">
        <section className="dish-grid">
          {restaurant &&
            (restaurant.menu_items || []).map((item) => {
              const left = stock[item.id];
              const item2 = { ...item, restaurant_id: restaurant.id };
              return (
                <article className="dish" key={item.id}>
                  <div className="dish-img">
                    <img src={item.image_url} alt="" />
                    <StockBadge left={left} />
                  </div>
                  <div className="dish-body">
                    <h3>{item.name}</h3>
                    <p>{item.description}</p>
                    <div className="dish-foot">
                      <span className="price">{money(toCents(item.price))}</span>
                      <Button type="primary" icon={<PlusOutlined />} disabled={left === 0}
                              loading={adding === item.id} onClick={() => add(item2)}>
                        Add
                      </Button>
                    </div>
                  </div>
                </article>
              );
            })}
        </section>
        <MyCart />
      </div>
    </main>
  );
};

export default FoodList;
