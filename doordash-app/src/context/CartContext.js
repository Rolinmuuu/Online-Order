import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { getCart } from "../utils";

const CartContext = createContext(null);

const POLL_INTERVAL_MS = 5000;

export const CartProvider = ({ children, enabled }) => {
  const [cartData, setCartData] = useState(null);
  const [cartLoading, setCartLoading] = useState(false);

  const refreshCart = useCallback(() => {
    if (!enabled) return;
    setCartLoading(true);
    getCart()
      .then((data) => setCartData(data))
      .catch(() => {})
      .finally(() => setCartLoading(false));
  }, [enabled]);

  useEffect(() => {
    if (!enabled) return;
    refreshCart();
    const interval = setInterval(refreshCart, POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [enabled, refreshCart]);

  return (
    <CartContext.Provider value={{ cartData, cartLoading, refreshCart }}>
      {children}
    </CartContext.Provider>
  );
};

export const useCart = () => useContext(CartContext);
