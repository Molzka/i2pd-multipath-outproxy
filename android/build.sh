#!/bin/sh
set -eu
ANDROID=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$ANDROID")
ASSETS="$ANDROID/app/src/main/assets"
OUT="$ANDROID/native/out"
ABIS=${ABIS:-"arm64-v8a x86_64"}
I2PD_VERSION=$(sed -n 's/^ARG I2PD_VERSION=//p' "$ANDROID/native/Dockerfile" | tr -d '\r')
I2PD_COMMIT=$(sed -n 's/^ARG I2PD_COMMIT=//p' "$ANDROID/native/Dockerfile" | tr -d '\r')
hash_stdin() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum | cut -d ' ' -f 1
    else shasum -a 256 | cut -d ' ' -f 1; fi
}
PATCH_HASH=$(sed 's/\r$//' "$ROOT/i2pd/my.patch" | hash_stdin)
mkdir -p "$ASSETS"

for abi in $ABIS; do
    case "$abi" in arm64-v8a|x86_64) ;; *) echo "Unsupported ABI: $abi" >&2; exit 1 ;; esac
    if [ "${1:-}" != "--skip-native" ] || [ ! -f "$OUT/$abi/libi2pd.so" ]; then
        echo "==> i2pd + agg-tunnel + i2pmux for $abi (Docker, NDK)"
        docker build -f "$ANDROID/native/Dockerfile" --build-arg "ABI=$abi" \
            --output "type=local,dest=$OUT" "$ROOT"
    fi
    manifest="$OUT/$abi/i2pd-build.properties"
    if [ ! -f "$manifest" ]; then
        echo "Missing i2pd build manifest for $abi. Rebuild without --skip-native." >&2
        exit 1
    fi
    if [ "$(sed -n 's/^version=//p' "$manifest")" != "$I2PD_VERSION" ] ||
       [ "$(sed -n 's/^commit=//p' "$manifest")" != "$I2PD_COMMIT" ] ||
       [ "$(sed -n 's/^abi=//p' "$manifest")" != "$abi" ] ||
       [ "$(sed -n 's/^patch=//p' "$manifest")" != "$PATCH_HASH" ] ||
       [ "$(sed -n 's/^binary=//p' "$manifest")" != "$(hash_stdin < "$OUT/$abi/libi2pd.so")" ]; then
        echo "Stale or modified i2pd binary for $abi. Rebuild without --skip-native." >&2
        exit 1
    fi
    for library in libi2pd.so libaggtunnel.so libi2pmux.so; do
        if [ ! -f "$OUT/$abi/$library" ]; then
            echo "Missing $library for $abi. Rebuild without --skip-native." >&2
            exit 1
        fi
    done
    mkdir -p "$ANDROID/app/src/main/jniLibs/$abi"
    cp "$OUT/$abi"/*.so "$ANDROID/app/src/main/jniLibs/$abi/"
    cp "$manifest" "$ANDROID/app/src/main/jniLibs/$abi/i2pd-build.properties"
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
(cd "$ANDROID" && sh ./gradlew --no-daemon "-PnativeAbis=$(printf '%s' "$ABIS" | tr ' ' ',')" assembleRelease)
mkdir -p "$ROOT/dist"
for abi in $ABIS; do
    name=multipath-i2p.apk; [ "$abi" = arm64-v8a ] || name="multipath-i2p-$abi.apk"
    cp "$ANDROID/app/build/outputs/apk/release/app-$abi-release.apk" "$ROOT/dist/$name"
    echo "==> $ROOT/dist/$name"
done
