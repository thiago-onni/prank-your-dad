# syntax=docker/dockerfile:1.7
# Build do shell Next.js (output standalone) em imagem non-root.
FROM node:22-alpine AS base
ENV PNPM_HOME=/pnpm PATH=/pnpm:$PATH NEXT_TELEMETRY_DISABLED=1
RUN corepack enable && corepack prepare pnpm@10.28.0 --activate

FROM base AS deps
WORKDIR /repo
COPY web/package.json web/pnpm-workspace.yaml web/pnpm-lock.yaml web/.npmrc ./
COPY web/apps/shell/package.json apps/shell/
COPY web/packages/design-system/package.json packages/design-system/
COPY web/packages/domain-components/package.json packages/domain-components/
COPY web/packages/api-client/package.json packages/api-client/
COPY web/packages/auth/package.json packages/auth/
RUN --mount=type=cache,id=pnpm,target=/pnpm/store pnpm install --frozen-lockfile

FROM base AS build
WORKDIR /repo
COPY --from=deps /repo/node_modules ./node_modules
COPY --from=deps /repo/apps ./apps
COPY --from=deps /repo/packages ./packages
COPY web/ ./
# O contrato OpenAPI fica fora de web/ (../contracts); copie-o para a geração de tipos se necessário.
ENV NODE_ENV=production
RUN pnpm --filter @sus-nexus/shell build

FROM node:22-alpine AS runner
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
WORKDIR /app
RUN addgroup -S -g 1001 nodejs && adduser -S -u 1001 -G nodejs nextjs
COPY --from=build --chown=nextjs:nodejs /repo/apps/shell/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /repo/apps/shell/.next/static ./apps/shell/.next/static
COPY --from=build --chown=nextjs:nodejs /repo/apps/shell/public ./apps/shell/public
USER nextjs
EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://127.0.0.1:3000/api/health || exit 1
CMD ["node", "apps/shell/server.js"]
