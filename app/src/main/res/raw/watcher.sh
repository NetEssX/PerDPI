#!/system/bin/sh
# Spawned by PerDPI through Shizuku. Runs as the shell user and keeps the
# display density in sync with the app that currently owns the screen.

export PATH=/system/bin:/system/xbin:$PATH
exec 2>&1

CONF=/data/local/tmp/perdpi.conf
PIDFILE=/data/local/tmp/perdpi.pid
BASE=@BASE@
HAD=@HAD@
last=""

restore_base() {
    if [ "$HAD" = "1" ] && [ -n "$BASE" ]; then
        wm density "$BASE" >/dev/null 2>&1
    else
        wm density reset >/dev/null 2>&1
    fi
}

cleanup() {
    restore_base
    rm -f "$PIDFILE"
}

on_signal() {
    cleanup
    exit 0
}

trap cleanup EXIT
trap on_signal TERM INT HUP
# If the app process disappears, stdout becomes a broken pipe. Keep running
# (and keep applying densities) instead of dying from SIGPIPE.
trap '' PIPE

# Record the watcher pid so a later service start can reap a stale instance.
echo $$ > "$PIDFILE"

while true; do
    pkg=$(dumpsys activity activities 2>/dev/null \
        | grep -E 'topResumedActivity|mResumedActivity' \
        | tail -n 1 \
        | sed -E 's/.*u[0-9]+ ([^/ ]+)\/.*/\1/')

    if [ -z "$pkg" ]; then
        pkg=$(dumpsys window 2>/dev/null \
            | grep -E 'mCurrentFocus|mFocusedApp' \
            | tail -n 1 \
            | sed -E 's/.* ([A-Za-z0-9_.]+)\/[^ ]*.*/\1/')
    fi

    # Ignore malformed matches such as "Window{...".
    case "$pkg" in
        *[!A-Za-z0-9_.]*|"") pkg="" ;;
    esac

    if [ -z "$pkg" ]; then
        sleep 0.7
        continue
    fi

    dpi=$(grep -F "$pkg=" "$CONF" 2>/dev/null | tail -n 1 | cut -d= -f2)
    case "$dpi" in
        ''|*[!0-9]*) dpi="" ;;
    esac

    # User-selected fallback for apps without an override; 0/empty means the
    # physical density captured when the service started.
    default_dpi=$(grep '^default=' "$CONF" 2>/dev/null | head -n 1 | cut -d= -f2)
    case "$default_dpi" in
        ''|*[!0-9]*) default_dpi=0 ;;
    esac

    if [ -n "$dpi" ] && [ "$dpi" -gt 0 ]; then
        want="$dpi"
    else
        want="$default_dpi"
    fi

    key="$pkg|$want"

    if [ "$key" != "$last" ]; then
        if [ -n "$want" ] && [ "$want" -gt 0 ] 2>/dev/null; then
            if wm density "$want" >/dev/null 2>&1; then
                echo "TOP|$pkg|$want"
            else
                echo "ERR|wm density $want failed"
            fi
        else
            restore_base
            echo "TOP|$pkg|0"
        fi
        last="$key"
    fi

    sleep 0.7
done
