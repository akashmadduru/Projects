# =============================================================================
# Vue Admin Product Catalog SPA - Multi-stage build
# =============================================================================
# Stage 1: Build the Vite app
FROM node:25-alpine AS builder

WORKDIR /app

# Copy package files (relative to project root since build context is ..)
COPY apps/admin-product-catalog/package.json apps/admin-product-catalog/package-lock.json ./

# Install dependencies
RUN npm ci

# Copy source code (relative to project root)
COPY apps/admin-product-catalog/src ./src
COPY apps/admin-product-catalog/index.html apps/admin-product-catalog/tsconfig.json apps/admin-product-catalog/vite.config.ts ./

# Build the Vue app
RUN npm run build

# =============================================================================
# Stage 2: Serve the built app with Node.js http-server
FROM node:25-alpine

WORKDIR /app

# Install http-server globally (lightweight static file server)
RUN npm install -g http-server

# Copy built app from builder stage
COPY --from=builder /app/dist ./dist

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=10s \
    CMD wget --quiet --tries=1 --spider http://localhost:3001/ || exit 1

# Serve on port 3001 (different from React app on 3000)
EXPOSE 3001

# Start the static file server
# Serve dist/ on port 3001 with CORS headers and SPA fallback (index.html for 404s)
CMD ["http-server", "dist", "-p", "3001", "--cors", "--spa", "index.html"]
