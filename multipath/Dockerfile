
FROM rust:1-alpine AS agg
RUN apk add --no-cache musl-dev build-base cmake perl curl patch
ARG AGG_VERSION=0.18.9
ARG AGG_CORE_VERSION=0.9.9
ARG AGG_CORE_SHA256=3084121a172a2b8b90d96e2fb619f3ac60aa87d2536ac7ebc39da739a1a07691
COPY multipath/agg-tunnel.patch /tmp/agg-tunnel.patch
COPY multipath/aggligator-recovery.patch /tmp/aggligator-recovery.patch
RUN mkdir -p /src && cd /src \
    && curl -fsSL -o /tmp/aggligator.crate https://crates.io/api/v1/crates/aggligator/${AGG_CORE_VERSION}/download \
    && echo "$AGG_CORE_SHA256  /tmp/aggligator.crate" | sha256sum -c - \
    && tar xzf /tmp/aggligator.crate && rm /tmp/aggligator.crate \
    && sed -i 's/\r$//' /tmp/aggligator-recovery.patch \
    && patch -d aggligator-${AGG_CORE_VERSION} -p1 < /tmp/aggligator-recovery.patch \
    && curl -fsSL https://crates.io/api/v1/crates/aggligator-util/${AGG_VERSION}/download | tar xz \
    && sed -i 's/\r$//' /tmp/agg-tunnel.patch \
    && cd aggligator-util-${AGG_VERSION} && patch -p1 < /tmp/agg-tunnel.patch \
    && mkdir -p .cargo && printf 'paths = ["../aggligator-%s"]\n' "$AGG_CORE_VERSION" > .cargo/config.toml
ENV CARGO_PROFILE_RELEASE_LTO=true \
    CARGO_PROFILE_RELEASE_CODEGEN_UNITS=1 \
    CARGO_PROFILE_RELEASE_STRIP=true
RUN cd /src/aggligator-util-${AGG_VERSION} \
    && J=$(awk '/MemAvailable/ {print int($2 / 700000)}' /proc/meminfo) \
    && if [ "$J" -lt 1 ]; then J=1; fi \
    && if [ "$J" -gt "$(nproc)" ]; then J=$(nproc); fi \
    && cargo build --release --locked --no-default-features --bin agg-tunnel -j "$J" \
    && cp target/release/agg-tunnel /agg-tunnel

FROM golang:1.26-alpine AS mux
WORKDIR /src
COPY i2pmux/go.mod i2pmux/go.sum ./
COPY i2pmux/third_party/ ./third_party/
RUN go mod download
COPY i2pmux/ ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /i2pmux .

FROM alpine:3.22
COPY --from=agg /agg-tunnel /usr/local/bin/agg-tunnel
COPY --from=mux /i2pmux /usr/local/bin/i2pmux
USER 65534:65534
