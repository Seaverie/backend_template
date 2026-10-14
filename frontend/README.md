# TenantCore Admin (Vue 3)

A responsive admin console for the TenantCore API: Vue 3 + TypeScript + Vite + Vue Router. It mirrors the
screens in the *TenantCore Admin Console* Figma file and reuses its design tokens (`src/styles/tokens.css`).

| Screen | Route | API |
| --- | --- | --- |
| Sign in (tenant / platform with MFA) | `/login` | `POST /auth/login`, `POST /platform/auth/login` |
| Dashboard | `/` | `GET /analytics/sales`, `GET /stock/levels`, `GET /orders` |
| Orders (list + detail, status changes) | `/orders` | `GET /orders`, `GET /orders/{id}`, `GET /order-lifecycle`, `POST /orders/{id}/transitions` |
| Users & roles | `/users` | `GET /users`, `GET /roles` |
| Webhooks | `/webhooks` | `GET /webhooks`, `GET /webhooks/{id}/deliveries`, redeliver, rotate secret |
| Platform · Tenants | `/platform/tenants` | `GET /platform/tenants`, tenant database, sync, offboard |

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
  and turns the API's `{"error": {...}}` body into an `ApiError`. Order transitions send an `Idempotency-Key`.
- `src/api/mock.ts`: the same functions returning sample data in the API's real shapes (`VITE_USE_MOCKS`).
- `src/api/session.ts`: tokens in `sessionStorage` (cleared when the tab closes).
- `src/router.ts`: route guards decide what to show; the API still enforces every permission.
- Responsive rules: the sidebar becomes a slide-in menu below 1024px, detail panels become slide-over
  sheets below 1200px, and tables turn into stacked cards below 768px (`components/DataTable.vue`).
