#!/bin/bash
# Long-lived supervisor: keeps Xtigervnc and websockify (noVNC) alive. Runs as the proot main process.
export HOME=/root USER=root LANG=C.UTF-8 XDG_RUNTIME_DIR=/tmp/runtime-root
GEOM="${GEOMETRY:-1280x720}"
mkdir -p "$XDG_RUNTIME_DIR" /tmp/.X11-unix /run/dbus
chmod 700 "$XDG_RUNTIME_DIR"
chmod 1777 /tmp/.X11-unix
dbus-uuidgen --ensure 2>/dev/null || true

clean() {
  vncserver -kill :1 >/dev/null 2>&1 || true
  rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
}
start_vnc() {
  clean
  echo "[desktop] starting vncserver :1 ($GEOM)"
  # localhost ONLY: never reachable from the network
  vncserver :1 -geometry "$GEOM" -depth 24 -localhost yes -SecurityTypes VncAuth \
    -xstartup /root/.vnc/xstartup 2>&1
}
start_ws() {
  echo "[desktop] starting websockify/noVNC on 127.0.0.1:6080"
  websockify --web /usr/share/novnc 127.0.0.1:6080 127.0.0.1:5901 >/tmp/websockify.log 2>&1 &
}

start_vnc
start_ws
while true; do
  sleep 3
  pgrep -f Xtigervnc >/dev/null 2>&1 || { echo "[desktop] VNC died, restarting"; start_vnc; }
  pgrep -f websockify >/dev/null 2>&1 || { echo "[desktop] websockify died, restarting"; start_ws; }
done
