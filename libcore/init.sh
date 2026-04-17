#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi

# Ensure local replacements declared in libcore/go.mod exist.
ROOT_DIR="$(cd ../.. && pwd)"
SINGBOX_DIR="$ROOT_DIR/sing-box"
LIBNEKO_DIR="$ROOT_DIR/libneko"

if [ ! -d "$SINGBOX_DIR/.git" ]; then
    git clone --depth 1 --branch def https://github.com/MatsuriDayo/sing-box.git "$SINGBOX_DIR"
fi
if [ ! -d "$LIBNEKO_DIR/.git" ]; then
    git clone --depth 1 https://github.com/MatsuriDayo/libneko.git "$LIBNEKO_DIR"
fi

# gomobile upstream may require a newer Go toolchain for @latest installs.
# Allow toolchain auto-switch in CI when GOTOOLCHAIN is pinned to local.
if [ "${GOTOOLCHAIN}" = "local" ]; then
    export GOTOOLCHAIN=auto
fi

# Install gomobile
if [ ! -f "$GOPATH/bin/gomobile-matsuri" ]; then
    git clone https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
	git checkout origin/master2
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    mv "$GOPATH/bin/gomobile" "$GOPATH/bin/gomobile-matsuri"
    mv "$GOPATH/bin/gobind" "$GOPATH/bin/gobind-matsuri"
fi

GOBIND=gobind-matsuri gomobile-matsuri init
