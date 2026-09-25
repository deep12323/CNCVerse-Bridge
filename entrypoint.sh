#!/usr/bin/env bash
set -e

# 1. Setup user data directories (detect persistent volume if available)
if [ -d "/data" ] && [ -w "/data" ]; then
    echo "💾 Persistent storage detected at /data! Linking data directories..."
    mkdir -p /data/cncverse /data/cncverse_bridge/plugins
    ln -sfn /data/cncverse "$HOME/.cncverse"
    ln -sfn /data/cncverse_bridge "$HOME/.cncverse_bridge"
else
    mkdir -p "$HOME/.cncverse"
    mkdir -p "$HOME/.cncverse_bridge/plugins"
fi

# 2. Automated Cloud Git Persistence (if GITHUB_TOKEN or GH_TOKEN is configured)
GIT_TOKEN="${GITHUB_TOKEN:-$GH_TOKEN}"
REPO_SLUG="${GITHUB_REPOSITORY:-deep12323/CNCVerse-Bridge}"

if [ -n "$GIT_TOKEN" ]; then
    echo "🔑 GitHub Token detected! Setting up git persistence with $REPO_SLUG on branch 'bridge-data'..."
    git config --global user.name "CNCVerse Bot" 2>/dev/null || true
    git config --global user.email "bot@cncverse.local" 2>/dev/null || true

    SYNC_REPO_URL="https://x-access-token:${GIT_TOKEN}@github.com/${REPO_SLUG}.git"

    TMP_CLONE=$(mktemp -d)
    if git clone --depth 1 --branch bridge-data "$SYNC_REPO_URL" "$TMP_CLONE" 2>/dev/null; then
        echo "📦 Found existing bridge-data branch. Restoring extensions and settings..."
        if [ -d "$TMP_CLONE/plugins" ]; then
            cp -r "$TMP_CLONE/plugins"/* "$HOME/.cncverse_bridge/plugins/" 2>/dev/null || true
        fi
        if [ -f "$TMP_CLONE/installed_plugins.json" ]; then
            cp "$TMP_CLONE/installed_plugins.json" "$HOME/.cncverse_bridge/installed_plugins.json" 2>/dev/null || true
        fi
        if [ -f "$TMP_CLONE/ext_settings.txt" ]; then
            cp "$TMP_CLONE/ext_settings.txt" "$HOME/.cncverse/ext_settings.txt" 2>/dev/null || true
        fi
        if [ -f "$TMP_CLONE/repos.txt" ]; then
            cp "$TMP_CLONE/repos.txt" "$HOME/.cncverse/repos.txt" 2>/dev/null || true
        fi
        echo "✅ Restored extensions, repos, and settings from git persistence branch!"
    else
        echo "ℹ️ bridge-data branch does not exist yet; will create on first sync."
    fi
    rm -rf "$TMP_CLONE"

    # Launch background continuous sync daemon (commits & pushes changes every 45s)
    (
        while true; do
            sleep 45
            if [ -d "$HOME/.cncverse_bridge" ]; then
                TMP_SYNC=$(mktemp -d)
                mkdir -p "$TMP_SYNC/plugins"
                cp -r "$HOME/.cncverse_bridge/plugins"/* "$TMP_SYNC/plugins/" 2>/dev/null || true
                [ -f "$HOME/.cncverse_bridge/installed_plugins.json" ] && cp "$HOME/.cncverse_bridge/installed_plugins.json" "$TMP_SYNC/"
                [ -f "$HOME/.cncverse/ext_settings.txt" ] && cp "$HOME/.cncverse/ext_settings.txt" "$TMP_SYNC/"
                [ -f "$HOME/.cncverse/repos.txt" ] && cp "$HOME/.cncverse/repos.txt" "$TMP_SYNC/"

                cd "$TMP_SYNC"
                git init -q
                git config user.name "CNCVerse Bot"
                git config user.email "bot@cncverse.local"
                git checkout -q -B bridge-data
                git add -A
                if ! git diff-index --quiet HEAD 2>/dev/null; then
                    git commit -q -m "chore: auto-sync installed extensions & settings [skip ci]"
                    git push -q -f "$SYNC_REPO_URL" bridge-data >/dev/null 2>&1 || true
                fi
                cd - >/dev/null
                rm -rf "$TMP_SYNC"
            fi
        done
    ) &
fi

# 3. Apply Hugging Face Space secrets or environment variables (fallback if not already present)
if [ -n "$EXTENSION_SETTINGS" ]; then
    if [ ! -s "$HOME/.cncverse/ext_settings.txt" ]; then
        echo "$EXTENSION_SETTINGS" > "$HOME/.cncverse/ext_settings.txt"
        echo "✅ Loaded EXTENSION_SETTINGS into $HOME/.cncverse/ext_settings.txt"
    fi
fi

if [ -n "$INSTALLED_PLUGINS" ]; then
    if [ ! -s "$HOME/.cncverse_bridge/installed_plugins.json" ]; then
        echo "$INSTALLED_PLUGINS" > "$HOME/.cncverse_bridge/installed_plugins.json"
        echo "✅ Loaded INSTALLED_PLUGINS into $HOME/.cncverse_bridge/installed_plugins.json"
    fi
fi

if [ -n "$REPO_URLS" ]; then
    if [ ! -s "$HOME/.cncverse/repos.txt" ]; then
        echo "$REPO_URLS" > "$HOME/.cncverse/repos.txt"
        echo "✅ Loaded REPO_URLS into $HOME/.cncverse/repos.txt"
    fi
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
