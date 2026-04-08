import { Button, Drawer, List, message, Typography } from "antd";
import { useState } from "react";
import { checkOut } from "../utils";
import { useCart } from "../context/CartContext";

const { Text } = Typography;

const MyCart = () => {
  const [cartVisible, setCartVisible] = useState(false);
  const [checking, setChecking] = useState(false);
  const { cartData, cartLoading, refreshCart } = useCart();

  const onCheckOut = () => {
    setChecking(true);
    checkOut()
      .then(() => {
        message.success("Checkout successful!");
        refreshCart();
        setCartVisible(false);
      })
      .catch(() => {
        message.error("Checkout failed. Something went wrong.");
      })
      .finally(() => {
        setChecking(false);
      });
  };

  const orderItems = cartData?.order_items ?? [];

  return (
    <>
      <Button type="primary" shape="round" onClick={() => setCartVisible(true)}>
        Cart {orderItems.length > 0 && `(${orderItems.length})`}
      </Button>
      <Drawer
        title="My Shopping Cart"
        onClose={() => setCartVisible(false)}
        width={520}
        open={cartVisible}
        footer={
          <div style={{ display: "flex", justifyContent: "space-between" }}>
            <Text strong>{`Total price: $${cartData?.total_price ?? 0}`}</Text>
            <div>
              <Button onClick={() => setCartVisible(false)} style={{ marginRight: 8 }}>
                Cancel
              </Button>
              <Button
                onClick={onCheckOut}
                type="primary"
                loading={checking}
                disabled={cartLoading || orderItems.length === 0}
              >
                Check Out
              </Button>
            </div>
          </div>
        }
      >
        <List
          loading={cartLoading}
          itemLayout="horizontal"
          dataSource={orderItems}
          renderItem={(item) => (
            <List.Item>
              <List.Item.Meta
                title={item.menu_item_name}
                description={`Price: $${item.price}  ×  ${item.quantity}`}
              />
            </List.Item>
          )}
        />
      </Drawer>
    </>
  );
};

export default MyCart;
