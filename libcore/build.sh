#!/bin/bash

[ -f ./env_java.sh ] && source ./env_java.sh
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# Ensure local replacements used by libcore/go.mod are available even when
# init.sh was not executed in the current CI step.
ROOT_DIR="$(cd ../.. && pwd)"
SINGBOX_DIR="$ROOT_DIR/sing-box"
LIBNEKO_DIR="$ROOT_DIR/libneko"

if [ ! -d "$SINGBOX_DIR/.git" ]; then
  git clone --depth 1 --branch def https://github.com/MatsuriDayo/sing-box.git "$SINGBOX_DIR"
fi
if [ ! -d "$LIBNEKO_DIR/.git" ]; then
  git clone --depth 1 https://github.com/MatsuriDayo/libneko.git "$LIBNEKO_DIR"
fi

# gomobile may internally install tools with @latest.
# Let Go auto-upgrade toolchain instead of failing with GOTOOLCHAIN=local.
if [ "${GOTOOLCHAIN}" = "local" ]; then
  export GOTOOLCHAIN=auto
fi

if [ ! -x "$GOPATH/bin/gomobile-matsuri" ] || [ ! -x "$GOPATH/bin/gobind-matsuri" ]; then
  ./init.sh
fi

export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags='-s -w' -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"
