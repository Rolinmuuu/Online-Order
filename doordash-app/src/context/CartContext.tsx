import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { getCart, getInventory, getRestaurants } from "../api";
import type { Cart, Restaurant, Stock } from "../types";

interface CartState {
  cart: Cart | null;
  restaurants: Restaurant[];
  stock: Stock;
  refreshCart: () => Promise<void>;
  refreshStock: () => Promise<void>;
}

const CartContext = createContext<CartState | null>(null);

// Cart, menu and stock levels, shared by the menu page and the cart panel.
export const CartProvider = ({ children }: { children: ReactNode }) => {
  const [cart, setCart] = useState<Cart | null>(null);
  const [restaurants, setRestaurants] = useState<Restaurant[]>([]);
  const [stock, setStock] = useState<Stock>({});

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

export const useCart = (): CartState => {
  const state = useContext(CartContext);
  if (!state) throw new Error("useCart must be used inside <CartProvider>");
  return state;
};
