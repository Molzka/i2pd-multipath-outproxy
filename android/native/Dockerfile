
ARG NDK_VERSION=r27c
ARG API=24
ARG ABI=arm64-v8a

FROM ubuntu:24.04@sha256:008173c23f95b170204355c12626cb5a965d779a7e1283b09e9cffbb1bf33ca3 AS ndk
ARG NDK_VERSION
RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      build-essential cmake ninja-build git curl ca-certificates unzip perl python3 bzip2 patch file \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip \
    && unzip -q /tmp/ndk.zip -d /opt && rm /tmp/ndk.zip \
    && mv /opt/android-ndk-${NDK_VERSION} /opt/ndk
ENV NDK=/opt/ndk \
    ANDROID_NDK_ROOT=/opt/ndk \
    TC=/opt/ndk/toolchains/llvm/prebuilt/linux-x86_64
ENV PATH=$TC/bin:$PATH
RUN printf '%s\n' \
      'case "$ABI" in' \
      '  arm64-v8a) TRIPLE=aarch64-linux-android; OSSL=android-arm64; GOARCH=arm64 ;;' \
      '  x86_64)    TRIPLE=x86_64-linux-android;  OSSL=android-x86_64; GOARCH=amd64 ;;' \
      '  *) echo "unsupported ABI $ABI" >&2; exit 1 ;;' \
      'esac' \
      'CC="$TC/bin/${TRIPLE}${API}-clang"; CXX="${CC}++"' \
      'JOBS=$(awk "/MemAvailable/ {print int(\$2 / 6000000)}" /proc/meminfo); [ "$JOBS" -ge 1 ] || JOBS=1' \
      'export TRIPLE OSSL GOARCH CC CXX JOBS' \
      > /usr/local/bin/abi-env

FROM ndk AS openssl
ARG API
ARG ABI
ARG OPENSSL_VERSION=3.5.4
RUN . abi-env && mkdir -p /src && cd /src \
    && curl -fsSL https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz | tar xz \
    && cd openssl-${OPENSSL_VERSION} \
    && ./Configure $OSSL -D__ANDROID_API__=${API} no-shared no-tests no-docs no-apps \
         --prefix=/deps/openssl --libdir=lib \
    && make -j"$JOBS" build_libs && make install_dev \
    && rm -rf /src

FROM ndk AS boost
ARG API
ARG ABI
ARG BOOST_VERSION=1.84.0
RUN . abi-env && mkdir -p /src && cd /src \
    && V=$(echo ${BOOST_VERSION} | tr . _) \
    && curl -fsSL https://archives.boost.io/release/${BOOST_VERSION}/source/boost_${V}.tar.bz2 | tar xj \
    && cd boost_${V} \
    && ./bootstrap.sh --with-libraries=program_options,filesystem,atomic \
    && printf 'using clang : android : %s\n  : <archiver>%s <ranlib>%s\n    <compileflags>-fPIC <compileflags>-DNDEBUG <compileflags>-O2 ;\n' \
         "$CXX" "$TC/bin/llvm-ar" "$TC/bin/llvm-ranlib" > android.jam \
    && ./b2 -j"$JOBS" --user-config=android.jam --ignore-site-config \
         toolset=clang-android target-os=android link=static threading=multi variant=release \
         cxxstd=17 --layout=system --with-program_options --with-filesystem --with-atomic \
         --prefix=/deps/boost install \
    && rm -rf /src

FROM ndk AS i2pd
ARG API
ARG ABI
ARG I2PD_VERSION=3.0.0
ARG I2PD_COMMIT=137dde2f433b2142eec7a2307608d7d082be086f
ARG BUILD_JOBS=1
COPY --from=openssl /deps/openssl /deps/openssl
COPY --from=boost /deps/boost /deps/boost
COPY i2pd/my.patch /tmp/my.patch
RUN git clone --depth 1 --branch ${I2PD_VERSION} https://github.com/PurpleI2P/i2pd.git /src/i2pd \
    && test "$(git -C /src/i2pd rev-parse HEAD)" = "$I2PD_COMMIT" \
    && sed -i 's/\r$//' /tmp/my.patch \
    && cd /src/i2pd && git apply /tmp/my.patch \
    && git checkout -- libi2pd/NetDb.hpp libi2pd/NetDbRequests.cpp
RUN . abi-env && cmake -S /src/i2pd/build -B /build -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=${ABI} -DANDROID_PLATFORM=android-${API} -DANDROID_STL=c++_static \
      -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
      -DCMAKE_BUILD_TYPE=Release \
      -DWITH_STATIC=OFF -DWITH_UPNP=OFF -DWITH_BINARY=ON -DWITH_LIBRARY=OFF \
      -DCMAKE_CXX_FLAGS="-DANDROID -D__ANDROID__ -DANDROID_BINARY" \
      -DCMAKE_FIND_ROOT_PATH="/deps/boost;/deps/openssl" \
      -DBoost_NO_BOOST_CMAKE=ON -DBoost_USE_STATIC_LIBS=ON \
      -DBoost_INCLUDE_DIR=/deps/boost/include \
      -DBoost_PROGRAM_OPTIONS_LIBRARY_RELEASE=/deps/boost/lib/libboost_program_options.a \
      -DBoost_FILESYSTEM_LIBRARY_RELEASE=/deps/boost/lib/libboost_filesystem.a \
      -DBoost_ATOMIC_LIBRARY_RELEASE=/deps/boost/lib/libboost_atomic.a \
      -DOPENSSL_ROOT_DIR=/deps/openssl -DOPENSSL_USE_STATIC_LIBS=ON \
      -DOPENSSL_INCLUDE_DIR=/deps/openssl/include \
      -DOPENSSL_CRYPTO_LIBRARY=/deps/openssl/lib/libcrypto.a \
      -DOPENSSL_SSL_LIBRARY=/deps/openssl/lib/libssl.a \
    && cmake --build /build -j"$BUILD_JOBS" --target i2pd \
    && llvm-strip --strip-unneeded /build/i2pd \
    && llvm-readelf -d /build/i2pd | grep NEEDED \
    && mkdir -p /out && cp /build/i2pd /out/libi2pd.so \
    && printf 'version=%s\ncommit=%s\nabi=%s\npatch=%s\nbinary=%s\n' \
         "$I2PD_VERSION" "$I2PD_COMMIT" "$ABI" \
         "$(sha256sum /tmp/my.patch | cut -d ' ' -f 1)" \
         "$(sha256sum /out/libi2pd.so | cut -d ' ' -f 1)" > /out/i2pd-build.properties \
    && cp -r /src/i2pd/contrib/certificates /out/certificates \
    && rm -rf /build /src/i2pd

FROM scratch AS i2pd-export
ARG ABI
COPY --from=i2pd /out/libi2pd.so /${ABI}/libi2pd.so
COPY --from=i2pd /out/i2pd-build.properties /${ABI}/i2pd-build.properties
COPY --from=i2pd /out/certificates /certificates

FROM ndk AS aggtunnel
ARG API
ARG ABI
ARG AGG_VERSION=0.18.9
ENV RUSTUP_HOME=/opt/rustup CARGO_HOME=/opt/cargo
ENV PATH=/opt/cargo/bin:$PATH
RUN . abi-env && curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable \
      --target $TRIPLE
RUN . abi-env && if [ "$ABI" = x86_64 ]; then \
      apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends libclang-dev \
      && rm -rf /var/lib/apt/lists/* \
      && cargo install --locked bindgen-cli -j "$JOBS"; fi
COPY multipath/agg-tunnel.patch /tmp/agg-tunnel.patch
RUN mkdir -p /src && cd /src \
    && curl -fsSL https://crates.io/api/v1/crates/aggligator-util/${AGG_VERSION}/download | tar xz \
    && sed -i 's/\r$//' /tmp/agg-tunnel.patch \
    && cd aggligator-util-${AGG_VERSION} && patch -p1 < /tmp/agg-tunnel.patch
ENV CARGO_PROFILE_RELEASE_LTO=true \
    CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1 \
    CARGO_PROFILE_RELEASE_STRIP=true
RUN . abi-env && cd /src/aggligator-util-${AGG_VERSION} \
    && T=$(echo $TRIPLE | tr a-z- A-Z_) && t=$(echo $TRIPLE | tr - _) \
    && export CARGO_TARGET_${T}_LINKER=$CC CC_${t}=$CC CXX_${t}=$CXX AR_${t}=$TC/bin/llvm-ar \
              CARGO_TARGET_${T}_RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" \
              BINDGEN_EXTRA_CLANG_ARGS_${t}="--sysroot=$TC/sysroot --target=${TRIPLE}${API}" \
    && cargo build --release --locked --no-default-features --bin agg-tunnel --target $TRIPLE -j "$JOBS" \
    && mkdir -p /out && cp target/$TRIPLE/release/agg-tunnel /out/libaggtunnel.so \
    && file /out/libaggtunnel.so

FROM ndk AS i2pmux
ARG API
ARG ABI
ARG GO_VERSION=1.26.3
RUN curl -fsSL https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz | tar xz -C /opt
ENV PATH=/opt/go/bin:$PATH GOTOOLCHAIN=local
WORKDIR /src
COPY i2pmux/go.mod i2pmux/go.sum ./
COPY i2pmux/third_party/ ./third_party/
RUN go mod download
COPY i2pmux/ ./
ARG GO_TAGS=
RUN . abi-env && CGO_ENABLED=1 GOOS=android GOARCH=$GOARCH \
      go build -p "$JOBS" -trimpath -buildmode=pie -tags "$GO_TAGS" \
        -ldflags "-s -w -X main.version=android -extldflags=-Wl,-z,max-page-size=16384" \
        -o /out/libi2pmux.so . \
    && file /out/libi2pmux.so

FROM scratch
ARG ABI
COPY --from=i2pd /out/libi2pd.so /${ABI}/libi2pd.so
COPY --from=i2pd /out/i2pd-build.properties /${ABI}/i2pd-build.properties
COPY --from=aggtunnel /out/libaggtunnel.so /${ABI}/libaggtunnel.so
COPY --from=i2pmux /out/libi2pmux.so /${ABI}/libi2pmux.so
COPY --from=i2pd /out/certificates /certificates
