# bootstrap-exporter

Database bootstrap exporter

Reproduce
If you have an RSK node 1.0.* synchronized from scratch, you can generate this file.We have created a simple Dockerfile for this. 

You can run it passing two volumes, one pointing to the database and the other one for the directory where it will output the file. 

For example:

```
$ git clone git@github.com:rsksmart/bootstrap-exporter.git
$ cd bootstrap-exporter
$ docker build -t exporter .

$ # stop rsk node if it's still running

$ docker run -v /path/to/database:/database -v output:/output exporter 10000
```
