#!/usr/bin/env bash
# Downloads aarch64 proot + libtalloc (+ loader) from the Termux package repo and installs them as
# app/src/main/jniLibs/arm64-v8a/lib{proot,talloc,proot-loader}.so
# Robust against filename/version changes: the current filenames are read from the Packages index.
set -euo pipefail

REPO="${TERMUX_REPO:-https://packages.termux.dev/apt/termux-main}"
ARCH="aarch64"
OUT="${OUT_DIR:-app/src/main/jniLibs/arm64-v8a}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

die() { echo "ERROR: $*" >&2; exit 1; }
log() { echo "[fetch-proot] $*"; }

retry() {
  local n=0 max=5
  until "$@"; do
    n=$((n + 1))
    [ "$n" -ge "$max" ] && return 1
    echo "[fetch-proot] retry $n/$max: $*" >&2
    sleep $((n * 3))
  done
}

command -v curl >/dev/null || die "curl is required"
command -v dpkg-deb >/dev/null || die "dpkg-deb is required (run on ubuntu/debian)"
mkdir -p "$OUT"

INDEX="$WORK/Packages"
log "Fetching package index from $REPO"
retry curl -fsSL --connect-timeout 20 --max-time 120 -o "$INDEX" \
  "$REPO/dists/stable/main/binary-$ARCH/Packages" \
  || die "Could not download the Packages index from $REPO (set TERMUX_REPO to a working mirror)."
[ -s "$INDEX" ] || die "Packages index is empty."

# Print a field of a package stanza: pkg_field <package> <Field>
pkg_field() {
  awk -v pkg="$1" -v field="$2" 'BEGIN{RS=""; FS="\n"}
    { name=""; val="";
      for (i=1;i<=NF;i++) {
        if ($i ~ /^Package: /) name=substr($i,10)
        else if (index($i, field ": ")==1) val=substr($i, length(field)+3)
      }
      if (name==pkg) { print val; exit } }' "$INDEX"
}

fetch_pkg() { # <package> <dest dir>
  local pkg="$1" dest="$2" file
  file="$(pkg_field "$pkg" Filename)"
  [ -n "$file" ] || die "Package '$pkg' not found in the Termux index for $ARCH."
  log "Downloading $pkg ($file)"
  retry curl -fsSL --connect-timeout 20 --max-time 600 -o "$WORK/$pkg.deb" "$REPO/$file" \
    || die "Download of $pkg failed."
  mkdir -p "$dest"
  dpkg-deb -x "$WORK/$pkg.deb" "$dest" || die "dpkg-deb failed for $pkg"
}

fetch_pkg proot "$WORK/proot"
fetch_pkg libtalloc "$WORK/talloc"

DEPS="$(pkg_field proot Depends || true)"
log "proot Depends: ${DEPS:-<none>}"
if echo "$DEPS" | tr ',' '\n' | sed 's/([^)]*)//g; s/ //g' | grep -vxE 'libtalloc|libandroid-support|' >/dev/null 2>&1; then
  echo "[fetch-proot] NOTE: proot depends on additional packages ($DEPS)." >&2
  echo "[fetch-proot]       If proot fails to start on the phone, add them here." >&2
fi

PROOT_BIN="$(find "$WORK/proot" -type f -name proot -path '*/bin/*' | head -n1)"
LOADER="$(find "$WORK/proot" -type f -path '*libexec/proot/loader' | head -n1)"
TALLOC="$(find "$WORK/talloc" -type f -name 'libtalloc.so*' | sort | head -n1)"

[ -n "$PROOT_BIN" ] || die "proot binary not found inside the proot .deb"
[ -n "$LOADER" ]    || die "proot loader (libexec/proot/loader) not found inside the proot .deb"
[ -n "$TALLOC" ]    || die "libtalloc.so not found inside the libtalloc .deb"

cp "$PROOT_BIN" "$OUT/libproot.so"
cp "$LOADER"    "$OUT/libproot-loader.so"
cp "$TALLOC"    "$OUT/libtalloc.so"
chmod 755 "$OUT"/lib*.so

# Verify: non-empty ELF files
for f in libproot.so libproot-loader.so libtalloc.so; do
  [ -s "$OUT/$f" ] || die "$f is empty"
  [ "$(head -c4 "$OUT/$f" | LC_ALL=C od -An -c | tr -d ' ')" = '177ELF' ] || die "$f is not an ELF file"
  log "OK  $OUT/$f ($(stat -c %s "$OUT/$f") bytes)"
done
log "Done."
