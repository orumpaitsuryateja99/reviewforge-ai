FROM node:22-alpine AS dependencies
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=dependencies /app/node_modules ./node_modules
COPY . .
RUN npm run build

FROM node:22-alpine AS runtime
RUN addgroup -S reviewforge && adduser -S reviewforge -G reviewforge
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build --chown=reviewforge:reviewforge /app/.next/standalone ./
COPY --from=build --chown=reviewforge:reviewforge /app/.next/static ./.next/static
USER reviewforge
EXPOSE 3000
ENV PORT=3000 HOSTNAME=0.0.0.0
CMD ["node", "server.js"]
