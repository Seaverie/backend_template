# TenantCore Admin (Vue 3)

A responsive admin console for the TenantCore API: Vue 3 + TypeScript + Vite + Vue Router. It mirrors the
screens in the *TenantCore Admin Console* Figma file and reuses its design tokens (`src/styles/tokens.css`).

Every screen is wired to the API: buttons create, edit and delete real records, and errors from the
API (validation, permissions, conflicts) appear as messages next to the action.

| Screen | Route | What you can do |
| --- | --- | --- |
| Sign in | `/login` | Workspace sign-in, or platform sign-in with an authenticator code |
| Dashboard | `/` | Revenue, orders and low stock for 7/30/90 days; export sales as CSV |
| Orders | `/orders` | Create orders, move them through the lifecycle, take payments, run returns (approve, receive into a bin, refund, close), export CSV |
| Catalog & stock | `/catalog` | Products and variants, warehouses and bins, stock adjustments |
| Data tables | `/data` | Browse, search, sort, create, edit and delete records in custom tables; forms are generated from the field definitions and your permissions |
| Imports | `/imports` | Upload CSV/XLSX into a table, download a template, watch the background task |
| Users & roles | `/users` | Add users, enable/disable, make admin, assign roles, build roles with row and field limits |
| API keys | `/api-keys` | Create scoped keys (secret shown once), rotate with a grace period, revoke |
| Webhooks | `/webhooks` | Add, edit, enable/disable and delete endpoints, view deliveries, redeliver, rotate the signing secret |
| Audit log | `/audit` | Every change with its details, paged; export CSV |
| Settings | `/settings` | Workspace rate limit, payment gateways (Stripe or sandbox) and their webhook URLs |
| Platform · Tenants | `/platform/tenants` | Create tenants, suspend/reactivate, rate limits, dedicated databases (connect, rotate, sync, disable), offboarding, schema snapshot export |
| Platform · Schema & tables | `/platform/tables` | Create tables, add and drop fields, delete tables |
| Platform · Dead letters | `/platform/dead-letters` | Inspect and requeue failed jobs |
| Platform · Diagnostics | `/platform/diagnostics` | Slowest statements, tables that need an index, task lookup |
| Platform · Security | `/platform/security` | Turn on two-factor authentication |

Imports run on the Celery worker, so start it too (`docker compose up` does) or uploads stay queued.
Payments with the sandbox gateway stay pending until a signed webhook reaches
`POST /api/v1/webhooks/payments/{gateway_id}`, exactly as with a real provider.

## Run it

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173, with sample data
```

To use the real backend, start the API (`docker compose up` in the repo root), then:

```bash
cp .env.example .env
# set VITE_USE_MOCKS=false
npm run dev
```

`npm run dev` proxies `/api` to `http://localhost:8000` (change with `API_PROXY_TARGET`), so no CORS
setup is needed. For a production build: `npm run build` → static files in `dist/`. Serve them behind
the same domain as the API, with a fallback to `index.html` for client-side routes (or build with
`VITE_ROUTER_MODE=hash`).

## How it fits together

- `src/api/client.ts`: one typed function per endpoint. Adds the bearer token, refreshes it once on 401,
  and turns the API's `{"error": {...}}` body into an `ApiError`. Every write sends a fresh `Idempotency-Key`.
  When a session expires it signs out and returns to `/login`.
- `src/api/mock.ts`: the same functions backed by in-memory sample data in the API's real shapes (`VITE_USE_MOCKS`),
  so every button also works in the demo; changes last until the page reloads.
- `src/ui.ts`, `src/composables.ts`: toasts, the confirm dialog, CSV/download helpers, and load/busy state for views.
- `src/api/session.ts`: tokens in `sessionStorage` (cleared when the tab closes).
- `src/router.ts`: route guards decide what to show; the API still enforces every permission.
- Responsive rules: the sidebar becomes a slide-in menu below 1024px, detail panels become slide-over
  sheets below 1200px, and tables turn into stacked cards below 768px (`components/DataTable.vue`).
