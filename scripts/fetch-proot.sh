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

# --- runtime dependencies of proot (e.g. libandroid-shmem.so) -------------------------------
# proot's DT_NEEDED entries must be resolvable from the app's native lib dir, so every "lib*"
# dependency listed in the Termux index is downloaded too (recursively) and shipped as lib*.so.
DEPS="$(pkg_field proot Depends || true)"
log "proot Depends: ${DEPS:-<none>}"
QUEUE=()
SEEN=" proot libtalloc "
parse_deps() { # "a (>= 1), b | c" -> names
  echo "$1" | tr ',' '\n' | sed 's/|.*//; s/(.*//; s/ //g' | grep -v '^$' || true
}
while IFS= read -r d; do [ -n "$d" ] && QUEUE+=("$d"); done < <(parse_deps "$DEPS")
# Safety net: current Termux proot needs libandroid-shmem even if the Depends parse changed.
QUEUE+=("libandroid-shmem")
i=0
while [ "$i" -lt "${#QUEUE[@]}" ]; do
  d="${QUEUE[$i]}"; i=$((i + 1))
  case "$SEEN" in *" $d "*) continue;; esac
  SEEN="$SEEN$d "
  case "$d" in
    lib*) ;;
    *) log "Skipping non-library dependency: $d"; continue;;
  esac
  if [ -z "$(pkg_field "$d" Filename)" ]; then
    log "WARNING: dependency $d not found in index (skipping)"
    continue
  fi
  fetch_pkg "$d" "$WORK/dep_$d"
  found=0
  while IFS= read -r so; do
    [ -n "$so" ] || continue
    cp -L "$so" "$OUT/$(basename "$so")"
    log "bundled $(basename "$so") (from $d)"
    found=1
  done < <(find "$WORK/dep_$d" \( -type f -o -type l \) -name '*.so' -path '*/lib/*')
  [ "$found" -eq 1 ] || log "WARNING: no lib*.so found in $d"
  while IFS= read -r dd; do [ -n "$dd" ] && QUEUE+=("$dd"); done < <(parse_deps "$(pkg_field "$d" Depends || true)")
done
chmod 755 "$OUT"/lib*.so

# Verify: non-empty ELF files
for f in $(cd "$OUT" && ls lib*.so); do
  [ -s "$OUT/$f" ] || die "$f is empty"
  [ "$(head -c4 "$OUT/$f" | LC_ALL=C od -An -c | tr -d ' ')" = '177ELF' ] || die "$f is not an ELF file"
  log "OK  $OUT/$f ($(stat -c %s "$OUT/$f") bytes)"
done
log "Done."
