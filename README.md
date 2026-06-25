# emvagent-backend

Java 17/21 Spring Boot backend for EMVAgent. Handles auth (email OTP / magic-link
+ JWT), feedback persistence, billing, and proxies chat to the Python AI service
(`emvagent-ai`). Postgres + Flyway. Served on Cloud Run.

Part of the workspace at `/Users/esrefaltuntas/workspaces/fairbit/` alongside
`emvagent-ai` (Python AI microservice) and `emvagent-frontend` (Next.js web UI).

## Deploy (Cloud Run)

Service: `emvagent-backend` · region `us-central1` · project `emvagent-ai-dev`.
Built from the repo `Dockerfile` (Maven → Temurin JRE 21) via Cloud Build.

### Standard deploy (code change only — recommended)

Preserves the running revision's environment variables (DB creds, `JWT_SECRET`,
`AI_SERVICE_URL`, `SPRING_PROFILES_ACTIVE=prod`). `--source` uploads the local
working directory to Cloud Build, so uncommitted local changes are included — no
commit required.

```bash
gcloud run deploy emvagent-backend \
  --source /Users/esrefaltuntas/workspaces/fairbit/emvagent-backend \
  --region us-central1 \
  --project emvagent-ai-dev \
  --allow-unauthenticated
```

### Deploy with environment-variable changes

Only when you actually need to change env vars. `--set-env-vars` REPLACES the full
set, so you must pass every variable. The real secret values are **not stored in
this repo** — they live in the running Cloud Run service config (and the operator's
shell history). Replace the `<...>` placeholders before running.

```bash
gcloud run deploy emvagent-backend \
  --source /Users/esrefaltuntas/workspaces/fairbit/emvagent-backend \
  --region us-central1 \
  --project emvagent-ai-dev \
  --allow-unauthenticated \
  --set-env-vars="SPRING_DATASOURCE_URL=jdbc:postgresql://<DB_HOST>:5432/emv_ai,SPRING_DATASOURCE_USERNAME=<DB_USER>,SPRING_DATASOURCE_PASSWORD=<DB_PASSWORD>,SPRING_PROFILES_ACTIVE=prod,JWT_SECRET=<JWT_SECRET>,AI_SERVICE_URL=https://emvagent-ai-829603232053.us-central1.run.app"
```

> Security: never commit real DB passwords or `JWT_SECRET` into this README or any
> tracked file. Treat the deploy command above with placeholders only.

### Logs

```bash
gcloud run services logs read emvagent-backend \
  --region=us-central1 --project=emvagent-ai-dev --limit=20
```

## Config notes

- `src/main/resources/application.yml` — base config. `jwt.expiration-ms` controls
  JWT lifetime (currently `1296000000` = 15 days).
- `application-prod.yml` — prod overrides; does **not** override `jwt.expiration-ms`,
  so prod inherits the base value.
- `application-local.yml` — local dev.
- Auth uses a single JWT access token (no refresh token); token is returned in the
  `/auth/magic-link/verify` JSON response and stored in the frontend's localStorage.

## Local development

```bash
docker-compose up -d            # Postgres
mvn spring-boot:run             # or: mvn package && java -jar target/*.jar
```
