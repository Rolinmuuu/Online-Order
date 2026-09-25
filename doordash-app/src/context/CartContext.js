import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { getCart, getInventory, getRestaurants } from "../api";

const CartContext = createContext(null);

// Cart, menu and stock levels, shared by the menu page and the cart panel.
export const CartProvider = ({ children }) => {
  const [cart, setCart] = useState(null);
  const [restaurants, setRestaurants] = useState([]);
  const [stock, setStock] = useState({});

  const refreshCart = useCallback(() => getCart().then(setCart).catch(() => {}), []);
  const refreshStock = useCallback(() => getInventory().then(setStock).catch(() => {}), []);

  useEffect(() => {
    refreshCart();
    refreshStock();
    getRestaurants().then(setRestaurants).catch(() => {});
  }, [refreshCart, refreshStock]);

  return (
    <CartContext.Provider value={{ cart, restaurants, stock, refreshCart, refreshStock }}>
      {children}
    </CartContext.Provider>
  );
};

export const useCart = () => useContext(CartContext);
