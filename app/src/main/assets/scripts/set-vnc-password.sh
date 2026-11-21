#!/bin/bash
# Sets the VNC password from $VNCPW (VNC only uses the first 8 characters).
set -eo pipefail
[ -n "${VNCPW:-}" ] || { echo "VNCPW not set"; exit 1; }
mkdir -p /root/.vnc /root/.config/tigervnc
printf '%s\n' "$VNCPW" | vncpasswd -f > /root/.vnc/passwd
chmod 600 /root/.vnc/passwd
cp /root/.vnc/passwd /root/.config/tigervnc/passwd
chmod 600 /root/.config/tigervnc/passwd
echo "VNC password saved."
