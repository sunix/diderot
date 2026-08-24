#!/bin/sh
# diderot installer.
#
#   curl -fsSL https://raw.githubusercontent.com/sunix/diderot/main/install.sh | sh
#
# Downloads the native binary matching this machine from GitHub Releases, verifies its
# SHA-256 checksum, and installs it. Override behaviour with:
#
#   DIDEROT_VERSION=v0.1.0   install a specific tag instead of the latest release
#   DIDEROT_INSTALL_DIR=...  install somewhere other than ~/.local/bin
#
# POSIX sh on purpose: this has to run before anything is installed.
set -eu

REPO="sunix/diderot"
INSTALL_DIR="${DIDEROT_INSTALL_DIR:-$HOME/.local/bin}"

die() {
    echo "install.sh: $1" >&2
    exit 1
}

need() {
    command -v "$1" >/dev/null 2>&1 || die "$1 is required but not installed."
}

need uname
need mkdir
need mv
need chmod

# One downloader is enough; prefer curl, fall back to wget. Both stay quiet about their
# own failures: every call site below reports what went wrong in more useful terms.
if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsL "$1" -o "$2" 2>/dev/null; }
    fetch_stdout() { curl -fsL "$1" 2>/dev/null; }
elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -qO "$2" "$1" 2>/dev/null; }
    fetch_stdout() { wget -qO- "$1" 2>/dev/null; }
else
    die "neither curl nor wget is available."
fi

# Map uname output onto the asset names the release workflow publishes.
os="$(uname -s)"
case "$os" in
    Linux)  asset_os="linux" ;;
    Darwin) asset_os="darwin" ;;
    *) die "unsupported operating system '$os'. Native binaries are published for Linux, macOS and Windows; on other systems use the uber-jar: java -jar diderot-<version>.jar" ;;
esac

arch="$(uname -m)"
case "$arch" in
    x86_64|amd64)  asset_arch="x86_64" ;;
    aarch64|arm64) asset_arch="aarch64" ;;
    *) die "unsupported architecture '$arch'. Published binaries cover x86_64 and aarch64; on other architectures use the uber-jar: java -jar diderot-<version>.jar" ;;
esac

# Resolve the version to install. The GitHub API redirects /releases/latest to the tag.
version="${DIDEROT_VERSION:-}"
if [ -z "$version" ]; then
    need sed
    version="$(fetch_stdout "https://api.github.com/repos/$REPO/releases/latest" \
        | sed -n 's/.*"tag_name" *: *"\([^"]*\)".*/\1/p' | head -n 1)"
    [ -n "$version" ] || die "could not determine the latest release of $REPO — either it has published none yet, or api.github.com is unreachable from here. Set DIDEROT_VERSION=<tag> to pick one explicitly."
fi

binary="diderot-${version}-${asset_os}-${asset_arch}"
base_url="https://github.com/$REPO/releases/download/$version"

tmp="$(mktemp -d)"
# shellcheck disable=SC2064
trap "rm -rf '$tmp'" EXIT INT TERM

echo "Installing diderot $version ($asset_os-$asset_arch) into $INSTALL_DIR"

fetch "$base_url/$binary" "$tmp/diderot" \
    || die "could not download '$binary' from release $version — either that release doesn't publish a binary for this platform, or github.com is unreachable. See https://github.com/$REPO/releases/tag/$version"
fetch "$base_url/$binary.sha256" "$tmp/diderot.sha256" \
    || die "downloaded the binary but its checksum file is missing from release $version; refusing to install unverified."

# The .sha256 file names the asset, not our temp path, so compare hashes directly.
expected="$(cut -d' ' -f1 < "$tmp/diderot.sha256")"
if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$tmp/diderot" | cut -d' ' -f1)"
elif command -v shasum >/dev/null 2>&1; then
    actual="$(shasum -a 256 "$tmp/diderot" | cut -d' ' -f1)"
else
    die "no sha256sum or shasum available to verify the download; refusing to install unverified."
fi
[ "$expected" = "$actual" ] || die "checksum mismatch for $binary (expected $expected, got $actual). Not installing."

mkdir -p "$INSTALL_DIR"
chmod +x "$tmp/diderot"
mv "$tmp/diderot" "$INSTALL_DIR/diderot"

echo "Installed $INSTALL_DIR/diderot"
case ":$PATH:" in
    *":$INSTALL_DIR:"*) exec "$INSTALL_DIR/diderot" --version ;;
    *) echo "Note: $INSTALL_DIR is not on your PATH. Add it, or run $INSTALL_DIR/diderot directly." ;;
esac
