#!/bin/bash
set -eo pipefail
export DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8
# Gentle apt settings: fewer parallel connections/processes (Android can kill many child processes)
cat > /etc/apt/apt.conf.d/98kalibox-net <<'CONF'
Acquire::Queue-Mode "access";
Acquire::http::Pipeline-Depth "0";
Acquire::Retries "5";
Acquire::http::Timeout "30";
APT::Install-Recommends "false";
CONF

echo "==> apt update"
apt-get update

PKGS="kali-desktop-xfce tigervnc-standalone-server tigervnc-tools dbus-x11 novnc websockify firefox-esr xfce4-terminal desktop-base librsvg2-common shared-mime-info adwaita-icon-theme fonts-dejavu-core fonts-liberation locales procps"
echo "==> installing Kali XFCE + VNC + noVNC + Firefox (large download, be patient)"
ok=0
for i in 1 2 3 4; do
  if apt-get install -y --no-install-recommends $PKGS; then ok=1; break; fi
  echo "!! attempt $i failed (exit $?). Retrying; packages already downloaded are kept."
  dpkg --configure -a || true
  sleep 5
done
[ "$ok" = 1 ] || { echo "Desktop install failed after 4 attempts."; exit 1; }
install -m 755 /root/.kalibox/fake-bwrap /usr/local/bin/bwrap 2>/dev/null || true
echo "==> configuring locale"
sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen 2>/dev/null || true
locale-gen >/dev/null 2>&1 || true

echo "==> configuring VNC + XFCE for proot"
mkdir -p /root/.vnc /root/.config/xfce4/xfconf/xfce-perchannel-xml /root/.config/autostart
install -m 755 /root/.kalibox/xstartup /root/.vnc/xstartup

# Disable compositing (no GPU in proot)
cat > /root/.config/xfce4/xfconf/xfce-perchannel-xml/xfwm4.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfwm4" version="1.0">
  <property name="general" type="empty">
    <property name="use_compositing" type="bool" value="false"/>
  </property>
</channel>
XML

# Disable power manager actions
cat > /root/.config/xfce4/xfconf/xfce-perchannel-xml/xfce4-power-manager.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<channel name="xfce4-power-manager" version="1.0">
  <property name="xfce4-power-manager" type="empty">
    <property name="dpms-enabled" type="bool" value="false"/>
    <property name="blank-on-ac" type="int" value="0"/>
    <property name="dpms-on-ac-sleep" type="uint" value="0"/>
    <property name="dpms-on-ac-off" type="uint" value="0"/>
    <property name="lock-screen-suspend-hibernate" type="bool" value="false"/>
  </property>
</channel>
XML

# Disable screensaver / locker / power-manager autostart
for n in xfce4-screensaver xfce4-power-manager light-locker xscreensaver; do
  printf '[Desktop Entry]\nHidden=true\n' > "/root/.config/autostart/$n.desktop"
done

# Browser sandboxes cannot work in proot
if [ -f /usr/share/applications/firefox-esr.desktop ]; then
  sed -i 's|^Exec=firefox-esr|Exec=env MOZ_DISABLE_CONTENT_SANDBOX=1 MOZ_FAKE_NO_SANDBOX=1 firefox-esr|' /usr/share/applications/firefox-esr.desktop
fi
for f in /usr/share/applications/chromium*.desktop; do
  [ -f "$f" ] && sed -i 's|^\(Exec=[^ ]*chromium\)|\1 --no-sandbox|' "$f"
done
mkdir -p /etc/chromium.d
printf '%s\n' 'export CHROMIUM_FLAGS="$CHROMIUM_FLAGS --no-sandbox"' > /etc/chromium.d/kalibox
grep -q MOZ_DISABLE_CONTENT_SANDBOX /etc/environment 2>/dev/null || \
  printf 'MOZ_DISABLE_CONTENT_SANDBOX=1\nMOZ_FAKE_NO_SANDBOX=1\n' >> /etc/environment

dbus-uuidgen --ensure 2>/dev/null || true
bash /root/.kalibox/set-vnc-password.sh
echo "==> Desktop installed."
