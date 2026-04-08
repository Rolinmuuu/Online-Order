import { Button, Card, List, message, Select, Tooltip } from "antd";
import { useEffect, useState } from "react";
import { addItemToCart, getMenus, getRestaurants } from "../utils";
import { PlusOutlined } from "@ant-design/icons";
import { useCart } from "../context/CartContext";

const { Option } = Select;

const AddToCartButton = ({ itemId }) => {
  const [loading, setLoading] = useState(false);
  const { refreshCart } = useCart();
  const AddToCart = () => {
    setLoading(true);
    addItemToCart(itemId)
      .then(() => {
        message.success("Added to cart!");
        refreshCart();
      })
      .catch(() => {
        message.error("Failed to add to cart. Try again later.");
      })
      .finally(() => {
        setLoading(false);
      });
  };

  return (
    <Tooltip title="Add to shopping cart">
      <Button
        icon={<PlusOutlined />}
        onClick={AddToCart}
        loading={loading}
        type="primary"
      />
    </Tooltip>
  );
};

const FoodList = () => {
  const [foodData, setFoodData] = useState([]);
  const [curRest, setCurRest] = useState(null);
  const [restaurants, setRestaurants] = useState([]);
  const [loading, setLoading] = useState(false);
  const [loadingRest, setLoadingRest] = useState(false);

  useEffect(() => {
    setLoadingRest(true);
    getRestaurants()
      .then((data) => {
        setRestaurants(data);
      })
      .catch((err) => {
        message.error("Can't get restaurants. Please try again later.");
      })
      .finally(() => {
        setLoadingRest(false);
      });
  }, []);

  useEffect(() => {
    if (curRest) {
      setLoading(true);
      getMenus(curRest)
        .then((data) => {
          setFoodData(data);
        })
        .catch((err) => {
          message.error("Can't get menu. Please try again later.");
        })
        .finally(() => {
          setLoading(false);
        });
    }
  }, [curRest]);

  return (
    <>
      <Select
        value={curRest}
        onSelect={(value) => setCurRest(value)}
        placeholder="Select a restaurant"
        loading={loadingRest}
        style={{ width: 300 }}
        onChange={() => {}}
      >
        {restaurants.map((item) => {
          return <Option value={item.id}>{item.name}</Option>;
        })}
      </Select>
      {curRest && (
        <List
          style={{ marginTop: 20 }}
          loading={loading}
          dataSource={foodData}
          renderItem={(item) => {
            return (
              <List.Item>
                <Card
                  title={item.name}
                  extra={<AddToCartButton itemId={item.id} />}
                >
                  <img
                    src={item.image_url}
                    alt={item.name}
                    style={{ width: "100%", display: "block" }}
                  />
                  {`price: $${item.price}`}
                </Card>
              </List.Item>
            );
          }}
        ></List>
      )}
    </>
  );
};

export default FoodList;
