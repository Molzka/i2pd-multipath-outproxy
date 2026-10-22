FROM alpine:3.22 AS builder

RUN apk add --no-cache build-base cmake git boost-dev openssl-dev zlib-dev miniupnpc-dev

ARG I2PD_VERSION=3.0.0
ARG I2PD_COMMIT=137dde2f433b2142eec7a2307608d7d082be086f
RUN git clone --depth 1 --branch ${I2PD_VERSION} https://github.com/PurpleI2P/i2pd.git /src/i2pd \
    && test "$(git -C /src/i2pd rev-parse HEAD)" = "$I2PD_COMMIT"

COPY my.patch /tmp/my.patch
RUN sed -i 's/\r$//' /tmp/my.patch && cd /src/i2pd && git apply /tmp/my.patch

RUN cmake -S /src/i2pd/build -B /build -DCMAKE_BUILD_TYPE=Release -DWITH_HARDENING=ON -DWITH_UPNP=ON \
    && J=$(awk '/MemAvailable/ {print int($2 / 2000000)}' /proc/meminfo) \
    && if [ "$J" -lt 1 ]; then J=1; fi \
    && if [ "$J" -gt "$(nproc)" ]; then J=$(nproc); fi \
    && echo "jobs: $J" \
    && cmake --build /build -j"$J" --target i2pd \
    && strip /build/i2pd

FROM alpine:3.22

RUN apk add --no-cache boost-program_options boost-filesystem boost-atomic boost-json boost-url libstdc++ openssl zlib miniupnpc \
    && addgroup -g 1000 i2pd && adduser -u 1000 -G i2pd -s /sbin/nologin -h /home/i2pd -D i2pd

COPY --from=builder /build/i2pd /usr/local/bin/i2pd
COPY --from=builder /src/i2pd/contrib/certificates /usr/share/i2pd/certificates

USER i2pd
WORKDIR /home/i2pd
VOLUME /home/i2pd/data
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/i2pd", "--datadir=/home/i2pd/data", "--certsdir=/usr/share/i2pd/certificates"]
