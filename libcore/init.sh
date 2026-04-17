#!/bin/bash

set -e

chmod -R 777 .build 2>/dev/null || true
rm -rf .build 2>/dev/null

if [ -z "$GOPATH" ]; then
    GOPATH=$(go env GOPATH)
fi
GOBIN_DIR=$(go env GOBIN)
if [ -z "$GOBIN_DIR" ]; then
    GOBIN_DIR="$GOPATH/bin"
fi

# Always (re)install gomobile-matsuri to avoid stale binaries from older caches.
rm -f "$GOBIN_DIR/gomobile-matsuri" "$GOBIN_DIR/gobind-matsuri"

git clone --depth=1 --branch master2 https://github.com/MatsuriDayo/gomobile.git
pushd gomobile

# 1) Skip self-updating gobind inside `gomobile init`; we install gobind explicitly below.
python3 - <<'PY'
from pathlib import Path
p = Path("cmd/gomobile/init.go")
s = p.read_text()
needle = "\t// Make sure gobind is up to date.\n\tif err := goInstall([]string{\"golang.org/x/mobile/cmd/gobind@latest\"}, nil); err != nil {\n\t\treturn err\n\t}\n"
replacement = "\t// gobind is installed explicitly by libcore/init.sh.\n"
if needle not in s:
    raise SystemExit("expected gobind install block not found in cmd/gomobile/init.go")
p.write_text(s.replace(needle, replacement))
PY

# 2) Avoid writing empty go.mod files for generated per-arch src dirs when module metadata cannot be resolved.
python3 - <<'PY'
from pathlib import Path
p = Path("cmd/gomobile/bind.go")
s = p.read_text()
needle = "return writeFile(filepath.Join(dir, \"go.mod\"), func(w io.Writer) error {\n\t\tf, err := getModuleVersions(targetPlatform, targetArch, \".\")\n\t\tif err != nil {\n\t\t\treturn err\n\t\t}\n\t\tif f == nil {\n\t\t\treturn nil\n\t\t}\n\t\tbs, err := f.Format()\n\t\tif err != nil {\n\t\t\treturn err\n\t\t}\n\t\tif _, err := w.Write(bs); err != nil {\n\t\t\treturn err\n\t\t}\n\t\treturn nil\n\t})"
replacement = "f, err := getModuleVersions(targetPlatform, targetArch, \".\")\n\tif err != nil {\n\t\treturn err\n\t}\n\tif f == nil {\n\t\treturn nil\n\t}\n\n\treturn writeFile(filepath.Join(dir, \"go.mod\"), func(w io.Writer) error {\n\t\tbs, err := f.Format()\n\t\tif err != nil {\n\t\t\treturn err\n\t\t}\n\t\tif _, err := w.Write(bs); err != nil {\n\t\t\treturn err\n\t\t}\n\t\treturn nil\n\t})"
if needle not in s:
    raise SystemExit("expected block not found in cmd/gomobile/bind.go")
p.write_text(s.replace(needle, replacement))
PY

pushd cmd/gomobile
go install -v
popd
pushd cmd/gobind
go install -v
popd
popd
rm -rf gomobile
mv "$GOBIN_DIR/gomobile" "$GOBIN_DIR/gomobile-matsuri"
mv "$GOBIN_DIR/gobind" "$GOBIN_DIR/gobind-matsuri"

GOBIND=gobind-matsuri gomobile-matsuri init
