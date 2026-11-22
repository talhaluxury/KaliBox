#!/bin/bash
# Sets the VNC password from $VNCPW (VNC only uses the first 8 characters).
set -eo pipefail
[ -n "${VNCPW:-}" ] || { echo "VNCPW not set"; exit 1; }
mkdir -p /root/.vnc /root/.config/tigervnc

find_cmd() {
  for c in vncpasswd tigervncpasswd; do
    if command -v "$c" >/dev/null 2>&1; then echo "$c"; return 0; fi
  done
  return 1
}

PASSWD_CMD="$(find_cmd || true)"
if [ -z "$PASSWD_CMD" ]; then
  echo "vncpasswd not found: installing tigervnc-tools"
  DEBIAN_FRONTEND=noninteractive apt-get install -y tigervnc-tools || true
  PASSWD_CMD="$(find_cmd || true)"
fi

if [ -n "$PASSWD_CMD" ]; then
  printf '%s\n' "$VNCPW" | "$PASSWD_CMD" -f > /root/.vnc/passwd
  echo "VNC password saved (using $PASSWD_CMD)."
else
  # Fallback: write the VNC password file ourselves (DES with VNC's fixed key) using openssl.
  echo "No vncpasswd available: writing the password file with openssl"
  command -v openssl >/dev/null 2>&1 || DEBIAN_FRONTEND=noninteractive apt-get install -y openssl
  hex="$(printf '%s' "$VNCPW" | head -c 8 | od -An -tx1 | tr -d ' \n')"
  while [ "${#hex}" -lt 16 ]; do hex="${hex}00"; done
  bin=""
  for i in 0 2 4 6 8 10 12 14; do bin="$bin\\x${hex:$i:2}"; done
  printf "$bin" | openssl enc -des-ecb -nopad -K e84ad660c4721ae0 -provider legacy -provider default > /root/.vnc/passwd
  [ "$(stat -c %s /root/.vnc/passwd)" = "8" ] || { echo "Could not create the VNC password file."; exit 127; }
  echo "VNC password saved (openssl fallback)."
fi
chmod 600 /root/.vnc/passwd
cp -f /root/.vnc/passwd /root/.config/tigervnc/passwd 2>/dev/null || true   # may be the same file/symlink
chmod 600 /root/.config/tigervnc/passwd 2>/dev/null || true
