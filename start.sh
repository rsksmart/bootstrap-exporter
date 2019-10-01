#!/bin/bash

exporter-0.0.1/bin/exporter $1
strip-nondeterminism /output/bootstrap-data.zip
