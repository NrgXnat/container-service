# XNAT Container Service

[XNAT](https://www.xnat.org/) plugin for controlling containers (primarily [Docker](https://www.docker.com/) containers).

To use it, you will need XNAT 1.10.1 or later, running on Java 21. Sites on XNAT 1.9 should stay on Container Service 3.8.x; see the [compatibility matrix](https://wiki.xnat.org/container-service/container-service-compatibility-matrix) for which version to use with older XNATs. Get the `container-service-<version>-fat.jar` (through one of the methods below) and put it into your `${xnat.home}/plugins` directory. Restart Tomcat and you are ready to run containers. See the [guide to getting started](https://wiki.xnat.org/container-service/getting-started).

Full documentation is on the [XNAT wiki](https://wiki.xnat.org/container-service). Changes in each release are listed in [CHANGELOG.md](CHANGELOG.md).

## Getting the jar
### Download
Releases are posted on the repository's [Releases page](https://github.com/NrgXnat/container-service/releases) on GitHub. Download the `container-service-<version>-fat.jar` for the version you want (probably the latest release) and [deploy it to XNAT](#deploy-to-xnat).

### Build the jar
Building the plugin requires JDK 21. If you clone the source repository, you can build an XNAT plugin jar by running

```
[container-service] $ ./gradlew fatJar
```

The jar will be created as `build/libs/container-service-<version>-fat.jar`. Use the `-fat` jar; the plain `container-service-<version>.jar` does not include the Docker and Kubernetes client libraries the plugin needs.

## Deploy to XNAT
Once you [have a jar](#getting-the-jar), copy it to the `${xnat.home}/plugins` directory, and restart Tomcat. If you are upgrading, remove the old Container Service jar from `plugins` first, so that only one version is loaded.

Where is `${xnat.home}`? `${xnat.home}/logs` is where XNAT writes its logs; you'll want the `plugins` directory which should be right next to the `logs` directory.

## Contributing
See [CONTRIBUTING.md](CONTRIBUTING.md).
