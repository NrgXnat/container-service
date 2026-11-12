# Contributing to the XNAT Container Service

First off, thanks for taking the time to contribute! 🎉👍

We welcome any [bug reports](#report-an-issue), feature requests, or [questions](#ask-a-question). And we super-duper welcome any [pull requests](#make-a-pull-request).

This document is a little sparse now, but will hopefully evolve in the future.

## Report an issue

Bug reports and feature requests go in the repository's [GitHub Issues](https://github.com/NrgXnat/container-service/issues).
Alternatively, [ask a question on the discussion group](#ask-a-question)

## Ask a question

First, check the [XNAT Discussion Board](https://groups.google.com/g/xnat_discussion). It is possible that someone has already asked your question and gotten an answer, so it could save you a lot of time to search around first. And if no one has asked your question, the discussion board is a great first place to ask.


## Run the Tests
Building and testing require JDK 21. The tests also need a running Docker: the database tests start a PostgreSQL container through [Testcontainers](https://testcontainers.com/). Without Docker, they expect PostgreSQL on `localhost:5432`, with database, user and password all set to `test`.

The unit tests can be run with:
```
[container-service]$ ./gradlew unitTest
```

If you have a docker server that you can use for testing, there are some additional integration tests that launch real containers. Make sure your docker environment is all set up (you can test this by making sure `$ docker version` works). The tests connect to `DOCKER_HOST` if it is set, and to `unix:///var/run/docker.sock` otherwise. Then run them with
```
[container-service]$ ./gradlew integrationTest
```
`./gradlew test` and `./gradlew build` run only the unit tests. Integration tests for a backend you can't reach are skipped: the Swarm variants need your Docker to be a swarm manager, and the Kubernetes variants need a cluster reachable through your kubeconfig. Add `-DskipKubernetes=true` to skip the Kubernetes variants even when a cluster is reachable.

In order to synchronize your Docker VM clock, you may need to initially run the command as:
```
[container-service]$ docker run --rm --privileged alpine:latest hwclock -s && ./gradlew clean integrationTest
```
We do not have any tests that can integrate with a running XNAT. All of the tests in this library use bespoke databases and mocked interfaces any time the code intends to communicate with XNAT. We welcome your contributions!

## Make a Pull Request
If you want to contribute code, we will be very happy to have it.

The first thing you should know is that we do almost all of our work on the `dev` branch, or on smaller "feature" branches that come from and merge into `dev`. So if you want to do something with the code, you, too, should start a new branch from `dev` on our [GitHub repo](https://github.com/NrgXnat/container-service), and open your pull request against `dev`.




And thanks again!
