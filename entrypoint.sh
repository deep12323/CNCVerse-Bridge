#!/usr/bin/env bash
set -e

# 1. Setup user data directories
mkdir -p "$HOME/.cncverse"
mkdir -p "$HOME/.cncverse_bridge/plugins"

# 2. Apply Hugging Face Space secrets or environment variables
if [ -n "$EXTENSION_SETTINGS" ]; then
    echo "$EXTENSION_SETTINGS" > "$HOME/.cncverse/ext_settings.txt"
    echo "✅ Loaded EXTENSION_SETTINGS into $HOME/.cncverse/ext_settings.txt"
fi

if [ -n "$INSTALLED_PLUGINS" ]; then
    echo "$INSTALLED_PLUGINS" > "$HOME/.cncverse_bridge/installed_plugins.json"
    echo "✅ Loaded INSTALLED_PLUGINS into $HOME/.cncverse_bridge/installed_plugins.json"
fi

if [ -n "$REPO_URLS" ]; then
    echo "$REPO_URLS" > "$HOME/.cncverse/repos.txt"
    echo "✅ Loaded REPO_URLS into $HOME/.cncverse/repos.txt"
fi

# 3. Optional Cloudflare Worker sync (if configured)
if [ -n "$CF_WORKER_URL" ]; then
    echo "Cloudflare Worker URL detected: $CF_WORKER_URL"
    CF_SECRET="${CF_WORKER_SECRET:-cncverse_secret_2026}"
    
    if [ -n "$SPACE_HOST" ]; then
        HF_DIRECT_URL="https://${SPACE_HOST}"
        echo "Syncing Hugging Face direct URL ($HF_DIRECT_URL) to Cloudflare Worker..."
        curl -s -X POST "$CF_WORKER_URL/__update_backend" \
            -H "Authorization: Bearer $CF_SECRET" \
            -H "Content-Type: application/json" \
            -d "{\"backend_url\": \"$HF_DIRECT_URL\"}" || true
    fi
fi

# 4. Set environment and launch CNCVerse Bridge
export PORT="${PORT:-7860}"
export HEADLESS="1"

echo "=========================================================="
echo "🚀 CNCVerse Bridge Starting..."
echo "Listening Port: $PORT"
if [ -n "$SPACE_HOST" ]; then
    echo "📺 Stremio Addon URL: https://${SPACE_HOST}/manifest.json"
else
    echo "📺 Stremio Addon URL: http://127.0.0.1:${PORT}/manifest.json"
fi
echo "=========================================================="

exec /app/bridge/bin/cncverse-bridge
