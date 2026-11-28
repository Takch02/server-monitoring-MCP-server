FROM node:22-alpine AS web-build
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM caddy:2-alpine
COPY deploy/incident-web.Caddyfile /etc/caddy/Caddyfile
COPY --from=web-build /web/dist/ /srv/server-doctor-web/
