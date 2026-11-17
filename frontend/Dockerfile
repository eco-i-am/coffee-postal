# Builds the React app, then serves it with Caddy (which also proxies /api to the Java backend).
FROM node:22-alpine AS build
WORKDIR /web
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM caddy:2
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /web/dist /srv
