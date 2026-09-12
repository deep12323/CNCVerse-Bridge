# ── Stage 1: Build the Server application with Gradle ──
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /build

COPY . .
RUN chmod +x ./gradlew

# Build server distribution
RUN ./gradlew installDist --no-daemon

# ── Stage 2: Minimal Runtime for Hugging Face Spaces ──
FROM eclipse-temurin:17-jre-jammy

# Install utilities and cloudflared (for optional Cloudflare compatibility)
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    && curl -fsSL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64.deb -o /tmp/cloudflared.deb \
    && dpkg -i /tmp/cloudflared.deb \
    && rm -f /tmp/cloudflared.deb \
    && rm -rf /var/lib/apt/lists/*

# Hugging Face Spaces runs as user 1000
RUN useradd -m -u 1000 user

WORKDIR /app

# Copy the built distribution from builder
COPY --from=builder --chown=user:user /build/build/install/cncverse-bridge ./bridge

# Copy entrypoint script
COPY --chown=user:user entrypoint.sh ./entrypoint.sh
RUN chmod +x ./entrypoint.sh ./bridge/bin/*

# Set environment
ENV PORT=7860 \
    HEADLESS=1 \
    HOME=/home/user

# Switch to non-root user (UID 1000)
USER user
WORKDIR /home/user

# Hugging Face Spaces default port
EXPOSE 7860

ENTRYPOINT ["/app/entrypoint.sh"]
