# bootstrap-exporter

Database bootstrap exporter

Reproduce
If you have an RSK node 1.0.* sync'd from scratch, you can generate this file easily. We have created a simple Dockerfile for this, which you should run passing two volumes, one pointing to the mentioned database and another for where you want it to generate the file. For example:

$ cd ~/.rsk/mainnet
$ docker build -t exporter .
$ docker run -v database:/database -v output:/output exporter 10000
