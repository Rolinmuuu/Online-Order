# Online Order — High-Concurrency E-Commerce Platform

A full-stack online food ordering platform inspired by DoorDash, built with **Spring Boot**, **React**, **PostgreSQL**, and **Redis**, deployed on **AWS**.

---

## Architecture Overview

```
┌─────────────────────────────────────┐
│         React + Ant Design SPA      │
│   (CartContext · polling · fetch)   │
└──────────────┬──────────────────────┘
               │ HTTP REST
┌──────────────▼──────────────────────┐
│        Spring Boot MVC Backend      │
│  CustomerController · MenuController│
│  CartController · Spring Security   │
└────────┬──────────────┬─────────────┘
         │              │
┌────────▼───────┐  ┌───▼────────────┐
│  PostgreSQL    │  │  Redis Cache   │
│  (AWS RDS)     │  │  (menu / cart) │
└────────────────┘  └────────────────┘
```

---

## Key Features

- **User Auth** — Registration (`POST /signup`) and form-based login/logout via Spring Security; passwords stored with `BCryptPasswordEncoder`
- **Menu Browsing** — Browse all restaurants and menus; results cached in Redis with 60-min TTL (`@Cacheable`)
- **Shopping Cart** — Add items, view cart, and checkout; cart state managed with React Context and auto-polled every 5 s for cross-session synchronization
- **Optimistic Locking** — `@Version` field on `CartEntity` prevents lost-update race conditions during concurrent checkout
- **Transactional Operations** — `addMenuItemToCart` and `clearCart` both annotated with `@Transactional`
- **Query Indexing** — Explicit indexes on `menu_items.restaurant_id`, `order_items.cart_id`, and `customers.email` for sub-millisecond lookups
- **Containerization** — Multi-service `docker-compose` (PostgreSQL + Redis) for local dev; production image built via `Dockerfile` and pushed to **AWS ECR**, deployed on **AWS App Runner**
- **CI Pipeline** — GitHub Actions workflow runs unit tests + Gradle build on every push/PR to `main`, with PostgreSQL and Redis service containers

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 21 · Spring Boot 3 · Spring MVC · Spring Security · Spring Data JDBC |
| Frontend | React 18 · Ant Design 4 · React Context (centralized state) |
| Database | PostgreSQL 15 (AWS RDS) |
| Caching | Redis 7 (`@Cacheable` / `@CacheEvict`) |
| Build | Gradle 8 |
| Container | Docker · AWS ECR · AWS App Runner |
| CI | GitHub Actions |
| Testing | JUnit 5 · Mockito · Postman Collection |

---

## Project Structure

```
Online-Order/
├── OnlineOrder/                    # Spring Boot backend
│   ├── src/main/java/.../
│   │   ├── controller/             # REST controllers (Cart, Menu, Customer)
│   │   ├── service/                # Business logic
│   │   ├── repository/             # Spring Data JDBC repositories
│   │   ├── entity/                 # Table-mapped records
│   │   ├── model/                  # DTOs and request bodies
│   │   └── AppConfig.java          # Security + Redis cache config
│   ├── src/main/resources/
│   │   ├── application.yml         # Datasource, Redis, cache settings
│   │   └── database-init.sql       # DDL + seed data + indexes
│   ├── src/test/                   # Unit tests (JUnit 5 + Mockito)
│   ├── Dockerfile
│   └── docker-compose.yml          # Local dev: PostgreSQL + Redis
├── doordash-app/                   # React frontend
│   └── src/
│       ├── context/CartContext.js  # Centralized cart state + 5 s polling
│       ├── components/
│       │   ├── FoodList.js         # Restaurant & menu browsing
│       │   ├── MyCart.js           # Cart drawer + checkout
│       │   ├── LoginForm.js
│       │   └── SignupForm.js
│       └── utils.js                # fetch-based API client
├── postman/
│   └── OnlineOrder.postman_collection.json   # API integration tests
└── .github/workflows/ci.yml        # GitHub Actions CI
```

---

## API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/signup` | Public | Register new user |
| `POST` | `/login` | Public | Form login (Spring Security) |
| `POST` | `/logout` | Public | Logout |
| `GET` | `/restaurants/menu` | Public | All restaurants with menus (Redis cached) |
| `GET` | `/restaurant/{id}/menu` | Public | Menu items for one restaurant |
| `GET` | `/cart` | Required | Get current user's cart |
| `POST` | `/cart` | Required | Add item to cart |
| `POST` | `/cart/checkout` | Required | Clear cart (checkout) |

---

## Getting Started

### Prerequisites

- Java 21
- Docker & Docker Compose
- Node.js 18+ / npm

### 1. Start local infrastructure

```bash
cd OnlineOrder
docker compose up -d
```

This starts PostgreSQL (port 5432) and Redis (port 6379). The database schema and seed data are applied automatically on first start.

### 2. Run the backend

```bash
cd OnlineOrder
./gradlew bootRun
```

The server starts on `http://localhost:8080`.

### 3. Run the frontend (development)

```bash
cd doordash-app
npm install
npm start
```

The React app starts on `http://localhost:3000` and proxies API calls to `:8080`.

---

## Running Tests

### Backend unit tests (JUnit + Mockito)

```bash
cd OnlineOrder
./gradlew test
```

13 unit tests covering `CartService`, `CustomerService`, `MenuItemService`, and `RestaurantService`.

### API integration tests (Postman)

Import `postman/OnlineOrder.postman_collection.json` into Postman. The collection includes 11 requests covering auth, menu browsing, cart operations, and checkout validation.

---

## Environment Variables

The backend reads the following environment variables (set automatically by `docker-compose` or AWS App Runner):

| Variable | Description |
|---|---|
| `DATABASE_URL` | PostgreSQL host |
| `DATABASE_PORT` | PostgreSQL port (default `5432`) |
| `DATABASE_USERNAME` | PostgreSQL username |
| `DATABASE_PASSWORD` | PostgreSQL password |
| `REDIS_HOST` | Redis host |
| `REDIS_PORT` | Redis port (default `6379`) |
| `INIT_DB` | `always` to re-run DDL on startup, `never` otherwise |

---

## License

MIT
