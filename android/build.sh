#!/bin/sh
set -eu
ANDROID=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$ANDROID")
ASSETS="$ANDROID/app/src/main/assets"
OUT="$ANDROID/native/out"
ABIS=${ABIS:-"arm64-v8a x86_64"}
mkdir -p "$ASSETS"

for abi in $ABIS; do
    if [ "${1:-}" != "--skip-native" ] || [ ! -f "$OUT/$abi/libi2pd.so" ]; then
        echo "==> i2pd + agg-tunnel + i2pmux for $abi (Docker, NDK)"
        docker build -f "$ANDROID/native/Dockerfile" --build-arg "ABI=$abi" \
            --output "type=local,dest=$OUT" "$ROOT"
    fi
    mkdir -p "$ANDROID/app/src/main/jniLibs/$abi"
    cp "$OUT/$abi"/*.so "$ANDROID/app/src/main/jniLibs/$abi/"
done
rm -rf "$ASSETS/certificates" && cp -r "$OUT/certificates" "$ASSETS/certificates"
cp "$ROOT/client/multipath/multipath.cfg" "$ASSETS/multipath.cfg"

if [ ! -f "$ANDROID/keystore.properties" ]; then
    echo "==> generating release keystore"
    pw=$(head -c 18 /dev/urandom | od -An -tx1 | tr -d ' \n')
    keytool -genkeypair -keystore "$ANDROID/release.jks" -storetype PKCS12 -alias release \
        -keyalg EC -groupname secp256r1 -validity 10000 -storepass "$pw" -keypass "$pw" \
        -dname "CN=Multipath I2P" >/dev/null
    printf 'storeFile=release.jks\nstorePassword=%s\nkeyAlias=release\nkeyPassword=%s\n' "$pw" "$pw" \
        > "$ANDROID/keystore.properties"
fi
[ -f "$ANDROID/local.properties" ] || echo "sdk.dir=${ANDROID_HOME:?set ANDROID_HOME}" > "$ANDROID/local.properties"

echo "==> gradle assembleRelease"
(cd "$ANDROID" && sh ./gradlew --no-daemon assembleRelease)
mkdir -p "$ROOT/dist"
for abi in $ABIS; do
    name=multipath-i2p.apk; [ "$abi" = arm64-v8a ] || name="multipath-i2p-$abi.apk"
    cp "$ANDROID/app/build/outputs/apk/release/app-$abi-release.apk" "$ROOT/dist/$name"
    echo "==> $ROOT/dist/$name"
done
