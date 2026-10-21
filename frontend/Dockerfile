FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# Node 22's dual-stack auto-selection times out on networks with broken IPv6 (npm ETIMEDOUT); build-time only.
RUN NODE_OPTIONS=--no-network-family-autoselection npm ci
COPY . .
RUN npm run build

FROM nginx:1.30-alpine
# Pull in OS security fixes released after the base image was built (the scanner flagged libexpat and libx11).
RUN apk upgrade --no-cache
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
