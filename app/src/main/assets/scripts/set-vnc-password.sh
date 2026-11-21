#!/bin/bash
# Sets the VNC password from $VNCPW (VNC only uses the first 8 characters).
set -eo pipefail
[ -n "${VNCPW:-}" ] || { echo "VNCPW not set"; exit 1; }
PASSWD_CMD=""
for c in vncpasswd tigervncpasswd; do
  if command -v "$c" >/dev/null 2>&1; then PASSWD_CMD="$c"; break; fi
done
if [ -z "$PASSWD_CMD" ]; then
  echo "No vncpasswd/tigervncpasswd found. Installed VNC binaries:"
  ls /usr/bin /usr/sbin 2>/dev/null | grep -i vnc || true
  exit 127
fi
mkdir -p /root/.vnc /root/.config/tigervnc
printf '%s\n' "$VNCPW" | "$PASSWD_CMD" -f > /root/.vnc/passwd
chmod 600 /root/.vnc/passwd
cp /root/.vnc/passwd /root/.config/tigervnc/passwd
chmod 600 /root/.config/tigervnc/passwd
echo "VNC password saved (using $PASSWD_CMD)."
