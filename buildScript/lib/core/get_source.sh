#!/bin/bash
set -e

source "buildScript/init/env.sh"
ENV_NB4A=1
source "buildScript/lib/core/get_source_env.sh"
pushd ..

####

if [ ! -d "sing-box" ]; then
  git clone --no-checkout https://github.com/starifly/sing-box.git
fi
pushd sing-box
git checkout "$COMMIT_SING_BOX"
popd

####

if [ ! -d "libneko" ]; then
  git clone --no-checkout https://github.com/starifly/libneko.git
fi
pushd libneko
git checkout "$COMMIT_LIBNEKO"
popd

####

if [ ! -d "wireguard-go" ]; then
  git clone --no-checkout https://github.com/amnezia-vpn/amneziawg-go.git wireguard-go
fi
pushd wireguard-go
git checkout "$COMMIT_WIREGUARD_GO"
# Keep import path compatible with sing-box (expects github.com/sagernet/wireguard-go).
sed -i '1s|^module .*|module github.com/sagernet/wireguard-go|' go.mod
popd

####

popd
