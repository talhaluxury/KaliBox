#!/bin/bash
# Long-lived supervisor: keeps Xtigervnc and websockify (noVNC) alive. Runs as the proot main process.
export HOME=/root USER=root LANG=C.UTF-8 XDG_RUNTIME_DIR=/tmp/runtime-root
GEOM="${GEOMETRY:-1280x720}"
VNCSERVER="$(command -v vncserver || command -v tigervncserver || true)"
[ -n "$VNCSERVER" ] || { echo "[desktop] ERROR: vncserver/tigervncserver not found"; exit 127; }
mkdir -p "$XDG_RUNTIME_DIR" /tmp/.X11-unix /run/dbus
# bubblewrap cannot work in proot; GTK image loaders need this shim
install -m 755 /root/.kalibox/fake-bwrap /usr/local/bin/bwrap 2>/dev/null || true
chmod 700 "$XDG_RUNTIME_DIR"
chmod 1777 /tmp/.X11-unix
dbus-uuidgen --ensure 2>/dev/null || true

clean() {
  "$VNCSERVER" -kill :1 >/dev/null 2>&1 || true
  rm -f /tmp/.X1-lock /tmp/.X11-unix/X1
}
start_vnc() {
  clean
  mkdir -p /root/.vnc
  install -m 755 /root/.kalibox/xstartup /root/.vnc/xstartup 2>/dev/null || true
  echo "[desktop] starting vncserver :1 ($GEOM)"
  # localhost ONLY: never reachable from the network
  "$VNCSERVER" :1 -geometry "$GEOM" -depth 24 -localhost yes -SecurityTypes VncAuth \
    -xstartup /root/.vnc/xstartup 2>&1
}
# noVNC 1.6 in Kali has a touch bug (GestureHandler._touchEnd: "Cannot set properties of undefined").
# Serve a patched copy so touching the screen does not pop up the red error dialog.
prepare_novnc() {
  rm -rf /root/.kalibox/novnc
  cp -r /usr/share/novnc /root/.kalibox/novnc 2>/dev/null || return 1
  GH=/root/.kalibox/novnc/core/input/gesturehandler.js
  [ -f "$GH" ] && sed -i 's/^\( *\)this\._tracked\[\([^]]*\)\]\.\([A-Za-z]*\) = \(.*\);/\1if (this._tracked[\2]) this._tracked[\2].\3 = \4;/' "$GH"
  return 0
}
start_ws() {
  WEBDIR=/usr/share/novnc
  prepare_novnc && WEBDIR=/root/.kalibox/novnc
  echo "[desktop] starting websockify/noVNC ($WEBDIR) on 127.0.0.1:6080"
  websockify --web "$WEBDIR" 127.0.0.1:6080 127.0.0.1:5901 >/tmp/websockify.log 2>&1 &
}

start_vnc
start_ws
while true; do
  sleep 3
  pgrep -f Xtigervnc >/dev/null 2>&1 || { echo "[desktop] VNC died, restarting"; start_vnc; }
  pgrep -f websockify >/dev/null 2>&1 || { echo "[desktop] websockify died, restarting"; start_ws; }
done
