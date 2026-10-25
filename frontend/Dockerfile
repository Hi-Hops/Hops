FROM node:24.22.0-bookworm-slim

WORKDIR /workspace/frontend
RUN chown -R node:node /workspace
USER node

COPY --chown=node:node frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY --chown=node:node frontend/ ./
COPY --chown=node:node specs/contracts/ /workspace/specs/contracts/
COPY --chown=node:node specs/mocks/ /workspace/specs/mocks/

EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "5173", "--strictPort"]
