#!/bin/bash

export EXPORTER_OPTS="$EXPORTER_OPTS -Ddatabase.dir=/database"
export EXPORTER_OPTS="$EXPORTER_OPTS -Dexporter.output=/output"

exporter-0.0.1/bin/exporter "$@"
strip-nondeterminism /output/bootstrap-data.zip
