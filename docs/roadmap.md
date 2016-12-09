A bunch of stuff that you may want to do with the container service, some of which is actually possible.

# Docker
## Docker server
I want to configure XNAT to communicate with docker...

* on the same machine as XNAT (:white_check_mark:)
* on a machine remote from XNAT (:white_check_mark:)
* swarm (:no_good: [PIP-140](https://issues.xnat.org/browse/PIP-140))
* via Kubernetes (:no_good:)
* via Apache Mesos (:no_good:)

## Docker images
I want to use XNAT to add a docker image...

* `docker pull`ed from Docker Hub (:white_check_mark:)
* `docker pull`ed from a different docker repository (:white_check_mark:?)
* from a tgz file URL (:no_good: [PIP-141](https://issues.xnat.org/browse/PIP-141))
* from a file path to a tgz file on my docker server (:no_good: [PIP-141](https://issues.xnat.org/browse/PIP-141))
* from a file path to a tgz file on my XNAT server (:no_good: [PIP-141](https://issues.xnat.org/browse/PIP-141))
* by uploading a tgz file on my local machine (:no_good: [PIP-141](https://issues.xnat.org/browse/PIP-141))

# Commands
## Adding commands
I have a command I want to add to XNAT...

* and its definition JSON lives in my image's labels (:white_check_mark: auto-added when XNAT pulls the image, and can be manually added later)
* and its definition JSON lives in a file inside my image (:no_good: [PIP-142](https://issues.xnat.org/browse/PIP-142))
* and its definition JSON lives at a URL (:no_good: [PIP-143](https://issues.xnat.org/browse/PIP-143))
* by `POST`ing some JSON to a REST endpoint (:white_check_mark:)
* by pasting some JSON into a UI (:no_good:)
* in [Boutiques syntax](https://github.com/boutiques/boutiques) (:no_good: [PIP-144](https://issues.xnat.org/browse/PIP-144))
* in [CWL syntax](https://github.com/common-workflow-language/common-workflow-language) (:no_good: [PIP-145](https://issues.xnat.org/browse/PIP-145))

## Writing/defining commands
I have a docker image, and I want to define a command for it...

* with pretty much no help at all other than the code (:white_check_mark:)
* by asking [one of the container-service developers](https://github.com/johnflavin) for help (:white_check_mark:)
* by reading some documentation of the format (:no_good: [PIP-146](https://issues.xnat.org/browse/PIP-146))
* by following a tutorial or guide (:no_good:)
* by interactively defining it inside a UI (:no_good:)

## Other command management
I want to...

* delete a command (:white_check_mark:)
* update a few fields of an existing command (:white_check_mark:?)
* update a docker image, but keep the command the same (a subset of :point_up:)
* add a command to a project (:no_good: [PIP-147](https://issues.xnat.org/browse/PIP-147))

## Stuff you can do with a command
I want to...

* launch a container (see [Containers: Launching](#launching))
* use properties of XNAT objects (subject/session labels, scan ids, etc.) as inputs (:white_check_mark:)
* use files from XNAT as inputs (:white_check_mark:)
* upload files back to XNAT as a new...
    * resource (:no_good: [XNAT-4548](https://issues.xnat.org/browse/XNAT-4548) [PIP-124](https://issues.xnat.org/browse/PIP-124))
    * assessor (:no_good: [XNAT-4556](https://issues.xnat.org/browse/XNAT-4556) [PIP-125](https://issues.xnat.org/browse/PIP-125))
    * scan (:no_good: [PIP-148](https://issues.xnat.org/browse/PIP-148))
    * type of thing altogether, that just aggregates results of all the stuff I ran (:no_good:)

# Containers

## Launching
I want to launch a container...

* by archiving a session (:white_check_mark:)
* by `POST`ing a few input values up to a REST endpoint (:white_check_mark:)
* by clicking a button on an XNAT page (session, scan?, subject, etc.) and filling values into a UI (:no_good:)

I want to launch a bunch of containers all at once...

* by `POST`ing a list of input values up to a REST endpoint (:no_good:)
* from a custom search (:no_good:)

## Monitoring / reporting
I want to see...

* what containers are running right now, on anything (:no_good:)
* what containers are running right now, on a specific thing (session, scan, subject, etc.) (:no_good:)
* what containers have run in the past, on anything (:white_check_mark:)
* what containers have run in the past, on a specific thing (:no_good:)
* the status of a container execution (:no_good: [PIP-137](https://issues.xnat.org/browse/PIP-137))
* what files/resources/assessors/etc. were generated by the specific container execution I am looking at (:no_good:)
* what container execution produced the specific file/resource/assessor/etc. I am looking at (:no_good:)
* the logs from a completed container execution (:no_good: [PIP-138](https://issues.xnat.org/browse/PIP-138))

I want to...

* stop a running container (:no_good: [PIP-139](https://issues.xnat.org/browse/PIP-139))
* restart a failed/finished container, either as-is or with new input values (:no_good: [PIP-139](https://issues.xnat.org/browse/PIP-139))