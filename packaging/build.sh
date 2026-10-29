#!/usr/bin/env bash
# Builds the installers an end user runs: a .deb for Ubuntu/Debian and a setup .exe for Windows.
# Both carry Electron and their own Java runtime, so nothing has to be installed first.
#
#   packaging/build.sh [linux|windows|all]        (default: all)
#
# Needs on this machine (Linux): a JDK 21 (JAVA21_HOME, or ~/.sdkman, or it is downloaded), Maven, Node,
# dpkg-deb (the .deb), and either `makensis` or apt (NSIS is fetched without root).
# Set JB_GOOGLE_CLIENT_ID / JB_GOOGLE_CLIENT_SECRET to build "Sign in with Google" into the installers.
set -euo pipefail

TARGET="${1:-all}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$ROOT/packaging"
CACHE="$PKG/.cache"
WORK="$PKG/.work"
DIST="$PKG/dist"
mkdir -p "$CACHE" "$WORK" "$DIST"

# what the app needs from Java: all of Java SE (JDBC drivers load all sorts of things) plus these
MODULES="java.se,jdk.unsupported,jdk.crypto.ec,jdk.zipfs,jdk.management,jdk.net,jdk.naming.dns,jdk.localedata,jdk.charsets,jdk.security.auth,jdk.security.jgss,jdk.httpserver"

log() { printf '\n\033[1;33m== %s\033[0m\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

VERSION="$(python3 - "$ROOT/pom.xml" <<'PY'
import sys, xml.etree.ElementTree as ET
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
print(ET.parse(sys.argv[1]).getroot().find("m:version", ns).text)
PY
)"
APP_VERSION="${VERSION%-SNAPSHOT}"
[[ "$APP_VERSION" =~ ^[0-9]+\.[0-9]+(\.[0-9]+)?$ ]] || die "version $VERSION cannot be an installer version (use x.y.z)"

# ---- JDKs -----------------------------------------------------------------------------------------------------------
adoptium() { # os -> url of the latest Temurin 21 JDK archive
  echo "https://api.adoptium.net/v3/binary/latest/21/ga/$1/x64/jdk/hotspot/normal/eclipse"
}

linux_jdk() {
  if [[ -n "${JAVA21_HOME:-}" && -x "$JAVA21_HOME/bin/jpackage" ]]; then echo "$JAVA21_HOME"; return; fi
  for d in "$HOME"/.sdkman/candidates/java/21*; do
    [[ -x "$d/bin/jpackage" && -d "$d/jmods" ]] && { echo "$d"; return; }
  done
  if [[ ! -d "$CACHE/jdk-linux" ]]; then
    log "Downloading Temurin 21 for Linux"
    curl -fSL "$(adoptium linux)" -o "$CACHE/jdk-linux.tar.gz"
    mkdir -p "$CACHE/jdk-linux" && tar -xzf "$CACHE/jdk-linux.tar.gz" -C "$CACHE/jdk-linux" --strip-components=1
  fi
  echo "$CACHE/jdk-linux"
}

windows_jmods() {
  if [[ ! -d "$CACHE/jdk-windows" ]]; then
    if [[ ! -f "$CACHE/jdk-windows.zip" ]]; then
      log "Downloading Temurin 21 for Windows (only its modules are used)"
      curl -fSL "$(adoptium windows)" -o "$CACHE/jdk-windows.zip"
    fi
    mkdir -p "$CACHE/jdk-windows.tmp" && unzip -q -o "$CACHE/jdk-windows.zip" -d "$CACHE/jdk-windows.tmp"
    mv "$CACHE/jdk-windows.tmp"/jdk-* "$CACHE/jdk-windows" && rmdir "$CACHE/jdk-windows.tmp"
  fi
  echo "$CACHE/jdk-windows/jmods"
}

JDK="$(linux_jdk)"
"$JDK/bin/java" -version 2>&1 | grep -q 'version "21' || die "JDK at $JDK is not Java 21 (set JAVA21_HOME)"

# ---- the application (one fat jar with the page inside) -------------------------------------------------------------------
log "Building the application $VERSION"
( cd "$ROOT" && JAVA_HOME="$JDK" mvn -B -ntp -q ${OFFLINE:+-o} package -DskipTests )
JAR="$ROOT/target/justinbeaver-$VERSION.jar"
[[ -f "$JAR" ]] || die "$JAR was not built"
rm -rf "$WORK/app" && mkdir -p "$WORK/app" && cp "$JAR" "$WORK/app/justinbeaver.jar"

log "Installing the desktop shell"
( cd "$ROOT/desktop" && npm ci --no-audit --no-fund )
cp "$PKG/assets/justinbeaver-256.png" "$ROOT/desktop/icon.png"

prepare_backend() {
  rm -rf "$WORK/backend"
  mkdir -p "$WORK/backend"
  cp "$WORK/app/justinbeaver.jar" "$WORK/backend/"
  # A Google Cloud download can be imported once; environment variables override this local file.
  [[ ! -f "$PKG/.google-client.json" ]] || cp "$PKG/.google-client.json" "$WORK/backend/google-client.json"
  node - "$WORK/backend/google-client.json" <<'JS'
const fs = require('node:fs');
const file = process.argv[2];
const config = fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : {};
if (process.env.JB_GOOGLE_CLIENT_ID) {
  config.clientId = process.env.JB_GOOGLE_CLIENT_ID;
  config.clientSecret = process.env.JB_GOOGLE_CLIENT_SECRET || '';
}
if (config.clientId) {
  // Desktop OAuth client metadata ships with the app. It must remain readable after root-owned .deb installation.
  fs.writeFileSync(file, JSON.stringify(config), { mode: 0o644 });
  fs.chmodSync(file, 0o644);
}
JS
}

# ---- Ubuntu / Debian .deb ------------------------------------------------------------------------------------------------
build_linux() {
  log "Linux runtime"
  prepare_backend
  "$JDK/bin/jlink" --module-path "$JDK/jmods" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
    --compress zip-6 --output "$WORK/backend/runtime"

  log "Packaging Electron for Linux"
  node "$ROOT/desktop/package.cjs" linux "$APP_VERSION"

  log "Building the .deb"
  rm -f "$DIST"/justinbeaver_*.deb
  local deb="$WORK/deb"
  rm -rf "$deb"
  mkdir -p "$deb/DEBIAN" "$deb/opt/justinbeaver" "$deb/usr/share/applications" "$deb/usr/share/icons/hicolor/256x256/apps"
  cp -a "$WORK/electron/justinbeaver-linux-x64/." "$deb/opt/justinbeaver/"
  cp "$PKG/assets/justinbeaver-256.png" "$deb/usr/share/icons/hicolor/256x256/apps/justinbeaver.png"
  cat > "$deb/DEBIAN/control" <<EOF
Package: justinbeaver
Version: $APP_VERSION
Architecture: amd64
Maintainer: JustinBeaver <justinbeaver@localhost>
Section: database
Priority: optional
Depends: libgtk-3-0, libnss3, libxss1, libasound2t64 | libasound2, libgbm1, libx11-6, libxcomposite1, libxdamage1, libxfixes3, libxrandr2, libatk1.0-0, libatk-bridge2.0-0, libcups2, libdrm2
Description: Desktop database client with JDBC support
 Browse, query and edit databases in a dedicated application window.
EOF
  cat > "$deb/usr/share/applications/justinbeaver.desktop" <<'EOF'
[Desktop Entry]
Name=JustinBeaver
Comment=Database client
Exec=/opt/justinbeaver/justinbeaver %U
Icon=justinbeaver
Terminal=false
Type=Application
Categories=Development;Database;
StartupWMClass=justinbeaver
EOF
  # The staged tree inherits the build user's umask and the packager's 0700 app dir; installed root-owned, that would
  # hide the app from every other user (and from the launcher menu). Normalize before packing.
  find "$deb" -type d -exec chmod 755 {} +
  find "$deb" -type f ! -perm -u+x -exec chmod 644 {} +
  chmod 4755 "$deb/opt/justinbeaver/chrome-sandbox"
  dpkg-deb --root-owner-group --build "$deb" "$DIST/justinbeaver_${APP_VERSION}_amd64.deb"
  ls -1 "$DIST"/justinbeaver_*.deb
}

# ---- Windows setup .exe -------------------------------------------------------------------------------------------------------
makensis_cmd() {
  if command -v makensis >/dev/null 2>&1; then echo "makensis"; return; fi
  local dir="$CACHE/nsis"
  if [[ ! -x "$dir/usr/bin/makensis" ]]; then
    log "Fetching NSIS (no root needed)"
    ( cd "$CACHE" && apt-get download nsis nsis-common >/dev/null && for d in nsis_*.deb nsis-common_*.deb; do dpkg -x "$d" "$dir"; done )
  fi
  echo "env NSISDIR=$dir/usr/share/nsis $dir/usr/bin/makensis"
}

build_windows() {
  log "Windows runtime (built from the Windows JDK modules)"
  local jmods; jmods="$(windows_jmods)"
  prepare_backend
  "$JDK/bin/jlink" --module-path "$jmods" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages \
    --compress zip-6 --output "$WORK/backend/runtime"
  [[ -f "$WORK/backend/runtime/bin/java.exe" ]] || die "the Windows runtime has no java.exe"
  log "Packaging Electron for Windows"
  node "$ROOT/desktop/package.cjs" win32 "$APP_VERSION"

  log "Building the setup .exe"
  local out="$DIST/JustinBeaver-Setup-$APP_VERSION.exe"
  rm -f "$DIST"/JustinBeaver-Setup-*.exe
  $(makensis_cmd) -V2 "-DVERSION=$APP_VERSION" "-DSRC=$WORK/electron/justinbeaver-win32-x64" "-DASSETS=$PKG/assets" "-DOUTFILE=$out" \
    "$PKG/windows/installer.nsi"
  ls -1 "$out"
}

case "$TARGET" in
  linux) build_linux ;;
  windows) build_windows ;;
  all) build_linux; build_windows ;;
  *) die "usage: build.sh [linux|windows|all]" ;;
esac

log "Done"
( cd "$DIST" && sha256sum ./*.deb ./*.exe > SHA256SUMS )
ls -lh "$DIST"
