# =============================================================================
# React Product Catalog SPA - Multi-stage build
# =============================================================================
# Stage 1: Build the Vite app
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files (relative to project root since build context is ..)
COPY apps/product-catalog/package.json apps/product-catalog/package-lock.json ./

# Install dependencies
RUN npm ci

# Copy source code (relative to project root)
COPY apps/product-catalog/src ./src
COPY apps/product-catalog/index.html apps/product-catalog/tsconfig.json apps/product-catalog/vite.config.ts apps/product-catalog/tailwind.config.ts ./

# Build the React app
RUN npm run build

# =============================================================================
# Stage 2: Serve the built app with Node.js http-server
FROM node:20-alpine

WORKDIR /app

# Install http-server globally (lightweight static file server)
RUN npm install -g http-server

# Copy built app from builder stage
COPY --from=builder /app/dist ./dist

# Health check
HEALTHCHECK --interval=10s --timeout=5s --retries=3 --start-period=10s \
    CMD wget --quiet --tries=1 --spider http://localhost:3000/ || exit 1

# Serve on port 3000
EXPOSE 3000

# Start the static file server
# Serve dist/ on port 3000 with CORS headers and SPA fallback (index.html for 404s)
CMD ["http-server", "dist", "-p", "3000", "--cors", "--spa", "index.html"]
