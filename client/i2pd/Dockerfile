FROM alpine:3 AS builder

RUN apk --no-cache --virtual build-dependendencies add \
  cmake \
  make \
  gcc \
  g++ \
  binutils \
  libtool \
  libev-dev \
  check-dev \
  zlib-dev \
  boost-dev \
  build-base \
  openssl-dev \
  openssl \
  git \
  sed \
  autoconf \
  automake \
  miniupnpc \
  miniupnpc-dev

# install deps && build i2p binary
RUN mkdir -p /i2pd/data/addressbook \
  && mkdir /i2pd/tunnels.null \
  && mkdir /i2pd/tunnels.source.conf.d \
  && mkdir /i2pd/tunnels.conf.d \
  && mkdir /i2pd/bin

RUN cd /tmp \
	&& git clone --depth 1 https://github.com/PurpleI2P/i2pd.git \
	&& cd i2pd \
	&& git reset --hard c63f87eb22194cbf68bfd117f1e43e1cfc44c728 \
	&& git clean -df

COPY ./my.patch /tmp/i2pd/my.patch
RUN cd /tmp/i2pd && git apply my.patch && echo patched

RUN cd /tmp/i2pd/build \
  && DEBUG=no cmake -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS="-Wl,-z,reiro -Wl,-z,now -D_FORTIFY_SOURCE=2 -Ofast -march=native -fstack-protector-strong -fstrict-flex-arrays" -DWITH_AESNI=ON -DWITH_UPNP=ON -DWITH_HARDENING=ON . \
  && DEBUG=no make DEBUG=no -j $(nproc) \
  && strip i2pd

FROM alpine:3

RUN apk --no-cache add \
  boost-system \
  boost-filesystem \
  boost-program_options \
  openssl \
  musl-utils \
  libstdc++ \
  sed \
  miniupnpc

RUN mkdir -p /i2pd/data/addressbook \
  && mkdir /i2pd/tunnels.null \
  && mkdir /i2pd/tunnels.source.conf.d \
  && mkdir /i2pd/tunnels.conf.d \
  && mkdir /i2pd/bin

COPY --from=builder /tmp/i2pd/build/i2pd /i2pd/bin/i2pd
COPY --from=builder /tmp/i2pd/contrib/certificates /i2pd_certificates

RUN addgroup -g 1000 i2pd \
  && adduser -u 1000 -G i2pd -s /bin/sh -h "/i2pd" -D i2pd \
  && chown -R i2pd:i2pd /i2pd \
  && chmod 0700 /i2pd/bin/i2pd \
  && mkdir -p /home/i2pd/

USER i2pd

WORKDIR "/home/i2pd/"

CMD ["/i2pd/bin/i2pd", "--datadir=/home/i2pd/data"]
