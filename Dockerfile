FROM gradle:jdk17@sha256:cd50c1a698a2d3ef4a3c4bdd1d6076de4027a19cb8254cc2df2305c18b7776dd

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
