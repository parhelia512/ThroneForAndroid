#!/bin/bash
set -e

# Build app/libs/ThroneCore.aar from throneproj/Throne at the ref pinned in nb4a.properties
# (gomobile + gobind on PATH, JDK 17, ANDROID_HOME/ANDROID_NDK_HOME set).
THRONE_CORE_REF=$(sed -n 's/^THRONE_CORE_REF=//p' nb4a.properties | tr -d '\r[:space:]')
test -n "$THRONE_CORE_REF"
THRONE_DIR="$(cd .. && pwd)/Throne"
rm -rf "$THRONE_DIR"
git init -q "$THRONE_DIR"
git -C "$THRONE_DIR" remote add origin https://github.com/throneproj/Throne
git -C "$THRONE_DIR" fetch -q --depth 1 origin "$THRONE_CORE_REF"
git -C "$THRONE_DIR" checkout -q --detach FETCH_HEAD

go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.13
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.13
export PATH="$(go env GOPATH)/bin:$PATH"

DEST="$PWD/app/libs"
(cd "$THRONE_DIR" && DEST="$DEST" bash script/build_android.sh)
