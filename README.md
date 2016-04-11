# XNAT Container Service [![Circle CI](https://circleci.com/gh/NrgXnat/container-service.svg?style=svg)](https://circleci.com/gh/NrgXnat/container-service) [![codecov.io](https://codecov.io/github/NrgXnat/container-service/coverage.svg?branch=master)](https://codecov.io/github/NrgXnat/container-service?branch=master)

[XNAT](http://www.xnat.org/) module for controlling containers (primarily [Docker](https://www.docker.com/) containers).

## How to Build and Deploy
### Build a jar
If you clone this repo, you can build a jar by running

```
$ ./gradlew clean jar
```

### Deploy to XNAT
Copy the jar from `build/libs/containers-${VERSION}.jar` to 
the `${xnat.home}/modules` directory, and restart tomcat. 

Where is `${xnat.home}`? If you are using a VM generated by the 
[XNAT Vagrant project](https://bitbucket.org/xnatdev/xnat_vagrant), `xnat.home` is in the
`~/${PROJECT}` directory (and the default value for `${PROJECT}` is `xnat`). If you aren't 
using the Vagrant project, or even if you are and you're still confused, then `${xnat.home}/logs` 
is where XNAT writes its logs.

## How to Test
### Unit tests
The various unit tests can be run with
```
$ ./gradlew cleanTest unitTest
```

### Unit tests + Integration tests
If you have a docker server that you can use for testing, there are some additional
tests that can run. Set the `DOCKER_HOME` environment variable to the `tcp` address of
your server. If you need to use certificates for https, set `DOCKER_TLS_VERIFY=1` and
set `DOCKER_CERT_PATH` to the path to your certificate files.

Then run all the tests with
```
./gradlew cleanTest test
```