# TailorBookApp — Tailor Order Management System

A Spring Boot REST backend for tailoring shops. It manages shops, workers, customers, measurable
clothing items, and the full lifecycle of tailoring **orders** — from measurement capture through
cutting, sewing, pressing, delivery, payments, QR codes, and shop-owner dashboards.

This README is written to give an **AI-QA / test-automation platform** everything it needs to
generate API automation scripts and execute them: the architecture, the domain model, the
authentication flow, a full endpoint catalog with **required roles and preconditions**, and the
**business rules / state machines** that assertions must respect.

---

## 1. Technology Stack

| Concern            | Technology                                                        |
|--------------------|-------------------------------------------------------------------|
| Language / Build   | Java 17, Maven (`spring-boot-starter-parent` **2.6.3**)           |
| Framework          | Spring Boot (Web MVC, Data JPA, Security, Validation, Thymeleaf, Mail) |
| Persistence        | PostgreSQL (Hibernate, `ddl-auto=update`)                         |
| Auth               | JWT (JJWT), BCrypt password/PIN hashing, method-level security    |
| Object storage     | MinIO (S3-compatible) for photos, with temp → permanent promotion |
| API docs           | springdoc-openapi (Swagger UI)                                    |
| Extras             | Stripe (declared), Firebase Admin (declared), ZXing (QR codes), Spring Mail (OTP email) |

**Base URL (local):** `http://localhost:8083`
**Swagger UI:** `http://localhost:8083/swagger-ui.html`
**OpenAPI JSON:** `http://localhost:8083/api-docs`

> Package root: `tailor.book`. Layers: `controller` → `service` → `repository` → `entity`, with
> `dto.request` / `dto.Response` at the boundary, `config` for security/JWT/MinIO, and
> `exception` for a global handler.

---

## 2. High-Level Architecture

```
                    ┌─────────────────────────────────────────────┐
   Client / QA ───▶ │  Spring Security Filter Chain               │
   (JWT in header)  │   • JwtRequestFilter (parses Bearer token)  │
                    │   • URL rules (SecurityConfig)              │
                    │   • @PreAuthorize + @shopSecurity (per-row) │
                    └───────────────┬─────────────────────────────┘
                                    ▼
        ┌───────────────┐   ┌───────────────┐   ┌────────────────┐
        │  Controllers  │──▶│   Services    │──▶│  Repositories  │──▶ PostgreSQL
        │ (REST, /api)  │   │ (biz logic,   │   │ (Spring Data)  │
        └───────────────┘   │  @Transactional)│  └────────────────┘
                            └──────┬────────┘
                                   ▼ (after-commit)
                            ┌───────────────┐
                            │ MinIO storage │  temp bucket ──▶ permanent bucket
                            └───────────────┘
```

Key cross-cutting mechanisms:

- **Uniform response envelope** — most JSON endpoints return `ApiResponse<T>`:
  ```json
  { "status": "success | error", "message": "…", "data": { … }, "meta": { … } }
  ```
  Paginated endpoints put the Spring `Page` in `data` and totals in `meta`
  (`MetaData { totalElements, currentPage, totalPages }`).
- **JWT is returned in the `Authorization` response header** on sign-in/login (as `Bearer <token>`),
  **not** in the body. QA scripts must read the token from the response header.
- **Stateless sessions** (`SessionCreationPolicy.STATELESS`), CSRF disabled, CORS enabled.
- **Photo lifecycle**: images are uploaded to a **temp** MinIO bucket, referenced by URL in the
  order/customer request, and only **moved to the permanent bucket after the DB transaction commits**
  (`AfterCommitExecutor` + `PostCommitPhotoService`). A scheduled `MinioCleanupService` purges stale
  temp objects.

---

## 3. Domain Model (Entities)

| Entity | Table | Key fields | Relationships / Notes |
|--------|-------|-----------|-----------------------|
| `User` | `app_user` | `username`, `password`(BCrypt), `email`(unique), `phoneNumber`, `otp`, `pin` fields, `userType` (`owner`/…) | `@ManyToMany` `roles`; `@OneToOne` `shop` (via `shop_user`). `getShopId()`, `getAreaId()` derive from shop. |
| `Role` | — | `name` (`ERole`) | Seeded on startup. |
| `Area` | — | name, etc. | Top-level geography; a shop belongs to an `areaId`. |
| `Shop` | `Shop` | `shopName`, `shopPhone`, `ownerName`, `email`(unique), `address`, `areaId`(not null), lat/long, `status` | Belongs to an area. |
| `Worker` | — | worker profile, `WorkerRole` | Belongs to a shop; onboarding via OTP + PIN. |
| `Customer` | `customers` | `name`, `phone`, `gender`, `photo`(TEXT) | **Unique constraint `(name, phone)`** — created on demand during order creation. |
| `Item` | `Item` | `nameEn`, `nameBn`, `status`(`ItemStatus`) | `@OneToMany` `parameters` (`MeasuringParameter`, EAGER). A garment type (e.g. Shirt). |
| `MeasuringParameter` | — | `nameEn`, `nameBn`, `unit`, `type`(`ParameterType`), `suggestiveValues`, `nsId`(unique, 4-char) | Belongs to an `Item`. Defines what is measured. |
| `Order` | `orders` | `orderId`(unique business code), `orderDate`, `trialDate`, `deliveryDate`, `totalAmount`, `paidAmount`, `dueAmount`, `status`(`OrderStatus`) | `@ManyToOne` `shop`, `customer`; `@OneToMany` `orderItems` (cascade all, orphanRemoval). |
| `OrderItem` | — | — | Links an `Order` to an `Item`; holds `measurementGroups`. |
| `OrderItemMeasurementGroup` | — | `orderItemStatus`(`OrderItemStatus`), `deliveryDate`, `makingCharge`, `specialInstruction`, 4 photo URLs | The **unit of production tracking & pricing**. Each group has its own status and charge. |
| `OrderMeasurement` | — | `nameEn`,`nameBn`,`unit`,`type`, `value`/`booleanValue`/`textValue`, `nsId` | Actual captured measurement values inside a group. |
| `Payment` | — | `amount`, `paymentDate`, `order` | One row **per payment event** (advance + each subsequent payment) — powers "Today's Earning". |
| `OrderSequence` | — | `shopId`, `orderDate`, `sequence` | Per-shop, per-day counter for order-ID generation (optimistic locking). |
| `Photo` | — | binary/user/worker linkage | Profile photos for users/workers (DB-stored, separate from MinIO order photos). |
| `PendingShopChange` | — | — | Holds pending shop reassignment for workers. |

### Enums

- **`ERole`**: `ROLE_ADMIN`, `ROLE_CUSTOMER`, `ROLE_SHOP_OWNER`, `ROLE_SHOP_WORKER`
- **`OrderStatus`**: `MEASUREMENT_DONE`, `CUTTING_DONE`, `SEWING_DONE`, `PRESS_DONE`, `READY_FOR_DELIVERY`, `DELIVERED`, `IN_PROGRESS`, `CANCELLED`
- **`OrderItemStatus`**: `MEASUREMENT_DONE`, `CUTTING_DONE`, `SEWING_DONE`, `PRESS_DONE`, `READY_FOR_DELIVERY`, `DELIVERED`, `CANCELLED`
- **`ParameterType`**: `NUMERIC`, `BOOLEAN`, `TEXT`
- **`Gender`**, **`ItemStatus`**, **`WorkerRole`** also exist.

---

## 4. Authentication & Authorization

### 4.1 Roles

Spring uses the `ROLE_` prefix internally; `@PreAuthorize("hasRole('SHOP_OWNER')")` matches authority
`ROLE_SHOP_OWNER`. The four roles are seeded automatically (see §6).

### 4.2 Login flows (two ways to obtain a JWT)

1. **Password sign-in** — `POST /api/auth/signin` with `{ username, password }`.
2. **PIN login** — `POST /api/auth/login` with `{ username, pin }` (PIN acts as the password).

Both return the JWT in the **`Authorization` response header** and a `JwtResponse` in the body
(`id, username, email, roles[], shopId, areaId`).

**Shop-owner / worker onboarding via OTP:**
```
POST /api/auth/request-otp?email=<email>     → emails a one-time password
POST /api/auth/verify-otp   { phoneNumber, otp }
POST /api/auth/set-pin      { phoneNumber, otp, pin, confirmPin }   → PIN set (then login with PIN)
```

### 4.3 How to authenticate in a test request

```
Authorization: Bearer <token>
```
- Header **must** start with `Bearer ` (a malformed prefix is rejected with `TOKEN_INVALID`).
- JWT subject = `username`; token is signed HS512, default expiry `86400000 ms` (24h).
- Auth failures surface as JSON via `JwtAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403).

### 4.4 Two layers of authorization

1. **URL-level** (`SecurityConfig.securityFilterChain`):

   | Pattern | Rule |
   |---------|------|
   | `/api/auth/**` (otp/signin/verify/set-pin/login), `/swagger-ui/**`, `/v3/api-docs/**`, `/error` | public |
   | `GET /api/shops/**` | public |
   | `POST/PUT/DELETE /api/areas/**`, `/api/shops` | `ROLE_ADMIN` |
   | `POST/PUT/DELETE /api/orders/**` | `ROLE_SHOP_OWNER` |
   | `GET /api/orders/**` | authenticated |
   | `GET /api/dashboard/**` | `ROLE_SHOP_OWNER` |
   | `/api/photos/**`, `/api/customers/**` | `ROLE_SHOP_OWNER` |
   | anything else | authenticated |

2. **Row-level ownership** (`@shopSecurity`, class `ShopSecurity`) — enforced with `@PreAuthorize`
   on order endpoints. `ROLE_ADMIN` bypasses ownership; otherwise the caller must be an `owner`
   whose bound shop matches the target:
   - `owns(auth, shopId)` — caller owns that shop.
   - `ownsOrder(auth, orderId)` — caller's shop owns the order (numeric id).
   - `ownsOrderCode(auth, orderCode)` — same but by business order code (string).
   - `ownsOrderItem(auth, orderItemId)` — caller's shop owns the parent order of the item.

> **QA implication:** creating/reading/updating an order requires a **SHOP_OWNER token whose shop
> owns the resource**. A valid token for a *different* shop must yield **403**, not 200 — a critical
> negative-test axis.

---

## 5. API Endpoint Catalog (with preconditions)

`{base}` = `http://localhost:8083`. All bodies/returns use `ApiResponse<T>` unless noted.

### 5.1 Auth — `/api/auth` (public)

| Method & Path | Body | Precondition | Notes |
|---|---|---|---|
| `POST /signin` | `{ username, password }` | user exists w/ password | JWT in `Authorization` header; 401 on bad creds |
| `POST /login` | `{ username, pin }` | user has PIN set | JWT in header |
| `POST /request-otp?email=` | — | — | sends OTP email (returns OTP in `data` — test-only) |
| `POST /verify-otp` | `{ phoneNumber, otp }` | valid unexpired OTP | |
| `POST /set-pin` | `{ phoneNumber, otp, pin, confirmPin }` | valid OTP, `pin==confirmPin` | |

### 5.2 Orders — `/api/orders` (SHOP_OWNER + ownership)

| Method & Path | Auth | Precondition |
|---|---|---|
| `POST /api/orders` | owns `request.shopId` | shop, item(s) exist; valid `OrderRequest`; cloth photo present in temp bucket |
| `PUT /api/orders/{orderId}` | owns order (numeric id) | order exists |
| `GET /api/orders/{orderId}` | owns order **code** (string) | order exists; `{orderId}` = business code |
| `GET /api/orders/shop/{shopId}?page&limit` | owns shop | — (sorted by id desc) |
| `GET /api/orders/by-phone?phone&page&limit` | `SHOP_OWNER` | resolves caller's shop from token |
| `GET /api/orders/last-measurement?phone&customerId&itemId` | `SHOP_OWNER` | a prior order with that item exists |
| `POST /api/orders/{orderId}/cancel` | owns order | **all items in `MEASUREMENT_DONE`** (else 400) |
| `PUT /api/orders/items/{orderItemId}/status` | owns order item | status transition must be legal (§7.2) |
| `GET /api/orders/{orderId}/qrcode` | owns order | returns `{ "qrCode": "data:image/png;base64,…" }` (raw, not `ApiResponse`) |
| `POST /api/orders/{orderId}/pay` | owns order | `paymentAmount > 0`; returns `PaymentResponse` (raw) |

### 5.3 Dashboard — `/api/dashboard` (SHOP_OWNER; shop resolved from token)

| Path | Returns |
|---|---|
| `GET /summary` | `DashboardSummaryResponse` (counts + earnings, see §7.3) |
| `GET /orders/today?page&limit` | orders whose **delivery date = today** |
| `GET /orders/tomorrow?page&limit` | delivery date = tomorrow |
| `GET /orders/overdue?page&limit` | delivery date `< today` AND status ≠ `READY_FOR_DELIVERY` |
| `GET /orders/monthly?page&limit` | orders in current calendar month |
| `GET /orders/new?orderDate=YYYY-MM-DD&page&limit` | orders placed on given date |

### 5.4 Customers — `/api/customers` (SHOP_OWNER)

| Path |
|---|
| `GET /{id}` · `GET /by-name-phone?name&phone` · `GET /by-phone?phone` · `GET /by-shop/{shopId}?page&limit` |

### 5.5 Shops — `/api` (read public; write ADMIN)

| Method & Path | Auth |
|---|---|
| `GET /api/areas/{areaId}/shops` , `GET /api/areas/{areaId}/shops/{id}` , `GET /api/shops?shopPhone&areaId` | public |
| `POST /api/areas/{areaId}/shops` , `PUT …/{id}` , `DELETE …/{id}` | `ROLE_ADMIN` |

### 5.6 Areas — `/api/areas` (read public; write ADMIN)
`GET /api/areas`, `GET /api/areas/{id}`, `GET /api/areas-name?name`, `POST/PUT/DELETE` (ADMIN).

### 5.7 Items — `/api/items` (authenticated)
`POST /`, `PUT /{id}`, `GET /` (paged), `GET /{id}`, `GET /by-name?name`. Item carries `MeasuringParameter`s.

### 5.8 Workers — `/api` (authenticated)
`GET/POST /api/shops/{shopId}/workers`, `GET/PUT/DELETE …/{id}`, `POST …/workers/send-otp`,
`POST …/workers/set-pin`, `GET /api/workers/search?name`.

### 5.9 Photos
- **`/api/photos`** (MinIO, SHOP_OWNER): `POST /upload` (multipart), `POST /upload-base64`
  `{ photo, imageName? }` → `{ url, objectName }`, `GET /url?objectName`, `DELETE /delete?objectName`.
- **`/api/photos/user/{userId}` & `/worker/{workerId}`** (`PhotoController`) return raw JPEG bytes.

### 5.10 Roles — `/api/roles`
`GET /{id}`, `POST /`, `PUT /{id}`, `DELETE /{id}`.

---

## 6. Seed / Bootstrap Data (`DataInitializer`)

On first startup (when the role table is empty) the app seeds:

- The four roles: `ROLE_ADMIN`, `ROLE_CUSTOMER`, `ROLE_SHOP_OWNER`, `ROLE_SHOP_WORKER`.
- An **admin user**: username `admin`, password `admin`, email `admin@admin.com`, role `ROLE_ADMIN`.

> QA can obtain an ADMIN token immediately via `POST /api/auth/signin { "username":"admin","password":"admin" }`.
> There is **no seeded shop or shop-owner** — those must be created (admin creates area+shop; a
> shop-owner user must be provisioned/bound to the shop) before order/dashboard flows can be tested.

---

## 7. Business Logic & Rules (assertion targets)

### 7.1 Order creation (`OrderService.createOrder`)

1. Resolve `Shop` by `shopId` (404/500 if missing).
2. **Find-or-create `Customer`** by `(name, phone)` — reuses existing, else creates (respecting the
   unique constraint).
3. Generate a **business order id** (see §7.4) and initialize the order with status
   `MEASUREMENT_DONE`.
4. For each item → each **measurement group**: create the group with status `MEASUREMENT_DONE`,
   resolve its delivery date, add its `makingCharge` to the running total, map measurements by type.
   - **Cloth photo is required per group** (missing → 400 `IllegalArgumentException`); the other three
     photos (pattern, measurement, design) are optional.
5. **Delivery date resolution** (`resolveFinalOrderDeliveryDate`): single-item order → group's date;
   multi-item order → **latest** group delivery date.
6. `totalAmount = Σ makingCharge`; `dueAmount = totalAmount − paidAmount`.
7. If `paidAmount > 0`, a **`Payment` row is recorded with today's date** (so advance shows in
   "Today's Earning").
8. Photos referenced by temp URLs are **promoted to the permanent bucket only after commit**.

**`OrderRequest` validation (Bean Validation):**
`shopId` not null · `customerName` not blank · `customerPhone` matches `^(\+88)?01[3-9]\d{8}$` ·
`customerGender` not null · `paidAmount` ≥ 0 not null · `deliveryDate` not blank ·
`items` not empty. Each item: `itemId` not null, ≥1 measurement group. Each group:
`makingCharge` ≥ 0 not null, ≥1 measurement.

> **Date strings** (`deliveryDate`, `trialDate`, group `deliveryDate`) are parsed with
> `LocalDate.parse` → must be **ISO `YYYY-MM-DD`**. A blank/invalid delivery date will fail.

### 7.2 Order-item status state machine (`validateNextOrderItemStatus`)

Strictly linear; only the next step is allowed (illegal jumps → `InvalidStatusTransitionException`):

```
(null/new) ─▶ MEASUREMENT_DONE ─▶ CUTTING_DONE ─▶ SEWING_DONE ─▶ PRESS_DONE ─▶ READY_FOR_DELIVERY ─▶ DELIVERED
                    │
                    └─▶ CANCE

... [README truncated at 16000 chars]