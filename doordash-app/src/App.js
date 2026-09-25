import { useCallback, useEffect, useState } from "react";
import { NavLink, Navigate, Route, Routes, useNavigate } from "react-router-dom";
import { Button, Spin } from "antd";
import { logout, me } from "./api";
import { CartProvider } from "./context/CartContext";
import AuthPage from "./components/AuthPage";
import FoodList from "./components/FoodList";
import OrdersPage from "./components/OrdersPage";
import OrderPage from "./components/OrderPage";
import KitchenPage from "./components/KitchenPage";

const App = () => {
  const [user, setUser] = useState(undefined); // undefined = checking, null = signed out
  const navigate = useNavigate();

  const refreshUser = useCallback(() => me().then(setUser).catch(() => setUser(null)), []);
  useEffect(() => {
    refreshUser();
  }, [refreshUser]);

  if (user === undefined) {
    return <div style={{ display: "grid", placeItems: "center", height: "100vh" }}><Spin /></div>;
  }
  if (!user) {
    return <AuthPage onSignedIn={refreshUser} />;
  }

  const signOut = () => logout().finally(() => { setUser(null); navigate("/"); });

  return (
    <CartProvider>
      <header className="topbar">
        <div className="topbar-inner">
          <NavLink to="/" className="brand"><span className="brand-mark">
            <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M3 11h18a9 9 0 0 1-18 0z" fill="#fff" />
              <path d="M8 8c0-2 2-2 2-4M12 8c0-2 2-2 2-4" stroke="#f2b233" strokeWidth="1.8" fill="none" strokeLinecap="round" />
            </svg>
          </span>Online Order</NavLink>
          <nav className="nav">
            <NavLink to="/" end>Order food</NavLink>
            <NavLink to="/orders">My orders</NavLink>
            {user.kitchen_staff && <NavLink to="/kitchen">Kitchen</NavLink>}
          </nav>
          <div className="who">
            <span>{user.email}</span>
            <Button size="small" onClick={signOut}>Sign out</Button>
          </div>
        </div>
      </header>
      <Routes>
        <Route path="/" element={<FoodList />} />
        <Route path="/orders" element={<OrdersPage />} />
        <Route path="/orders/:id" element={<OrderPage />} />
        <Route path="/kitchen" element={user.kitchen_staff ? <KitchenPage /> : <Navigate to="/" />} />
        <Route path="*" element={<Navigate to="/" />} />
      </Routes>
    </CartProvider>
  );
};

export default App;
