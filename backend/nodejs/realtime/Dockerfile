FROM node:24.22.0-bookworm-slim AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json ./
COPY src/ ./src/
COPY test/ ./test/
RUN npm run build

FROM node:24.22.0-bookworm-slim AS production-dependencies

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci --omit=dev

FROM node:24.22.0-bookworm-slim AS runtime

ENV NODE_ENV=production
WORKDIR /app

COPY --chown=node:node package.json ./
COPY --from=production-dependencies --chown=node:node /app/node_modules/ ./node_modules/
COPY --from=build --chown=node:node /app/dist/src/ ./dist/src/

USER node

EXPOSE 3001
CMD ["node", "dist/src/main.js"]
