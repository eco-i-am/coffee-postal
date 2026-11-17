# Coffee Postal POS

Branch + HQ management and point-of-sale for Coffee Postal PH.
Spring Boot 4 (Java 21), PostgreSQL 16, Thymeleaf + HTMX. Plan and specs live in the
`ishiki-os` workspace: `product-dev/coffee-postal/specs/system-plan.md`.

## Run locally

Needs Java 21+ and Docker.

```bash
docker compose up -d db                                   # PostgreSQL on :5432
./gradlew bootRun --args='--spring.profiles.active=dev'   # app on http://localhost:8080
```

The `dev` profile creates demo data (dev only):

| User | Role | Password / PIN |
|---|---|---|
| `admin` | Super Admin (HQ) | `coffee123` |
| `manager` | Branch Manager, Cebu City | `coffee123` |
| `cashier` | Cashier, Cebu City | `coffee123` (PIN `1234`) |

## Test

```bash
./gradlew test      # starts PostgreSQL in Docker via Testcontainers
```

## Layout

```
src/main/java/ph/coffeepostal/pos/
  shared/      Money (centavos), AuditService, SecurityConfig
  iam/         users, roles, permissions (module x view/add/edit/delete), login history, first-run bootstrap
  branch/      Branch list (HQ)
  catalog/ inventory/ branchpo/ sales/ finance/ reporting/ engagement/ hr/   (later phases)
  web/         shell controllers for /admin, /branch, /pos
src/main/resources/db/migration/   Flyway: V1 core tables, V2 modules + role permissions
deploy/        compose.prod.yaml, Caddyfile, backup.sh   (Oracle VM)
```

Rules: money is integer centavos; every money/stock/price/role change goes through `AuditService`;
branch users are scoped to their branch on the server.

## Deploy (Oracle Always Free, Ampere A1)

1. Create the VM (Ubuntu arm64), open ports 80/443, install Docker.
2. `git clone` to `/opt/coffee-postal`, copy `.env.example` to `.env`, fill it in.
3. Point your domain at the VM (Cloudflare DNS).
4. `cd deploy && docker compose --env-file ../.env -f compose.prod.yaml up -d`
5. Sign in with `ADMIN_USERNAME` / `ADMIN_PASSWORD`, then change the password.
6. Add the GitHub secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY` so pushes to `main` deploy automatically.
7. Add a cron job for `deploy/backup.sh` and test a restore before launch.

In production there is no default admin password: nobody can sign in until `ADMIN_PASSWORD` is set.
