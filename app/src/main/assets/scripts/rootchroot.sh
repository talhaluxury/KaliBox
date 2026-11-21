#!/system/bin/sh
# Real-root launcher: mounts /dev /proc /sys and chroots into the Kali rootfs.
# Usage: rootchroot.sh <rootfs> KEY=VAL... command...
R="$1"; shift
mounted() { grep -q " $1 " /proc/mounts; }
mkdir -p "$R/dev" "$R/proc" "$R/sys" "$R/sdcard" "$R/tmp" "$R/run"
mounted "$R/dev"      || mount -o bind /dev "$R/dev"
mkdir -p "$R/dev/pts" "$R/dev/shm"
mounted "$R/dev/pts"  || mount -o bind /dev/pts "$R/dev/pts"
mounted "$R/dev/shm"  || mount -t tmpfs tmpfs "$R/dev/shm"
mounted "$R/proc"     || mount -t proc proc "$R/proc"
mounted "$R/sys"      || mount -t sysfs sysfs "$R/sys"
mounted "$R/sdcard"   || mount -o bind /storage/emulated/0 "$R/sdcard" 2>/dev/null
# /data is often mounted nosuid/nodev; allow suid binaries (sudo, ping) inside the rootfs
mount -o remount,dev,suid /data 2>/dev/null
chmod 1777 "$R/tmp" 2>/dev/null
exec chroot "$R" /usr/bin/env -i "$@"
