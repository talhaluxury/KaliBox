# KaliBox

Kali Linux (terminal + full XFCE desktop) on an Android phone (root not required) using proot. Builds entirely on GitHub Actions. License: GPLv3. For authorized testing and education only.

---

## English

### 1. Put the code on GitHub (from a phone)
1. Create a new GitHub repository (browser, "Desktop site" mode helps).
2. Upload all files from this project keeping the folder structure (Add file → Upload files; on mobile it is easiest to upload folder by folder, or use the GitHub app / a web editor such as github.dev).
3. Make sure `.github/workflows/build.yml` and `scripts/fetch-proot.sh` are in the repo.

### 2. Add signing secrets (optional, only for a signed release APK)
Repo → **Settings → Secrets and variables → Actions → New repository secret**. Add:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the workflow still builds the **debug APK** and just skips the release build.
(To make a keystore without a PC you can generate one in a GitHub Codespace or any online keytool tool, then base64-encode it.)

### 3. Download the APK
Repo → **Actions** → open the latest run → scroll to **Artifacts** → `KaliBox-apk` (zip containing the APK). For tags `v*` (e.g. `v1.0.0`) the APK is also attached to a **Release**.

### 4. Install
Unzip, open `KaliBox-debug.apk`, allow "Install unknown apps", install. Open KaliBox → accept the notice → **Install Kali** (needs 6 GB free) → Terminal or **Start Desktop** (needs ~4 GB more, 4 GB RAM recommended).
Also: disable battery optimization for KaliBox, and see the Android 12+ phantom-process tip on the home screen.

### Root mode (rooted phones)
Settings → **Root mode** → Enable. KaliBox then starts Kali with real root (`su` + `chroot`) so raw sockets, network interfaces, USB devices and `/sys` are available. Monitor mode and packet injection additionally need a chipset/driver or kernel that supports them (NetHunter-style kernel, or an OTG USB WiFi adapter with a kernel driver). Install the *wireless* tool bundle in Settings, then e.g. `airmon-ng start wlan0`. Turn root mode off before backup/reset.

### 5. Troubleshooting
- **proot errors / "proot failed to start"**: the app retries with `PROOT_NO_SECCOMP=1` automatically. If it still fails, check the Actions log of "Fetch proot" step (the Termux package layout may have changed; set `TERMUX_REPO` or edit `scripts/fetch-proot.sh`), then Settings → Share logs.
- **VNC black screen**: wait 30–60 s on first start; use Stop, then Start Desktop again; try 720p; make sure RAM is enough; check Android's phantom-process killer tip.
- **Low storage**: rootfs needs 6 GB, desktop 4 GB more. Settings → Clear cache; remove unused tools.
- **Checksum**: set `ROOTFS_SHA256` in `util/Constants.kt` to pin the hash. If left empty the app tries Kali's SHA256SUMS list and warns if it cannot verify.
- **Build fails on Termux terminal libraries (JitPack)**: change `termux` version in `gradle/libs.versions.toml` (e.g. to another tag from github.com/termux/termux-app/tags).

---

## Roman Urdu

### 1. Code GitHub par rakhein (phone se)
1. Browser mein naya GitHub repository banayein ("Desktop site" on karna behtar hai).
2. Is project ki saari files folder structure ke saath upload karein (Add file → Upload files). `.github/workflows/build.yml` aur `scripts/fetch-proot.sh` zaroor honi chahiyein.

### 2. Secrets (sirf signed release APK ke liye, zaroori nahi)
Repo → **Settings → Secrets and variables → Actions → New repository secret**:
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Agar secrets nahi hain to bhi **debug APK** ban jati hai, release build skip ho jata hai.

### 3. APK download karein
Repo → **Actions** → latest run kholen → neeche **Artifacts** → `KaliBox-apk`. Tag `v1.0.0` jaisa push karein to APK **Release** mein bhi lag jati hai.

### 4. Install
Zip extract karein, `KaliBox-debug.apk` install karein ("Unknown apps" allow karein). App kholen → notice accept karein → **Install Kali** (6 GB khali jagah chahiye) → Terminal ya **Start Desktop** (4 GB mazeed jagah, 4 GB RAM behtar).
Battery optimization KaliBox ke liye band karein.

### Root mode (rooted phone)
Settings → **Root mode** → Enable. Phir Kali asli root (`su` + `chroot`) se chalta hai: raw sockets, network interfaces, USB devices. Monitor mode/injection ke liye WiFi chip ya kernel ka support bhi chahiye (NetHunter kernel ya OTG USB WiFi adapter). Settings mein *wireless* tool bundle install karein.

### 5. Masail ka hal
- **proot error**: app khud `PROOT_NO_SECCOMP=1` se dobara koshish karti hai. Phir bhi na chale to Actions mein "Fetch proot" step ka log dekhein, phir Settings → Share logs.
- **VNC black screen**: pehli dafa 30–60 second intezar karein; Stop karke dobara Start Desktop karein; 720p chunein.
- **Storage kam**: Settings → Clear cache; faltu tools hata dein.

