# TenantCore (Kotlin + Spring Boot)

A reusable multi-tenant backend: one platform hosts many businesses ("tenants"), and each business is
configured from the admin dashboard instead of by changing code. Features are switched on per tenant as
modules.

Built with Kotlin 2.2, Spring Boot 3.5 on Java 21, PostgreSQL 16 and Redis 7.

## Status: all 6 stages done

| Stage | Contents | State |
| --- | --- | --- |
| 1. Foundation | Tenants, platform admins with 2FA, workspace sign-in, roles and permissions, modules per tenant, settings, audit log, rate limits | **Done, tested** |
| 2. Files & email | Uploads and images (local disk, switchable to S3/R2), SMTP settings and templates, invitations, forgot password | **Done, tested** |
| 3. Commerce | Catalog, inventory, purchasing, orders with an editable lifecycle, payments, returns, analytics, public shop API | **Done, tested** |
| 4. Custom data tables | Tables and fields defined in the dashboard, generated CRUD API, CSV/Excel imports and CSV export | **Done, tested** |
| 5. Integrations | Background jobs, webhooks, API keys, customer accounts, push notifications, maps, delivery tracking, QR codes and PromptPay | **Done, tested** |
| 6. Dashboard & ops | Vue admin dashboard for all of the above, data export, offboarding, backups and restore | **Done, tested** |

## Quick start

```bash
cp .env.example .env      # replace every CHANGE_ME (commands to generate secrets are in the file)
docker compose up --build # PostgreSQL, Redis, Mailpit, nightly backups and the API on http://localhost:8000
```

Open **http://localhost:8000** for the dashboard:
- **Platform console** (`/platform/login`): sign in with `TC_SEED_ADMIN_EMAIL` / `TC_SEED_ADMIN_PASSWORD`, create
  workspaces, switch modules on and off, set up email and storage, watch jobs and backups.
- **Workspace** (`/login`): the business owner signs in with the workspace short name, email and password
  you gave them, and runs everything else from there.

Everything below can also be done through the API with `curl`.

Emails sent locally are caught by Mailpit: open http://localhost:8025. To send through it, set the
platform email settings once (host `mailpit`, port `1025`, security `none`):

```bash
curl -X PUT localhost:8000/api/v1/platform/email/settings -H "Authorization: Bearer <platform token>" \
  -H 'content-type: application/json' \
  -d '{"settings":{"host":"mailpit","port":1025,"security":"none","from_address":"no-reply@example.com"}}'
```

On first start the platform admin from `TC_SEED_ADMIN_EMAIL` / `TC_SEED_ADMIN_PASSWORD` is created.
Then:

```bash
# Sign in as the platform admin
curl -X POST localhost:8000/api/v1/platform/auth/login -H 'content-type: application/json' \
  -d '{"email":"admin@example.com","password":"<your seed password>"}'

# Create a business (tenant) and its owner account
curl -X POST localhost:8000/api/v1/platform/tenants -H "Authorization: Bearer <token>" -H 'content-type: application/json' \
  -d '{"slug":"demo-shop","name":"Demo Shop","owner_email":"owner@demo.example.com","owner_password":"<12+ characters>"}'

# Sign in as that owner
curl -X POST localhost:8000/api/v1/auth/login -H 'content-type: application/json' \
  -d '{"tenant":"demo-shop","email":"owner@demo.example.com","password":"<owner password>"}'
```

To expose it with Cloudflare Tunnel, point the tunnel at `http://localhost:8000` (or your
`API_HOST_PORT`); the dashboard and the API are served from the same address. Then set the dashboard's
public address in Platform console → General, so links in emails point there. Visitor IPs come from `CF-Connecting-IP`, so rate limits and audit entries see the real
client.

### Running without Docker

You need Java 21, PostgreSQL 16 and Redis. Create the roles once with `infra/postgres/01-roles.sh` (as the
`postgres` superuser), set the variables from `.env.example` plus `TC_DB_URL` and `TC_REDIS_URL`, then:

```bash
./gradlew bootRun
```

## Dashboard

The Vue 3 dashboard is in `web/`. `./gradlew bootJar` packs `web/dist` into the jar and the API serves it,
so production has one address and needs no CORS. The Docker image builds it for you.

```bash
cd web
npm ci
npm run dev        # http://localhost:5173, with /api proxied to http://localhost:8000
npm run build      # type-checks, then writes web/dist (used by ./gradlew bootJar)
```

Set `API_PROXY_TARGET` to proxy to another address. What each person sees follows the same rules the
API enforces: pages for switched-off modules are hidden, and a role only shows the pages and buttons it
allows (the API still checks every call). Sign-in tokens are kept in the browser tab's session storage
and cleared when the tab closes. The page is served with a strict Content-Security-Policy (no inline
scripts, no third-party code or fonts).

Workspace pages: home with sales figures, orders (payments, refunds, PromptPay QR, status changes),
returns, customers, deliveries, products and categories, inventory, purchasing, custom tables (designer,
rows with filters, CSV export), imports, files, push notifications, team, roles (permission grid with
own-rows and field-level access), developers (API keys, webhooks with delivery log), settings (business
and logo, checkout and order statuses, payment gateways, customer accounts, email server and templates,
maps, PromptPay, Firebase, modules, full data export) and the activity log.

Platform pages: workspaces (create, modules and their limits, business settings, exports, offboarding),
platform admins, background jobs, backups, activity log, email, file storage and general settings.

## Backups and restore

The `backup` service in `docker-compose.yml` runs `infra/backup/backup.sh` at start and then every
`BACKUP_INTERVAL_HOURS`: a `pg_dump` of the database plus a tarball of the file storage, in the `backups`
volume, deleting ones older than `BACKUP_KEEP_DAYS`. It dumps as the PostgreSQL superuser because row-level
security hides every tenant row from the schema owner. The dashboard's Backups page shows the files and
turns red when no database backup is newer than two intervals.

**Copy the backups volume off the machine** (rclone, restic, a cloud bucket): a backup on the same disk
does not survive that disk. Also keep `TC_ENCRYPTION_KEY` with them. If files are stored in S3/R2, the
storage tarball only covers local files; use the bucket's own versioning or replication.

To restore (replaces all current data and files):

```bash
docker compose run --rm backup ls /backups                                 # pick a timestamp
docker compose stop api
docker compose run --rm backup bash /opt/backup/restore.sh 20261015-020000       # asks you to type the database name
docker compose start api
```

The restore refuses to run while the API is still connected. It was tested here: a restore brought back
the deleted rows and files, and the API worked on the restored database with row-level security and grants
intact.

## Exports and offboarding

- **Workspace export:** a workspace admin (Settings → Export data) or a platform admin can download a zip
  of every record as JSON lines plus the uploaded files. Password hashes, token hashes and encrypted
  secrets are left out. Exports are kept for 7 days.
- **Offboarding** (Platform console → workspace → Data & offboarding):
  1. *Start offboarding*: signs everyone out, blocks sign-in and the public shop, and makes a final export.
     It can be cancelled.
  2. *Delete workspace*: a background job deletes every row in every tenant table and every stored file.
     It needs the slug typed to confirm and a finished export, unless you choose to delete without one.
     One audit entry records that it happened. Data in older backups remains until those backups age out.

## API

All paths are under `/api/v1`. Errors always look like
`{"error": {"code", "message", "request_id", "details"}}`; validation errors list the fields in `details`.

**Platform admin**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/platform/auth/login` | Email, password and, once enrolled, `otp` |
| POST | `/platform/auth/logout` | End this session |
| GET | `/platform/auth/me` | Current admin, MFA state |
| POST | `/platform/auth/mfa/setup`, `/mfa/confirm` | Enrol an authenticator app |
| GET, POST | `/platform/admins` | List and add platform admins |
| PATCH | `/platform/admins/{id}` | Rename, deactivate, reset a lost authenticator |
| GET, POST | `/platform/tenants` | Search and create tenants (with owner and starting modules) |
| GET, PATCH | `/platform/tenants/{id}` | Details; rename, suspend/reactivate, rate limit |
| GET | `/platform/modules` | Module catalog |
| GET, PUT | `/platform/tenants/{id}/modules` | Switch modules on and off |
| GET, PUT | `/platform/tenants/{id}/settings` | Edit a tenant's settings |
| GET | `/platform/audit-logs` | Platform-level audit trail |

**Workspace (tenant) users**

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/auth/login`, `/auth/refresh`, `/auth/logout` | Sign-in with rotating refresh tokens |
| POST | `/auth/change-password` | Change own password (ends other sessions) |
| GET | `/auth/me` | User, tenant, permissions, enabled modules and settings in one call |
| GET, PUT | `/settings` | Branding, currency, timezone, locale, custom values |
| GET | `/modules` | Which modules are on |
| GET, POST, PATCH | `/users`, `/users/{id}` | Manage users: disable, make admin, unlock |
| PUT | `/users/{id}/roles` | Assign roles |
| POST | `/users/{id}/reset-password` | Admin sets a new password |
| GET, POST, PATCH, DELETE | `/roles`, `/roles/{id}` | Manage roles and their permissions |
| GET | `/permissions/catalog` | Resources, allowed actions and module state, for the role editor |
| GET | `/audit-logs` | The workspace's audit trail |

**Files and images** (files module)

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/files` | Upload (multipart, part `file`; query `purpose`, `public`) |
| GET | `/files`, `/files/{id}` | List and search; details |
| GET | `/files/{id}/content`, `/files/{id}/thumbnail` | Download; 480 px PNG thumbnail of images |
| POST | `/files/{id}/link` | Time-limited signed link anyone can open |
| PATCH, DELETE | `/files/{id}` | Rename, make public/private; delete |
| GET | `/files/usage` | Space used, quota, per-file limit |
| GET | `/public/files/{tenant id}/{id}` | Public files (logos, product images) and signed links, no sign-in |
| GET, PUT | `/platform/storage`, POST `/platform/storage/test` | Local disk or S3-compatible storage (R2, S3, MinIO) |
| GET, PUT | `/platform/tenants/{id}/modules/files/config` | Per-tenant `max_file_mb` and `quota_mb` |

Set a logo with `PUT /settings {"logo_file_id": "..."}`; it becomes public and `logo_url` appears in the
settings and in `/auth/me`.

**Email** (email module)

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/users/invite`, `/users/{id}/resend-invite` | Invite by email; the user chooses their own password |
| POST | `/auth/accept-invite` | Set password from the invitation link and sign in |
| POST | `/auth/forgot-password`, `/auth/reset-password` | Self-service password reset |
| GET, PUT, DELETE | `/email/settings` | The workspace's own SMTP server (optional) |
| GET, PUT, DELETE | `/email/templates/{key}` | Customise or reset a template; `POST .../preview` renders it |
| POST | `/email/test`; GET `/email/log` | Send a test; see what was sent, retried or failed |
| GET, PUT | `/platform/email/settings`, POST `/platform/email/test` | The platform's SMTP server |
| GET, PUT, DELETE | `/platform/email/templates/{key}` | Platform-wide template defaults |
| GET, PUT | `/platform/settings/general` | Platform name and dashboard URL used in email links |

Templates: `user_invite`, `password_reset`, `password_changed`, `order_confirmation`, `test`. They use `{{variables}}` such
as `{{business_name}}`, `{{user_name}}` and `{{link}}`. Values are HTML-escaped, and a template has no
logic, so editing one cannot run code.

**Catalog** (catalog module; permission `products`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/products` | Search (`q` matches name, SKU, barcode; `status`, `category_id`); create with variants and images |
| GET, PATCH, DELETE | `/products/{id}` | Details; edit (`image_file_ids` replaces the images); delete, only without history |
| POST, PATCH, DELETE | `/products/{id}/variants[/{variantId}]` | Add, edit (`clear` empties optional fields), remove variants |
| GET, POST, PATCH, DELETE | `/product-categories[/{id}]` | Nested categories |

Every product has at least one variant; the variant carries the SKU, price, cost, barcode and options
(e.g. `{"size": "M"}`). `attributes` holds any extra values you want to show.

**Inventory** (inventory module; `inventory`: read, update for stock changes, manage for warehouses)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST, PATCH | `/inventory/warehouses[/{id}]` | Warehouses; the first is the default, each gets a `MAIN` bin |
| POST, PATCH | `/inventory/warehouses/{id}/bins[/{binId}]` | Bins (shelves, locations) |
| GET | `/inventory/stock` | On hand, reserved and available per variant and bin; `low_only=true` for the reorder list |
| POST | `/inventory/adjustments` | Change stock (`quantity`) or record a count (`set_to`) |
| POST | `/inventory/transfers` | Move stock between bins |
| GET | `/inventory/movements` | The ledger of every stock change |

**Purchasing** (purchasing module; `purchasing`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST, PATCH | `/suppliers[/{id}]` | Suppliers |
| GET, POST, PATCH, DELETE | `/purchase-orders[/{id}]` | Purchase orders (editable and deletable while draft) |
| POST | `/purchase-orders/{id}/submit`, `/receive`, `/cancel` | Mark ordered; receive (partly or fully) into the warehouse; cancel |

**Orders** (orders module; `orders`: manage for lifecycle and settings)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/orders` | Search (`status`, `payment_status`, `q` = number, email, name or phone; `from`, `to`); create. Send `Idempotency-Key` |
| GET, PATCH | `/orders/{id}` | Details with history and payments; edit customer details and notes |
| POST | `/orders/{id}/transition` | Move to another status (`{"to": "shipped"}`) |
| GET, PUT | `/orders/lifecycle` | The statuses and allowed moves |
| GET, PUT | `/orders/settings` | Tax rate, prices with or without tax, shipping fee, free-shipping threshold, online checkout, status on payment, confirmation email |

**Payments** (payments module; `payments`: create to take, update to refund, manage for gateways)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, PUT | `/payments/gateways[/{provider}]` | Stripe keys (secret keys are write-only) and the sandbox |
| GET, POST | `/orders/{id}/payments` | List; take a payment: `manual` (cash, transfer…), `stripe` or `sandbox` |
| POST | `/payments/{id}/refund` | Refund part or all of a payment |
| POST | `/payments/{id}/sandbox` | Complete a sandbox payment (`succeeded` or `failed`) to test the flow |
| POST | `/public/payments/stripe/{tenant id}/webhook` | Stripe webhook endpoint (signature-checked) |

**Returns** (returns module; `rma`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/returns` | List; open a return for items of an order |
| GET | `/returns/{id}` | Details with refunds |
| POST | `/returns/{id}/approve`, `/reject`, `/receive`, `/refund`, `/close` | Work the return through; receiving can restock, refunding can go through the original payment |

**Analytics** (`analytics:read`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/analytics/sales` | Orders, revenue, average order, items sold, refunds, a zero-filled series (`interval` day, week or month), top products, orders by status |

**Public shop** (no sign-in; for a website or app)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/public/shop/{slug}` | Name, currency, logo, colour, whether checkout is on, payment methods |
| GET | `/public/shop/{slug}/products`, `/products/{product slug}`, `/categories` | Active products only, with prices, images and in-stock flags |
| POST | `/public/shop/{slug}/checkout` | Place an order (needs online checkout switched on); `pay_with` starts a card payment |
| GET | `/public/shop/{slug}/orders/{id}?token=` | Order status page, using the token returned at checkout |

**Custom data tables** (data_tables module; `data_schema` to design tables, `data.<table key>` for rows)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/data/tables` | Tables you can see; create a table with its fields |
| GET, PATCH, DELETE | `/data/tables/{key}` | Design and your access; rename, set the display field; delete (`?confirm={key}`) |
| POST, PATCH, DELETE | `/data/tables/{key}/fields[/{field}]` | Add, change (label, required, unique, choices, limits) or remove (`?confirm={field}`) a field |
| GET, POST | `/data/tables/{key}/rows` | List (`q`, `sort=-points,name`, `limit`, `offset`); add a row (`{"data": {...}}`, `Idempotency-Key` supported) |
| POST | `/data/tables/{key}/rows/query` | Filters, sort, search and paging as JSON |
| GET, PATCH, DELETE | `/data/tables/{key}/rows/{id}` | Read, change (send only changed fields; optional `expected_version`), delete |
| GET | `/data/tables/{key}/export` | CSV of what you can see |

Field types: `text`, `long_text`, `integer`, `decimal`, `boolean`, `date`, `datetime`, `email`, `url`,
`phone`, `select`, `multi_select`, `reference` (a row of another table), `file`, `user`. Fields can be
required or unique and can have a default, choices, min/max, length limits and decimal places.

Filter operators: `eq`, `ne`, `lt`, `lte`, `gt`, `gte`, `contains`, `starts_with`, `in`, `is_empty`,
`is_not_empty`, and `has` for multi-select. Example:
`{"filters": [{"field": "points", "op": "gt", "value": 10}], "sort": [{"field": "name"}], "q": "lee"}`.

**Imports** (imports module; `imports` plus create, or update for upserts, on the table)

| Method | Path | Purpose |
| --- | --- | --- |
| POST | `/data/tables/{key}/imports` | Upload a CSV or .xlsx (`encoding` for non-UTF-8 CSV, `sheet` for a named sheet); returns columns, a preview and a suggested mapping |
| POST | `/data/imports/{id}/start` | `mapping` (column → field), `mode` insert or upsert (`match_field`), `date_format` iso/dmy/mdy, `dry_run` |
| GET | `/data/imports`, `/data/imports/{id}` | Progress, counts and errors by row number |
| POST | `/data/imports/{id}/cancel` | Stop it |

Per-tenant limits (platform admin, `PUT /platform/tenants/{id}/modules/{module}/config`):
`data_tables`: `max_tables` (50), `max_fields_per_table` (100), `max_rows_per_table` (100000);
`imports`: `max_file_mb` (10), `max_rows` (50000).

**API keys** (`api_keys`) — for other systems calling the API

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/api-keys` | List; create with a name, a role and an optional lifetime. The key (`tck_...`) is shown once |
| DELETE | `/api-keys/{id}` | Revoke |

Send it like a sign-in token: `Authorization: Bearer tck_...`. A key has exactly its role's permissions.

**Webhooks** (webhooks module; `webhooks`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/webhooks` | Endpoints; create with an https URL and the events wanted (the signing secret is shown once) |
| PATCH, DELETE | `/webhooks/{id}` | Change, re-enable after automatic disabling, delete |
| POST | `/webhooks/{id}/rotate-secret`, `/webhooks/{id}/test` | New secret; send a `ping` |
| GET | `/webhooks/{id}/deliveries`, `/webhooks/event-types` | Delivery log; the events you can subscribe to |
| POST | `/webhooks/deliveries/{id}/redeliver` | Send again |

Events: `order.created`, `order.status_changed`, `order.paid`, `payment.succeeded`, `payment.failed`,
`payment.refunded`, `return.created`, `return.updated`, `product.created/updated/deleted`,
`customer.created`, `delivery.created`, `delivery.status_changed`, and `data.<table>.created/updated/deleted`
for custom tables. Wildcards such as `order.*` and `*` work.

Each request carries `TenantCore-Event`, `TenantCore-Delivery` (an id, the same if it is retried) and
`TenantCore-Signature: t=<unix time>,v1=<hex HMAC-SHA256 of "<t>.<body>">`. Check the signature with the
endpoint's secret, refuse old timestamps, and ignore delivery ids you have already handled.

**Customer accounts** (customer_accounts module; for a shop website or app, no staff sign-in)

| Method | Path under `/public/shop/{slug}/account` | Purpose |
| --- | --- | --- |
| POST | `/register`, `/login`, `/refresh`, `/logout` | Sign-up and sign-in (rotating refresh tokens, lockout after 5 failures) |
| POST | `/verify-email`, `/resend-verification`, `/forgot-password`, `/reset-password` | Email confirmation and password reset |
| GET, PATCH | `/me` | Profile; `POST /me/change-password`, `POST /me/delete` closes the account |
| GET | `/orders`, `/orders/{id}` | The customer's orders, with delivery tracking links |
| POST | `/devices`, `/devices/remove` | Phone push tokens |

A signed-in customer's checkout (`Authorization: Bearer <customer token>`) is linked to their account and
needs no name or email. Staff: `GET /customers`, `GET/PATCH /customers/{id}` (deactivate),
`GET/PUT /customers/settings` (allow sign-ups, require a confirmed email, the shop website's address for
email links).

**Push notifications** (push_notifications module; `notifications`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, PUT | `/notifications/settings` | Firebase service account JSON (write-only); automatic order and delivery updates on/off |
| GET, POST | `/notifications` | History with sent/failed counts; send to all customers, some customers, staff |
| POST | `/devices`, `/devices/remove` | Staff app push tokens |

**Maps** (maps module; `maps`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, PUT | `/maps/settings` | Provider: OpenStreetMap Nominatim (default, free, light use) or Google (your key, write-only) |
| GET | `/maps/geocode?q=`, `/maps/reverse?lat=&lng=` | Address to coordinates and back, cached per tenant |
| POST | `/maps/distance` | Straight-line distance in km |

**Delivery tracking** (delivery_tracking module; `deliveries`)

| Method | Path | Purpose |
| --- | --- | --- |
| GET, POST | `/deliveries` | List (`mine=true` for a driver's own); create for an order with a driver and destination |
| GET, PATCH | `/deliveries/{id}` | Details with recent locations; status assigned → picked_up → in_transit → delivered (or failed, cancelled), proof file |
| POST | `/deliveries/{id}/pings` | Driver's phone sends up to 100 locations at a time |
| GET | `/public/shop/{slug}/deliveries/{id}?token=` | Customer tracking page; the driver's position is shown only while on the way |

Drivers need only `deliveries:read`: they may update the deliveries assigned to them. When a delivery is
delivered, the order moves to `delivered` if the lifecycle allows (configurable in the module options).

**QR codes and PromptPay** (qr_codes module)

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/qr?data=&format=png\|svg&size=` | A QR code for any text or link |
| GET, PUT | `/qr/settings` | The shop's PromptPay id (mobile number, national/tax id or e-wallet id) |
| GET | `/orders/{id}/promptpay?format=json\|png\|svg` | PromptPay QR for the amount due (THB orders) |
| GET | `/public/shop/{slug}/orders/{id}/promptpay?token=` | The same for the customer's order page |

Banks send no confirmation for PromptPay transfers, so staff record the payment when it arrives
(`POST /orders/{id}/payments` with `"provider": "manual", "method": "promptpay"`).

**Background jobs** (platform): `GET /platform/jobs?status=&kind=` shows queued, running, finished and
failed jobs; `POST /platform/jobs/{id}/retry` runs a failed one again.

Health: `GET /health` (process up) and `GET /ready` (database and Redis reachable).

### Exports, offboarding and backups

| Method | Path | Who | What |
| --- | --- | --- | --- |
| GET, POST | `/workspace/exports` | workspace admin | list or start a full export |
| GET | `/workspace/exports/{id}/download` | workspace admin | the zip |
| GET, POST | `/platform/tenants/{id}/exports` | platform | list or start an export of that workspace |
| GET | `/platform/exports/{id}/download` | platform | the zip |
| POST | `/platform/tenants/{id}/offboard` | platform | body `{"confirm": "<slug>"}`: lock, sign everyone out, export |
| POST | `/platform/tenants/{id}/cancel-offboarding` | platform | back to active |
| DELETE | `/platform/tenants/{id}?confirm=<slug>[&force=true]` | platform | delete all data and files (202; runs as a job) |
| GET | `/platform/backups` | platform | backup files and whether they are on time |

## How it works

**Tenant isolation is enforced by PostgreSQL, not only by the code.**
- **Two database roles.** The API connects as `tc_app`, which is not a superuser and cannot bypass
  row-level security. Migrations run as `tc_owner`.
- **Isolation inside the database.** Every tenant table has `FORCE ROW LEVEL SECURITY`.
  - Each request runs in a transaction that first sets `app.tenant_id` with `set_config(..., true)`.
    That value only lasts for the transaction, so a pooled connection never leaks one tenant's context
    into the next request.
  - With no tenant set, no rows are visible and every write is rejected.
  - The tests check this directly against the database, below the API.
- **The audit log is append-only.** A trigger refuses UPDATE, DELETE and TRUNCATE, even for the owner.

**Sign-in**
- **Passwords** are hashed with Argon2id (OWASP parameters).
  - After 5 failed attempts the account locks for 15 minutes.
  - Unknown accounts take the same time to reject as wrong passwords, so the timing doesn't reveal which
    emails exist.
- **Access tokens** are HS256 JWTs that live 10 minutes. They carry a `kid` header, so signing keys can be
  rotated without signing everyone out.
- **Every request re-checks** the session, the user and the tenant status. Disabling a user or suspending
  a tenant takes effect immediately.
- **Refresh tokens** are opaque. Only their SHA-256 is stored, and they rotate on every use. Presenting an
  already-used refresh token is treated as theft and ends the session.
- **Platform admins** can enrol TOTP two-factor authentication, which is required in production. The
  TOTP secret is stored encrypted with AES-256-GCM using `TC_ENCRYPTION_KEY`, and a code cannot be
  replayed.

**Permissions**
- **Workspace admins** can do everything their tenant's modules allow.
- **Everyone else gets roles.** A role is a list of `resource:action` pairs. For custom data tables it can
  also restrict access to "own rows only" and to specific fields.
- **No escalation.** Nobody can grant, assign, edit or delete anything beyond what they hold themselves.
- **Modules gate everything.** A resource whose module is switched off is refused for everyone, admins
  included.

**Rate limits** are counted in Redis, per sign-in attempt, per user and per tenant. The platform admin can
set a tenant's limit, up to a platform maximum. If Redis is down, each instance falls back to local
counters at a quarter of the limit instead of failing open.

**Files**
- **Type comes from the content.** Each file's type is detected from its first bytes, not from the name
  or what the browser claims. Allowed: images (JPEG, PNG, GIF, WebP), PDF, Office documents, CSV and
  plain text. HTML, SVG and executables are refused.
- **Images are checked and thumbnailed.** Each image is measured from its header before being decoded,
  which stops decompression bombs, then gets a re-encoded thumbnail with location metadata stripped.
- **Downloads are served safely.** They carry `nosniff` and a sandboxing Content-Security-Policy.
  Anything that isn't an image or a PDF downloads as an attachment.
- **Private files stay private.** They need a signed-in user with `files:read`, or a signed link that
  expires (HMAC-SHA256 over tenant, file and expiry).
- **Limits.** Per-file limits and per-tenant quotas are enforced, and the quota check is serialised so
  parallel uploads can't overshoot it.
- **Switching storage is safe.** Each file remembers which store it is in, so changing the storage
  setting never breaks existing files.

**Email**
- **No email gets lost.** Mail is queued in the same transaction as the change that caused it, then sent
  by a background worker that retries with exponential backoff (up to 8 attempts). Several API instances
  can run the worker safely at once.
- **A workspace can use its own mail server**, but it must have a public address and use TLS. The address
  is re-checked before every send, so a tenant can't make the platform connect to internal services.
- **Invitation and reset links are safe.**
  - They're single-use and short-lived: 72 hours for invitations, 60 minutes for resets.
  - Only their SHA-256 is stored.
  - Forgot-password answers the same way whether or not the account exists.

**Commerce**
- **Prices come from the catalog.** Order requests carry variant ids and quantities only; totals, tax
  and shipping are calculated on the server from the order settings.
- **Stock cannot be oversold.** Stock rows are locked in a fixed order before any decision, and database
  constraints (`on_hand >= 0`, `0 <= reserved <= on_hand`) back that up. Every change to on-hand stock is
  written to an append-only ledger.
- **The order lifecycle is data.** Each status says what happens to stock when an order enters it:
  `reserve`, `deduct` (shipped), `release` (give back reserved stock) or nothing. The default is pending →
  confirmed → shipped → delivered, with cancelled. Shipped stock is never put back automatically; a return
  does that.
- **Retries are safe.** With an `Idempotency-Key` header, repeating an order or checkout request returns
  the first result instead of creating another order, even when the retries arrive at the same moment.
- **Payments.**
  - Gateway secrets are encrypted like other secrets.
  - Card payments are created at the provider with our payment id as its idempotency key.
  - Their outcome arrives through the signed webhook, which is applied only once per event.
  - Refunds can never exceed what is left of a payment.
  - When an order becomes fully paid it can move to a status you choose (default `confirmed`).
- **Cross-tenant references are impossible.** Every link between tenant tables is a composite
  `(tenant_id, id)` foreign key, because foreign-key checks are not filtered by row-level security.

**Custom data tables**
- **No SQL from requests.** A tenant's tables are definitions, not real database tables, so nothing a
  user designs ever runs as DDL. Values are stored as JSON, checked against their field's type before
  saving. Filter and sort requests bind field names and values as parameters.
- **Roles reach down to rows and fields.** A role can be limited to rows its users created, and to
  certain fields for reading and for writing. Hidden fields are left out of responses and exports, and
  cannot be filtered, sorted or searched on, so their values cannot be guessed either.
- **Unique fields are guaranteed** by a database key, with writers to the same table taking turns.
- **Edits are safe for existing data.** Making a field required, unique or removing a choice is checked
  against the stored rows first. Deleting a referenced row is refused, or empties the reference, as the
  field says.
- **Imports.**
  - The file is read once and checked: CSV in any encoding you name, or .xlsx with its expanded size
    capped to stop zip bombs.
  - You choose the mapping and can dry-run first.
  - A background worker writes in batches of 250, each committed together with its progress, so an
    interrupted import resumes exactly where it stopped.
  - It works with the importing user's current permissions. Invalid rows are skipped and listed by row
    number with the reason.
- **Exports cannot run formulas.** Text starting with `=`, `+`, `-` or `@` is prefixed with `'`, and the
  file has a UTF-8 marker so Excel shows Thai and other scripts correctly.

**Integrations**
- **Background jobs.**
  - They are queued in the same transaction as the change that needs them.
  - Any number of API instances can run workers: a job is leased with `FOR UPDATE SKIP LOCKED`.
  - Failures are retried with growing delays.
  - Delivery is *at least once*: if a server stops mid-job, the job runs again. This is why webhook
    receivers get a delivery id and push targets are recorded per device.
- **Webhooks cannot reach your private network.**
  - Addresses must be https and resolve only to public IPs.
  - The connection is made to the exact IP that was checked (the certificate is still checked
    against the host name), so changing DNS after the check does not help an attacker.
  - No proxies, no redirects.
  - An endpoint that fails 15 deliveries in a row is switched off until someone re-enables it.
- **Customer accounts are separate from staff.** Customers have their own token type, sessions and
  password rules (8+ characters). Their tokens work only for their shop's public endpoints, and staff
  tokens don't work there. Closing an account erases name, email and phone; orders stay for the
  business's records.
- **Push** uses Firebase Cloud Messaging with the tenant's own service account (stored encrypted).
  Device tokens that Firebase reports as gone are retired automatically.
- **Tracking links** are HMAC-signed, like order links, so nothing extra is stored and they can't be
  guessed.

## Project layout

```
src/main/kotlin/com/tenantcore/
  config/     settings (TC_* variables), database pools, Spring Security
  db/         transaction scopes that set the row-level security context
  security/   passwords, encryption, TOTP, JWT, permissions and modules, auth filter, rate limits
  auth/       workspace and platform sign-in
  platform/   platform admins, first-admin seed
  tenants/    tenants, modules, settings
  users/      users and roles
  audit/      audit log
  files/      uploads, type detection, thumbnails, local and S3 storage
  email/      SMTP settings, templates, outbox and sending worker
  data/       custom tables: schema, value validation, rows and queries, CSV/XLSX imports and worker
  integrations/ jobs, outbound address guard, webhooks, API keys, customer accounts, push (FCM),
              maps and QR/PromptPay, delivery tracking
  commerce/   catalog, inventory (stock engine), purchasing, orders and lifecycle, payments (Stripe,
              sandbox, manual), returns, analytics, public shop
  ops/        workspace exports, offboarding and purge, backup status
src/main/resources/db/migration/   Flyway SQL migrations (schema, RLS policies, grants)
src/test/kotlin/                   integration tests (Testcontainers) and unit tests
web/                               Vue 3 + TypeScript dashboard (Vite); web/e2e/run.mjs is the browser test
infra/postgres/01-roles.sh         creates tc_owner and tc_app on first database start
infra/backup/                      backup, restore and the backup service loop
```

## Tests

```bash
./gradlew test
```

Integration tests start PostgreSQL 16, Redis 7, Mailpit and an S3-compatible server in Docker with Testcontainers, so Docker must be
running. They cover:
- sign-in, lockout, refresh rotation and reuse detection;
- MFA enrolment and replay protection;
- tenant isolation through the API and directly in the database;
- role rules and no-escalation;
- module dependencies, settings validation and the audit log;
- uploads, type detection, quotas, signed links and S3 storage (against an S3-compatible test server);
- invitations, password resets, templates and retries, read back from a real SMTP inbox (Mailpit);
- catalog rules, stock ledger and database limits, purchase receiving;
- order pricing and tax, idempotent retries under concurrency, 20 simultaneous checkouts for 5 units
  (exactly 5 succeed), lifecycle editing, cross-tenant isolation of orders;
- manual, sandbox and Stripe payments, signed webhooks, refunds, returns with restocking, the public
  shop and its confirmation email, sales analytics;
- custom tables: every field type's validation, filters and sorting, own-rows and field-level
  permissions, references, schema changes against stored rows, isolation in the database, CSV and Excel
  imports (Thai Windows-874 encoding, dry runs, upserts, resuming after an interruption) and safe exports.

- background jobs and webhooks (signatures, retries, redelivery, nothing sent for a rolled-back change),
  the private-address guard, API keys and their limits, customer sign-up/confirmation/reset/closing,
  push through Firebase (including the signed OAuth assertion), geocoding and its cache, delivery
  tracking, QR codes decoded back, and PromptPay payloads.

- workspace exports (no secrets in the zip, other workspaces cannot fetch it), offboarding and purge
  (every tenant table emptied and stored files removed, other workspaces untouched), cancelling, the
  backup status, and that the dashboard is served with its security policy while API paths never fall
  back to it.

Stripe, Firebase, Google and Nominatim are tested against small local stand-ins for their APIs
(`FakeStripe`, `FakeApis`), not against the real services.
Before taking real payments, run one test payment with your Stripe test keys.

The dashboard has a browser test that drives a running stack (no mocks): it creates a workspace with every
module and goes through each page, doing the main task on most of them (products, stock, an order paid and
shipped, a return, a delivery, a purchase order received, a custom table with an import, a file, a role
and a team member, an API key) and checks that a team member only sees what their role allows. It fails on
any page error, console error, server error or unexpected 4xx, and checks phone width for sideways
scrolling. Screenshots go to `web/e2e/screens/`.

```bash
docker compose up -d
cd web && npm ci
E2E_BASE=http://localhost:8000 E2E_ADMIN_PASSWORD='<seed admin password>' npm run e2e
```

It needs a Chromium for Playwright (`npx playwright install chromium`) and a platform admin without
two-factor (`TC_PLATFORM_MFA_REQUIRED=false`, the default outside production).

CI (`.github/workflows/ci.yml`) runs the backend tests and type-checks and builds the dashboard on every push and pull request,
and builds the Docker image.

## Configuration

Every variable is listed and explained in `.env.example`. The required ones are:
- the database and Redis passwords;
- `TC_JWT_KEYS` and `TC_JWT_ACTIVE_KID`;
- `TC_ENCRYPTION_KEY`.

The application refuses to start while the database passwords, JWT keys, encryption key or seed password are missing or still `CHANGE_ME`; Docker Compose refuses to start without the database and Redis passwords.

**Back up `TC_ENCRYPTION_KEY` together with the database.** Without it, encrypted secrets such as two-factor
setups and payment gateway keys cannot be read.

**Stripe:** in the Stripe dashboard, add a webhook endpoint pointing at
`https://<your API host>/api/v1/public/payments/stripe/<tenant id>/webhook` (the exact path is shown in
`GET /payments/gateways`) for the events `payment_intent.succeeded`, `payment_intent.payment_failed`,
`payment_intent.canceled` and `refund.updated`, then paste its signing secret (`whsec_...`) into the gateway
settings.
