// Shapes of the Spring Boot API's JSON (snake_case, nulls omitted). Kept next to the calls in
// api.ts; the backend records they mirror are named on each type.

/** GET /me (AccountController) */
export interface Me {
  email: string;
  first_name: string;
  kitchen_staff: boolean;
}

/** MenuItemDto. Prices are decimal dollars here; the ordering API uses integer cents. */
export interface MenuItem {
  id: number;
  name: string;
  description?: string;
  price: number;
  image_url?: string;
}

/** RestaurantDto, from GET /restaurants/menu */
export interface Restaurant {
  id: number;
  name: string;
  address?: string;
  phone?: string;
  image_url?: string;
  menu_items?: MenuItem[];
}

/** OrderItemDto: one line of the cart. */
export interface CartLine {
  order_item_id: number;
  menu_item_id: number;
  restaurant_id: number;
  price: number;
  quantity: number;
  menu_item_name: string;
  menu_item_description?: string;
  menu_item_image_url?: string;
}

/** CartDto */
export interface Cart {
  id: number;
  total_price: number;
  order_items?: CartLine[];
}

/** GET /inventory: units left of each limited dish, by menu item id. Absent = unlimited. */
export type Stock = Record<number, number>;

export type OrderStatus = "PLACED" | "PAID" | "ACCEPTED" | "READY" | "COMPLETED" | "CANCELLED";
export type PaymentStatus = "PENDING" | "CAPTURED" | "REFUNDED" | "FAILED";

/** OrderView */
export interface Order {
  id: number;
  status: OrderStatus;
  customer_id: number;
  restaurant_id: number;
  restaurant_name: string;
  total_cents: number;
  pay_by: string;
  created_at: string;
  cancel_reason?: string;
  payment_status?: PaymentStatus;
  payment_failure_reason?: string;
  lines: { menu_item_id: number; name: string; unit_price_cents: number; quantity: number }[];
  events: { from_status?: OrderStatus; to_status: OrderStatus; actor: string; reason?: string; at: string }[];
}

/** PaymentService.PaymentView, from POST /orders/{id}/pay */
export interface Payment {
  order_id: number;
  status: PaymentStatus;
  amount_cents: number;
  payment_ref: string;
}

/** GET /kitchen/restaurants/{id}/orders */
export interface KitchenBoard {
  orders: Order[];
  payable_cents: number;
}

/** OrderUpdates.Update, pushed over Server-Sent Events (serialised by Spring, so snake_case). */
export interface OrderUpdate {
  order_id: number;
  customer_id: number;
  restaurant_id: number;
  status: OrderStatus;
}

/** Error body of every 4xx the API returns (ApiErrors). */
export interface ErrorBody {
  error?: string;
  message?: string;
  total_cents?: number;
  menu_item_id?: number;
  available?: number;
  fields?: Record<string, string>;
}
