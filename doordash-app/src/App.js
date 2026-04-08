import "antd/dist/antd.css";
import { Layout, Typography } from "antd";
import { useState } from "react";
import LoginForm from "./components/LoginForm";
import MyCart from "./components/MyCart";
import SignupForm from "./components/SignupForm";
import FoodList from "./components/FoodList";
import { CartProvider } from "./context/CartContext";

const { Header, Content } = Layout;
const { Title } = Typography;

const App = () => {
  const [authed, setAuthed] = useState(false);

  return (
    <CartProvider enabled={authed}>
      <Layout style={{ height: "100vh" }}>
        <Header style={{ color: "white" }}>
          <div
            className="header"
            style={{ display: "flex", justifyContent: "space-between" }}
          >
            <Title
              level={2}
              style={{ color: "white", lineHeight: "inherit", marginBottom: 0 }}
            >
              Lai Food
            </Title>
            <div>{authed ? <MyCart /> : <SignupForm />}</div>
          </div>
        </Header>
        <Content
          style={{
            padding: "50px",
            maxHeight: "calc(100% - 64px)",
            overflowY: "auto",
          }}
        >
          {authed ? (
            <FoodList />
          ) : (
            <LoginForm onSuccess={() => setAuthed(true)} />
          )}
        </Content>
      </Layout>
    </CartProvider>
  );
};

export default App;
