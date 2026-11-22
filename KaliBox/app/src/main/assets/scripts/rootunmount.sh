#!/system/bin/sh
# Lazily unmounts everything mounted below the rootfs (deepest first). Usage: rootunmount.sh <rootfs>
R="$1"
grep " $R/" /proc/mounts | cut -d' ' -f2 | sort -r | while read m; do umount -l "$m" 2>/dev/null; done
exit 0
