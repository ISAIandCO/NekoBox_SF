#!/bin/bash

chmod -R 777 .build 2>/dev/null
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi
BIN_DIR=$(go env GOBIN)
if [ -z "$BIN_DIR" ]; then
    BIN_DIR="$GOPATH/bin"
fi
TARGET_BIN="$GOPATH/bin"
mkdir -p "$TARGET_BIN"

# Install gomobile
if [ ! -f "$TARGET_BIN/gomobile-matsuri" ] || [ ! -f "$TARGET_BIN/gobind-matsuri" ]; then
    rm -rf gomobile
    git clone https://github.com/MatsuriDayo/gomobile.git
    pushd gomobile
	git checkout origin/master2
    # Keep gobind bootstrap compatible with Go 1.24.x in CI environments.
    sed -i 's|golang.org/x/mobile/cmd/gobind@latest|golang.org/x/mobile/cmd/gobind@v0.0.0-20231108233038-35478a0c49da|g' cmd/gomobile/init.go
    pushd cmd
    pushd gomobile
    go install -v
    popd
    pushd gobind
    go install -v
    popd
    popd
    rm -rf gomobile
    cp -f "$BIN_DIR/gomobile" "$TARGET_BIN/gomobile-matsuri"
    cp -f "$BIN_DIR/gobind" "$TARGET_BIN/gobind-matsuri"
fi

export PATH="$TARGET_BIN:$PATH"
GOBIND=gobind-matsuri gomobile-matsuri init
