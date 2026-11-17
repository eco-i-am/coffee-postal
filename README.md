# Coffee Postal POS

Branch + HQ management and point-of-sale for Coffee Postal PH.
Two apps in one repo: a Java API (`backend/`) and a React UI (`frontend/`, plain JavaScript).
Plan and specs live in the `ishiki-os` workspace: `product-dev/coffee-postal/specs/system-plan.md`.

```
coffee-postal/
  backend/    Spring Boot 4 (Java 21) JSON API, PostgreSQL 16, Flyway
  frontend/   React + Vite, plain JavaScript (no TypeScript)
  deploy/     compose.prod.yaml, backup.sh   (Oracle VM)
  compose.yaml  dev database only
```

## Run locally

Needs Java 21+, Node 20+ and Docker (for the database).

```bash
docker compose up -d db                                                  # PostgreSQL on :5432
cd backend  && ./gradlew bootRun --args='--spring.profiles.active=dev'   # API on :8080
cd frontend && npm install && npm run dev                                # UI on http://localhost:5173
```

The UI dev server forwards `/api` to the backend, so open http://localhost:5173.

The `dev` profile creates demo data (dev only):

| User | Role | Password / PIN |
|---|---|---|
| `admin` | Super Admin (HQ) | `coffee123` |
| `manager` | Branch Manager, Cebu City | `coffee123` |
| `cashier` | Cashier, Cebu City | `coffee123` (PIN `1234`) |

## Test

```bash
cd backend  && ./gradlew test    # starts PostgreSQL in Docker via Testcontainers
cd frontend && npm test
```

GitHub Actions runs both on every push.

## Backend layout

```
backend/src/main/java/ph/coffeepostal/pos/
  shared/      Money (centavos), AuditService, SecurityConfig, ApiException
  iam/         users, roles, permissions (module x view/add/edit/delete), login history, /api/me
  branch/      Branch list (HQ)
  catalog/     products, categories, the POS menu
  sales/       orders (server-priced, idempotent)
  inventory/ branchpo/ finance/ reporting/ engagement/ hr/   (later phases)
backend/src/main/resources/db/migration/   Flyway migrations
```

## Frontend layout

```
frontend/src/
  app/         App (routes), Shell (sidebar), AuthContext
  features/    auth, branches, products, pos   (one folder per screen group)
  shared/      api client (cookies + CSRF), hooks, money formatting
  styles/      brand.css, app.css
```

Rules: money is integer centavos; every money/stock/price/role change goes through `AuditService`;
branch users are scoped to their branch on the server; the browser never sends prices.

## API in one minute

- `POST /api/auth/login` (form: username, password) sets a session cookie. `POST /api/auth/logout`.
- `GET /api/me` returns who is signed in, role, branch and permissions. 401 means sign in again.
- State-changing calls send the `X-XSRF-TOKEN` header (read from the `XSRF-TOKEN` cookie).
- Errors come back as `{ "message": "...", "fields": { "name": "..." } }`.

## Deploy (Oracle Always Free, Ampere A1) - paused

1. Create the VM (Ubuntu arm64), open ports 80/443, install Docker.
2. `git clone` to `/opt/coffee-postal`, copy `.env.example` to `.env`, fill it in.
3. Point your domain at the VM (Cloudflare DNS).
4. `cd deploy && docker compose --env-file ../.env -f compose.prod.yaml up -d`
5. Sign in with `ADMIN_USERNAME` / `ADMIN_PASSWORD`, then change the password.
6. Add the GitHub secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, then run the workflow manually to deploy.
7. Add a cron job for `deploy/backup.sh` and test a restore before launch.

Two images are built: `coffee-postal-api` (Java) and `coffee-postal-web` (the UI served by Caddy, which also
forwards `/api` to the API). In production there is no default admin password: nobody can sign in until
`ADMIN_PASSWORD` is set.
