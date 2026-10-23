# QueuePilot Test: the shared, browser-reachable Android TV emulator.
# The base is the player's own toolchain image (tools/Dockerfile), which already
# carries the Google Android SDK, the emulator and the Android TV API 30 image.
ARG BASE_IMAGE=queuepilot-player-build:0.1
FROM ${BASE_IMAGE}

RUN apt-get update && apt-get install -y --no-install-recommends \
      tini xvfb x11vnc xdotool python3-aiohttp \
      libxcb-icccm4 libxcb-image0 libxcb-keysyms1 libxcb-randr0 libxcb-render-util0 libxcb-shape0 \
      libxcb-xfixes0 libxcb-xinerama0 libxcb-xkb1 libxkbcommon-x11-0 libfontconfig1 libdbus-1-3 \
    && rm -rf /var/lib/apt/lists/*

# noVNC (MPL-2.0, maintained by Cendio AB). Only the browser client library is used;
# the supervisor carries the WebSocket itself, so websockify is not needed.
ARG NOVNC_VERSION=1.7.0
ARG NOVNC_SHA256=b1003a11b6e6e8d8f7f5e5586daae7f8ca651d8aee0aa155ff9ac841c48f52c6
RUN curl -fsSLo /tmp/novnc.tar.gz "https://github.com/novnc/noVNC/archive/refs/tags/v${NOVNC_VERSION}.tar.gz" \
    && echo "${NOVNC_SHA256}  /tmp/novnc.tar.gz" | sha256sum -c - \
    && mkdir -p /opt/novnc \
    && tar -xzf /tmp/novnc.tar.gz -C /opt/novnc --strip-components=1 \
         "noVNC-${NOVNC_VERSION}/core" "noVNC-${NOVNC_VERSION}/vendor" \
         "noVNC-${NOVNC_VERSION}/LICENSE.txt" "noVNC-${NOVNC_VERSION}/AUTHORS" \
    && rm /tmp/novnc.tar.gz

COPY qptest.py supervisor.py queuepilot-test /opt/queuepilot-test/
COPY web /opt/queuepilot-test/web
RUN chmod 755 /opt/queuepilot-test/supervisor.py /opt/queuepilot-test/queuepilot-test \
    && ln -s /opt/queuepilot-test/queuepilot-test /usr/local/bin/queuepilot-test

ENV PYTHONUNBUFFERED=1
WORKDIR /opt/queuepilot-test
EXPOSE 8080
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["python3", "/opt/queuepilot-test/supervisor.py"]
