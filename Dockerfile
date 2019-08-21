FROM gradle:jdk8

WORKDIR /workspace
COPY ./exporter/ ./

RUN gradle distZip --no-daemon
RUN unzip build/distributions/exporter-0.0.1.zip
RUN chmod +x exporter-0.0.1/bin/exporter

ENTRYPOINT ["sh", "exporter-0.0.1/bin/exporter"]
