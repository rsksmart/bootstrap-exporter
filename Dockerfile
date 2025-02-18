FROM gradle:jdk17

RUN apt update
RUN apt install -y --no-install-recommends strip-nondeterminism file
WORKDIR /workspace
COPY ./exporter/ ./
COPY start.sh .

RUN gradle distZip --no-daemon
RUN unzip build/distributions/exporter-0.0.1.zip
RUN chmod +x exporter-0.0.1/bin/exporter

RUN strip-nondeterminism build/distributions/exporter-0.0.1.zip

ENTRYPOINT ["sh", "start.sh"]
