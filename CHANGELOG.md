# Changelog

## 3.6.0
[Released](https://bitbucket.org/xnatdev/container-service/src/3.6.0/)

* **Improvement** [XNAT-7990](https://radiologics.atlassian.net/browse/XNAT-7990) Update Hibernate and Spring dependencies for compatibility with XNAT 1.9.0
* **Improvement** [XNAT-7991](https://radiologics.atlassian.net/browse/XNAT-7991) Replace calls to deprecated commons-httpclient dependency
* **Improvement** [CS-943](https://radiologics.atlassian.net/browse/CS-943) Add filtering to lists of installed commands in site-wide and project settings
* Also improved unit tests for automated validation 

## 3.5.0
[Released](https://bitbucket.org/xnatdev/container-service/src/3.5.0/).

* **Improvement** [CS-946][] Prevent setting mutually distinct k8s PVC mounting options
* **Bugfix** [CS-966][] Ensure tracking of container IDs in workflow tables in a Kubernetes environment
* **Bugfix** [CS-968][] Switch the docker API library we use from [docker-client][] to [docker-java][].
    This should restore CS functionality on docker engine v25 and higher.
    
### A Note About Our Docker Library
Originally we used the [spotify/docker-client][] library to wrap the docker remote API in java method calls. They stopped updating that and put out their final release [v6.1.1][] in 2016.  

We switched the Container Service to use a fork of that client, [dmandalidis/docker-client][] in CS version 3.0.0. Given that this was a fork of the client we already used, it was a simple drop-in replacement with no changes needed.

But that library maintainer did continue to make changes. In 2023 they released a major version upgrade, [v7.0.0][], which dropped support for Java 8. That is the version of Java we use in XNAT (at time of writing) so this change meant we weren't able to update our version of this library. That was fine for a while...  
...Until version 25 of the docker engine, in which they made an API change which caused an error in the version we used of `docker-client`. The library (presumably) fixed their issue but we weren't able to use that fix because our version of the library was frozen by their decision to drop Java 8 support.

This forced us to switch our library from `docker-client` to [docker-java][]. This was not a drop-in replacement, and did require a migration. All the same docker API endpoints were supported in a 1:1 replacement—which took a little effort but was straightforward—except for one. The `docker-java` library did not support requesting `GenericResources` on a swarm service, which is the mechanism by which we allow commands to specify that they need a GPU. We opened a ticket reporting that lack of support (https://github.com/docker-java/docker-java/issues/2320), but at time of writing there has been no response. I created a fork (https://github.com/johnflavin/docker-java) and fixed the issue myself (https://github.com/docker-java/docker-java/pull/2327), but at time of writing that also has no response. I built a custom version of `docker-java` `3.4.0.1` and pushed that to the XNAT artifactory ([ext-release-local/com/github/docker-java][]).

Long story short, as of CS version `3.5.0` we depend on `docker-java` version `3.4.0.1` for our docker (and swarm) API support.

[CS-946]: https://radiologics.atlassian.net/browse/CS-946
[CS-966]: https://radiologics.atlassian.net/browse/CS-966
[CS-968]: https://radiologics.atlassian.net/browse/CS-968
[docker-client]: https://github.com/spotify/docker-client
[spotify/docker-client]: https://github.com/spotify/docker-client
[v6.1.1]: https://github.com/spotify/docker-client/releases/tag/v6.1.1
[dmandalidis/docker-client]: https://github.com/dmandalidis/docker-client
[v7.0.0]: https://github.com/dmandalidis/docker-client/tree/v7.0.0
[docker-java]: https://github.com/docker-java/docker-java
[ext-release-local/com/github/docker-java]: https://nrgxnat.jfrog.io/ui/repos/tree/General/ext-release-local/com/github/docker-java

## 3.4.3
[Released](https://bitbucket.org/xnatdev/container-service/src/3.4.3/).

* **Improvement** [XNAT-7903][] Support shared data at project level 
* **Bugfix** [CS-945][] Fix race condition on overlapping container status events

[CS-945]: https://radiologics.atlassian.net/browse/CS-945
[XNAT-7903]: https://radiologics.atlassian.net/browse/XNAT-7903

## 3.4.2
[Released](https://bitbucket.org/xnatdev/container-service/src/3.4.2/).

* **Bugfix** [CS-948][] Support K8s compute backend with PEM credential

[CS-948]: https://radiologics.atlassian.net/browse/CS-948

## 3.4.1
[Released](https://bitbucket.org/xnatdev/container-service/src/3.4.1/).

* **Improvement** [CS-923][] Process events by putting work onto a worker thread, not doing work in the event thread
* **Improvement** [CS-939][] Remove synchronization on Docker Client creation (improves performance when using Docker and Docker Swarm compute backends) 
* **Bugfix** [CS-944][] Fix regression in 3.3.2 that broke command preresolution / launch UI generation when derived inputs matched multiple possible XNAT objects

[CS-923]: https://radiologics.atlassian.net/browse/CS-923
[CS-939]: https://radiologics.atlassian.net/browse/CS-939
[CS-944]: https://radiologics.atlassian.net/browse/CS-944

## 3.4.0
[Released](https://bitbucket.org/xnatdev/container-service/src/3.4.0/).

* **New Feature** [CS-864][] Support for mounting files from Kubernetes Persistent Volume Claims (PVCs)
* **Bugfix** [CS-810][] Tweak logs on failed ping to compute backend server
* **Bugfix** [CS-934][] Fix support for optional file inputs

[CS-810]: https://radiologics.atlassian.net/browse/CS-810
[CS-864]: https://radiologics.atlassian.net/browse/CS-864
[CS-934]: https://radiologics.atlassian.net/browse/CS-934

## 3.3.2
[Released 2023-06-07](https://bitbucket.org/xnatdev/container-service/src/3.3.2/).

* **Improvement** [CS-835][] Refactor internals for fetching live logs to make them easier to understand and maintain
* **Bugfix** [CS-605][] Fix silent container failures that were happening in container launch process
* **Bugfix** [CS-902][] Fix for live container logs re-appending continuously

[CS-835]: https://radiologics.atlassian.net/browse/CS-835
[CS-605]: https://radiologics.atlassian.net/browse/CS-605
[CS-902]: https://radiologics.atlassian.net/browse/CS-902

## 3.3.1
[Released 2023-03-30](https://bitbucket.org/xnatdev/container-service/src/3.3.1/).

* **Bugfix** [CS-834][] Handle `ISO_DATE_TIME`s in addition to `ISO_INSTANT`s in container log timestamps

[CS-834]: https://radiologics.atlassian.net/browse/CS-834

## 3.3.0
[Released 2022-10-11](https://bitbucket.org/xnatdev/container-service/src/3.3.0/).

* **New Feature** [CS-718][] Support for passing Secrets into containers. 
  For more information see [Making Use of Container Service Secrets][wiki-cs-secrets] on the XNAT wiki.
* **Deprecation** [CS-525][] Complete deprecation of old-style Command Automation (a.k.a. Command Event Mappings) in favor of [Event Service Subscriptions][wiki-event-service]
    * Remove UI to create new Command Automations
    * Enable migration / conversion of existing Command Automations into Event Service Subscriptions
* **Improvement** [CS-710][] Invalidate container's XNAT alias token on finalization
* **Improvement** [CS-722][] Improve UI when commands are added / edited but get rejected with errors
* **Bugfix** [CS-627][] Fix error on blank/empty swarm constraints
* **Bugfix** [CS-723][] Enable streaming logs from running containers on Kubernetes

[CS-525]: https://issues.xnat.org/browse/CS-525
[CS-627]: https://issues.xnat.org/browse/CS-627
[CS-710]: https://issues.xnat.org/browse/CS-710
[CS-718]: https://issues.xnat.org/browse/CS-718
[CS-722]: https://issues.xnat.org/browse/CS-722
[CS-723]: https://issues.xnat.org/browse/CS-723
[wiki-cs-secrets]: https://wiki.xnat.org/display/CSDEV/Making+use+of+Container+Service+Secrets
[wiki-event-service]: https://wiki.xnat.org/documentation/xnat-administration/enabling-the-xnat-event-service

## 3.2.1
[Released 2022-09-07](https://bitbucket.org/xnatdev/container-service/src/3.2.1/).

### Bugfixes

* [CS-767][]: Fixed issue with command automation which failed with the message "No command with wrapper with id 0" during command resolution.

[CS-767]: https://issues.xnat.org/browse/CS-767

## 3.2.0

[Released 2022-08-02](https://bitbucket.org/xnatdev/container-service/src/3.2.0/).

With this release, the minimum supported XNAT version is 1.8.5.

### Support for Kubernetes!

[CS-688][] In addition to Docker and Docker Swarm, with Container Service 3.2.0 you can now run your container jobs on a
Kubernetes cluster. We expect that this will be a welcome addition for site admins that deploy to the cloud,
as all the cloud providers have native solutions for building a Kubernetes cluster.

All the core Container Service operations are supported: launching containers, monitoring and reporting their
states, mounting XNAT data files as container inputs, loading container outputs into XNAT as new objects,
and reading container logs.

However, some Container Service features that are supported for Docker or Docker Swarm backends are not (yet)
supported for a Kubernetes backend, or are supported with some differences.

* [CS-728][]: The Container Service settings for Compute Backend—like Host URI and Certificate Path—are not used to configure a connection to Kubernetes. Instead, when you set your Compute Backend to Kubernetes, the Container Service will configure the connection to Kubernetes by reading a kubeconfig file that you must prepare outside of XNAT. See [Setting up Container Service on Kubernetes][wiki-kubernetes-setup] on the XNAT Wiki for more information.
* [CS-727][]: We do not support private images or private registries using the Image Hosts configuration in the Container Service admin settings.
  However, you can configure registry authentication within Kubernetes itself; see the section on Configuring Registry Credentials in Kubernetes on the
  [Configuring Image Registries][wiki-image-registries] page on the XNAT Wiki.
* [CS-726][]: We do not support the Finalizing Throttle setting, which allows you to limit the number of containers that can finalize concurrently.
* We support setting the user within the container who will run processes. In Kubernetes the user value must be an integer user id, unlike Docker and Docker Swarm which support both user id and user name.
* [CS-747][]: We do not support setting user group within the container.
* [CS-723][]: We do not support streaming live logs from a running container.
* [CS-737][]: Logs from finalized containers can be viewed in the Container History. However, the logs contain both stdout and stderr merged together. This is a Kubernetes limitation that we cannot work around.
* We support Placement Constraints, which allow you to control the placement of containers onto nodes of your cluster. However, Kubernetes only allows constraints on node labels unlike Docker Swarm which allows constraints on many node and engine properties. This is a Kubernetes limitation that we cannot work around.

Container Service 3.2.0 also has many other changes.

### Features

* [XNAT-7003][]: Run containers as scheduled events. This increases the minimum XNAT version requirement to 1.8.5.
* [CS-625][]: Enable adding Commands in the site configuration UI independently of adding Docker images
* [CS-601][]: Improve checks for whether a user will be able to perform all actions required by a command
    * Add `"xsi-type"` property to command wrapper output handler
    * Command wrappers that create a session assessor no longer require the user to have edit permissions on the session.
      If the output handler declares an `xsi-type` for the assessor, we can verify the user has permissions to create it before launch.
    * Filter `Run Containers` menu to only those items that a user will have permission to run
* [CS-715][]: Run containers on shared sessions

### Bugfixes

* [CS-682][]: Fix NullPointerError in Orchestration equals method
* [CS-686][]: Fixed: Command with Project Asset type external input fails when launched with BLP
* [CS-687][]: Repaired unit and integration tests that had not kept up with code changes.
* [CS-693][]: Stop service updater from trying to update `null` node IDs forever if service cannot be started
* [CS-695][]: Write container logs to a unique directory per container, not timestamped
* [CS-699][]: Fix issue viewing logs in Container History UI with certain container IDs
* [CS-706][]: When returning list of images, attempt to check local docker server only if host type is set to Docker
* [CS-707][]: Fixed issue that blocked mounting Subject Assessor as an input

### Updates

* [CS-681][]: Bumped version of [mandas/docker-client]() to 5.2.2.
* [CS-689][] [CS-692][]: Internal API refactors
* [CS-701][]: Ensure a new Command's `image` property has a tag, defaulting to `latest`
* [CS-704][]: Update host setup UI. `Type` is now the top-level option and must be provided first. Other options fill in based on `Type`.
* [CS-714][]: Change name of admin setup UI from "Docker Server Setup" to "Compute Backend"

[CS-601]: https://issues.xnat.org/browse/CS-601
[CS-625]: https://issues.xnat.org/browse/CS-625
[CS-681]: https://issues.xnat.org/browse/CS-681
[CS-682]: https://issues.xnat.org/browse/CS-682
[CS-686]: https://issues.xnat.org/browse/CS-686
[CS-687]: https://issues.xnat.org/browse/CS-687
[CS-688]: https://issues.xnat.org/browse/CS-688
[CS-689]: https://issues.xnat.org/browse/CS-689
[CS-692]: https://issues.xnat.org/browse/CS-692
[CS-693]: https://issues.xnat.org/browse/CS-693
[CS-695]: https://issues.xnat.org/browse/CS-695
[CS-699]: https://issues.xnat.org/browse/CS-699
[CS-701]: https://issues.xnat.org/browse/CS-701
[CS-704]: https://issues.xnat.org/browse/CS-704
[CS-706]: https://issues.xnat.org/browse/CS-706
[CS-707]: https://issues.xnat.org/browse/CS-707
[CS-714]: https://issues.xnat.org/browse/CS-714
[CS-715]: https://issues.xnat.org/browse/CS-715
[CS-723]: https://issues.xnat.org/browse/CS-723
[CS-726]: https://issues.xnat.org/browse/CS-726
[CS-727]: https://issues.xnat.org/browse/CS-727
[CS-728]: https://issues.xnat.org/browse/CS-728
[CS-737]: https://issues.xnat.org/browse/CS-737
[CS-747]: https://issues.xnat.org/browse/CS-747
[XNAT-7003]: https://issues.xnat.org/browse/XNAT-7003
[wiki-kubernetes-setup]: https://wiki.xnat.org/display/CSDEV/Setting+up+Container+Service+on+Kubernetes
[wiki-image-registries]: https://wiki.xnat.org/display/CSDEV/Configuring+Image+Registries

## 3.1.1

[Released 2022-02-18](https://bitbucket.org/xnatdev/container-service/src/3.1.1/).

### Features

* [CS-563][]: Added support for deriving Subject Assessor inputs, independent of Image Session types.
* [CS-617][]: Add configuration parameter to disable status emails.
* [CS-686][]: Fixed support for Batch Launch of commands with Project Asset context.

### Bugfixes

* [CS-677][]: Fixed issue that occurred when pulling Docker images with ':' in the name.
* [CS-679][]: Fixed issue that occurred when using the Batch Launch Plugin (BLP) to launch Image Assessor level containers. Image Assessors were resolved as Experiment types and excluded particular assessor properties.  
* [CS-598][] & [CS-609][]: Fixed related issues that caused CS to check for 'lost' service tasks forever, which in turn filled CS logs with repeated exceptions.

### Updates

* [CS-680][]: Added 'group', 'source', and 'initials' string properties to Subject model object type.
* [CS-678][]: With this change, you can provide credentials along with your "Hub" and they'll be used for pull and for starting a swarm service (which will allow the workers to pull the image as well).

  NOTE: The CS-678 update added a uniqueness constraint on hub URL, so you cannot have two separate sets of credentials for the same url (e.g., dockerhub). The workaround would be to have a dockerhub account for the XNAT and give it readonly access to any private repos you want your XNAT users to be able to use.


[CS-563]: https://issues.xnat.org/browse/CS-563
[CS-598]: https://issues.xnat.org/browse/CS-598
[CS-609]: https://issues.xnat.org/browse/CS-609
[CS-617]: https://issues.xnat.org/browse/CS-617
[CS-677]: https://issues.xnat.org/browse/CS-677
[CS-678]: https://issues.xnat.org/browse/CS-678
[CS-679]: https://issues.xnat.org/browse/CS-679
[CS-680]: https://issues.xnat.org/browse/CS-680
[CS-683]: https://issues.xnat.org/browse/CS-683


## 3.1.0

[Released 2020-10-01](https://bitbucket.org/xnatdev/container-service/src/3.1.0/).

### Features

* [CS-652][]: Added container orchestration. [See documentation](https://wiki.xnat.org/container-service/set-up-command-orchestration-130515311.html)
* [CS-656][]: Added Subject Assessor model object support to command resolution logic.

### Bugfixes

* [CS-659][]: Use password input tag for CS inputs with sensitive=true
* [CS-671][]: Fixed external timestamp display for Docker events

### Updates

* [CS-651][]: Updated [docker-client api library](https://github.com/dmandalidis/docker-client) to 5.0.1 

* Reduced logging level from INFO/DEBUG to WARN

* Refactored queue request handler to process ml:trainSession, rather than clara:trainSession.

[CS-651]: https://issues.xnat.org/browse/CS-651
[CS-652]: https://issues.xnat.org/browse/CS-652
[CS-656]: https://issues.xnat.org/browse/CS-656
[CS-659]: https://issues.xnat.org/browse/CS-659
[CS-671]: https://issues.xnat.org/browse/CS-671


## 3.0.0

[Released 2020-03-04](https://bitbucket.org/xnatdev/container-service/src/3.0.0/).


### Features

* [CS-6221][]: Added support for Generic Resources in order to request GPU resources on SWARM nodes
* [CS-6227][]: Added 'datatype-string' property to ProjectAsset, Session, Scan, Assessor, and Resource CS objects.  This string property is populated with output of xnat_object.toString(). Added 'parser' parameter to string-type derived wrapper input.  This parameter can be either an XPath or RegEx string, which will attempt to parse the input.


#### Added container command parameters:
    
    container-labels - Map<String,String> corresponding to Docker container labels (default: null)
    container-name - string(default null - container is named by docker)
    shm-size - long (default: null)
    network - string as attached network

##### Swarm Mode only
    generic-resources - Map<String, String> corresponding to swarm node generic-resource reservations 
                     i.e. "VRAM": "2000"


##### Docker Server only - not supported on Swarm services: 
    
    runtime - string (default: null)
    ipc-mode - string (default: null)
    ulimits - Map<String, String> where each entry is a string label and a value comprised of the desired ULimit softlimit:hardlimit
    
#### Added Rest APIs to get containers by name - this only works if container was assigned name by Container Service
    
    /projects/{project}/containers/name/{name} - param: nonfinalized bool
    /container/name/{name} - param: nonfinalized bool

#### Added project scoped container kill Rest API:
    
    /projects/{project}/containers/{id}/kill

[CS-6221]: https://issues.xnat.org/browse/XNAT-6221
[CS-6227]: https://issues.xnat.org/browse/XNAT-6227

## 2.1.1

### Features

* Use ActiveMQ queues for staging (command resolution and container launch) and finalizing containers.
* Update UI not to wait for command resolution and container launch before returning to user, add additional details to workflow entry so user still receives all information.
* Revamp bulk launch UI to be faster to load (only serializes first session) and show limited info to user about derived inputs. Previous bulklauncher UI would serialize all XNAT objects for all selected sessions before rendering the "launch container" form. It did this serializing, which can be very slow depending on how deep the preresolution needs to go into the hierarchy, while the user was waiting. Additionally, the resulting UI form only displayed inputs for the first session, which can be misleading or even incorrect (e.g., setting a scan id or file name as in pyradiomics) - it only worked if you want the same setting for all sessions.
* Allow admin to define docker swarm constraints (which may be user-settable).
* Automatically restart containers that are killed due to docker swarm nodes being removed from swarm.
* Live logging display
* Support command inputs that resolve to mulitple values (e.g., multiple T1 scans)
* Add label field to command.json inputs to allow customization of UI display
* Add select-values field to command.json inputs to allow select box rendering of non-xnat inputs
* Support scan output type (via scan xml) and resource uploads to it
* Make use of "derived-from-xnat-object-property" for XNAT object type derived inputs (previously, this was only used when derived input was type="string")
* Allow an input with type="File" to provide files for command mount
* [CS-583][]: Add option to automatically remove containers/services after they've been finalized

### Bugfixes
* Remove @Audited annotations to prevent database size explosion
* Mannually set swarm service names since auto-generated ones can clash on high throughput
* Allow removal of inputs, outputs, wrappers, external inputs, derived inputs, and output handlers from command.json via API
* [CS-54][]: For writable directory mount with existing contents in archive space, copy all files out of the root (archive) directory to a build directory. NOTE: case of a mount being both input and output not addressed
* [CS-575][]: Fix resolution of derived inputs to always set value to URI. This allows outputs to be uploaded to derived inputs. This "undoes" some of the fix CS-409, but the issue with URIs in the UI/launcher seems to have been resolved, and we still can derive an inupt by URI, label, or name. *This may be a breaking change for some command.json files. The replacement value, a.k.a., the thing that gets put into #inputName#, will now always be the URI (previously it varied: id for scan, name for file, label for project, label for subject derived from project, URI for subject derived from session and session derived from assessor or scan)*
* [CS-576][]: Correctly construct authentication header for Docker Swarm, required because Spotify client does not implement authForSwarm() for ConfigFileRegistryAuthSupplier, which is the only type of private repo authentication XNAT currently supports and because Swarm does not default to using a local config.json if this header is not passed
* [CS-577][]: Ports, hash, and index fields of command.json can now be updated
* [CS-578][]: Enforce ordering on uploading outputs so that we never try to upload to an object that has yet to be inserted
* [CS-580][]: Add client-side validation for required inputs
* [CS-579][]/[CS-592][]: Fixed an issue which caused assessor resources to be ignored in command resolution. 
* [CS-592][]: Fixed an issue which caused assessor resources to be ignored in command resolution.


[CS-54]: https://issues.xnat.org/browse/CS-54
[CS-575]: https://issues.xnat.org/browse/CS-575
[CS-576]: https://issues.xnat.org/browse/CS-576
[CS-577]: https://issues.xnat.org/browse/CS-577
[CS-578]: https://issues.xnat.org/browse/CS-578
[CS-579]: https://issues.xnat.org/browse/CS-579
[CS-580]: https://issues.xnat.org/browse/CS-580
[CS-583]: https://issues.xnat.org/browse/CS-583
[CS-592]: https://issues.xnat.org/browse/CS-592

## 2.1.0

[Released 2020-01-21](https://github.com/NrgXnat/container-service/releases/tag/2.1.0).

Along with several bug fixes, this release focuses on permissions updates required for compatibility with XNAT 1.7.6.  These updates break backward compatibility with previous versions of XNAT (<1.7.6). Users of XNAT 1.7.5.x should use [Container Service 2.0.1](https://github.com/NrgXnat/container-service/releases/tag/2.0.1).

### Bugfixes

* [CS-440][] Fixed an issue which caused guest users to see authentication dialog on public projects.
* [CS-572][]: Change command listing permissions to require only project-read permissions. Viewing the "Run containers" action is still restricted by item "canEdit" permissions, although this does need to be modified since, for example, a custom user group ought to be able to read an MR session and write a QC assessor by launching a container on the read-only session.
* [CS-584][]: Fixed bug which kept external and derived input replacement-key values from propagating to the command line.

### Updates

* [CS-585][]: Updated permissions model and h2 database parameters (in unit tests) to align with XNAT 1.7.6 requirements


[CS-440]: https://issues.xnat.org/browse/CS-440
[CS-572]: https://issues.xnat.org/browse/CS-572
[CS-584]: https://issues.xnat.org/browse/CS-584
[CS-585]: https://issues.xnat.org/browse/CS-585



## 2.0.1

[Released 2019-04-06](https://github.com/NrgXnat/container-service/releases/tag/2.0.1).

### Bugfixes

* [CS-554][] Prevent illegal characters from being used in command input names.
* [XNAT-5876][] Prevent Container Service from overwriting bugfix for character handling in core XNAT


[CS-554]: https://issues.xnat.org/browse/CS-554
[XNAT-5876]: https://issues.xnat.org/browse/XNAT-5876



## 2.0.0

[Released 2019-04-04](https://github.com/NrgXnat/container-service/releases/tag/2.0.0).

### Features

* [CS-29][] Enable output handlers to have type "`Assessor`". If a command output points to an assessor XML, the output handler can now upload that XML and create the assessor object in XNAT.
* [CS-542][] Add `"container-user"` to Docker Server settings. This allows you to specify the user within the container as whom the command should be run. If you specify nothing, the process within the container is run as root or whatever user settings are defined on the image (which is unchanged from the current behavior).
* [CS-543][] Add support for setting container user as `"containerUser=username"` in prefs-init.ini config file. This value is only used when no other server settings exist in the database, such as initial deployment. 
* [CS-545][] Project owners can now view project-specific Container History tables in the Project Settings UI.
* [CS-547][] Replacement keys in Setup and Wrapup command line strings are now resolved with parent container input values.
* [CS-549][] Refactor the container launch API output to support complex parent-child-grandchild relationships between inputs, then adjust the UI to use the new API.

### Bugfixes

* [XNAT-5785][] Ouputs that contain directories now maintain directory in resource. (Previous behavior would dump contents of directory into resource.)
* [CS-515][] Adds a version checker to warn users if plugin is not installed on a compatible version of XNAT. 
* [CS-541][] Use Path Translation setting when creating mounts for setup and wrapup commands.
* [CS-544][] Project level setup and wrapup command statuses now appear in project level history table.
* [CS-546][] Fixed Assessor as Command Input functionality.
* [CS-550][] Fixed rendering of long elements in container history table.
* [CS-557][] Fix element display for command table headers in Admin UI.
* [CS-558][] Fix labeling bug in bulk launcher from project data listing.

[CS-29]: https://issues.xnat.org/browse/CS-29
[XNAT-5785]: https://issues.xnat.org/browse/XNAT-5785
[CS-515]: https://issues.xnat.org/browse/CS-515
[CS-541]: https://issues.xnat.org/browse/CS-541
[CS-542]: https://issues.xnat.org/browse/CS-542
[CS-543]: https://issues.xnat.org/browse/CS-543
[CS-544]: https://issues.xnat.org/browse/CS-544
[CS-545]: https://issues.xnat.org/browse/CS-545
[CS-546]: https://issues.xnat.org/browse/CS-546
[CS-547]: https://issues.xnat.org/browse/CS-547
[CS-549]: https://issues.xnat.org/browse/CS-549
[CS-550]: https://issues.xnat.org/browse/CS-550
[CS-557]: https://issues.xnat.org/browse/CS-557
[CS-558]: https://issues.xnat.org/browse/CS-558
 
## 1.6.0

[Released 2018-10-29](https://github.com/NrgXnat/container-service/releases/tag/1.6.0).

### Features

* Change name of Command Wrapper Output property `as-a-child-of-wraper-input` to `as-a-child-of`. This is to support [CS-80][] and allow uploading outputs to other outputs. (Note that the title of the database column has not changed, and is still `wrapperInputName`.)
* [CS-80][] Allow command wrapper output handlers to create new objects as children of objects created by previous output handlers. The previous behavior allowed outputs to be handled only by wrapper inputs.
* [CS-457][] Add `label` field to command wrapper. This will be used to represent the command wrapper in the "Run Container" menu going forward.
* [CS-458][] `GET /commands/available` includes command and wrapper labels
* Manually refresh resource catalog when uploading resources. (We used to rely on the Catalog Service to do this, but it doesn't anymore.)
* [CS-407][] Add docker host setting to translate mount paths. If xnat sees a path at /data/xnat/x/y/z but your docker host sees the same path at /my/path/x/y/z, you can include these path prefixes in your docker server settings. If you're using REST, set the properties "path-translation-xnat-prefix" and "path-translation-docker-prefix" respectively. If you're using the Plugin Settings UI, the fields will be there.
* [CS-494][] Add docker host setting for whether to check on server startup if all the images referenced by commands are present, and pull them if not.
* [CS-502][] When a container is launched with the project-specific launch APIs (`/xapi/projects/{project}/.../launch`), that project is now saved as a property on the container.
* [CS-503][] Add APIs to get containers by project.
* [CS-513][] Increase length of several fields:
    * Command
        * Description
        * Command-line
    * Command Input
        * Description
        * Default value
    * Command Wrapper
        * Description
    * Command Wrapper Derived Input
        * Description
        * Default value
    * Command Wrapper External Input
        * Description
        * Default value
    * Container
        * Command-line
* Include a container's database ID in the `LaunchReport` returned after launch.
* Add a new container status: "Finalizing". This is set when the container has finished and container service begins its finalization process (uploading outputs and logs). When finalization is finished, the status is set to "Complete" as before.
* [CS-535][] Add command and wrapper input property `sensitive`. This boolean property, when set to true, will cause the value to be masked out in the container history UI and REST API. (The value is still present in the database and may be printed to logs.) In addition, if *any* inputs on a command / wrapper are marked as sensitive, the `raw` type inputs—i.e. those input values that were sent by the user and saved before any processing—will not be shown in the UI or API. The reason being that 1. if sensitive information exists, it may be leaked by raw inputs; 2. we have no way for the user to tell us anything about the raw inputs, including their potential sensitivity; thus 3. we can't guarantee any of their values are not sensitive.
* Add another rest endpoint for `/commands/available` that takes project in path instead of query: `/projects/{project}/commands/available?xsiType={whatever}` instead of `/commands/available?project={project}&xsiType={whatever}`.

### Bugfixes

* [CS-488][] Allow wrappers to derive `Session` inputs from `Assessor` inputs, in the same way you can derive `Session`s from `Scan`s.
* [CS-492][] Save `override-entrypoint` property on `Container` entity. (It was being used properly to make the docker container, but not saved in database.)
* [CS-510][] Enable fetching logs from a running service.
* History UI now fetches logs by container database id, not container docker id (or service id).
* [CS-520][] Can get containers by service ID. This applies to the internal `ContainerService.get(String id)` as well as REST `/xapi/containers/{id}`.
* Prevent generating duplicate `ContainerEntityHistory` items (and audit table entries) by improving equality check.
* [CS-409] Derived input values now sent to the launch UI as ID or name or value, rather than URI. Conversely, derived input values *can* be interpreted as URIs or IDs or names, whereas before each type of derived input had its own special undocumented requirement for an input value to be interpreted.
* [CS-531][] CommandWrapperEntity derivedInputs, externalInputs, and outputHandlers and CommandEntity inputs, outputs, wrappers, and mounts sorted by primary table id.
* Fix `null` label on `XnatFile` objects. Now label is the same as name.

### Other

* [CS-537][] References to the dummy TransportService removed, as it was a placeholder for functionality implemented elsewhere.
* [CS-480][] Deprecate `Container.ContainerMount.inputFiles`. Having a list of input files for a mount is nice during command resolution, but it doesn't make much sense to store that list. As of now no new containers will have anything in their mounts' `inputFiles`. Old containers will still report their values for `inputFiles` for now, but this may change in the future.
* Remove the constant log entries for event polling. We get it. You're checking for events.


[CS-80]: https://issues.xnat.org/browse/CS-80
[CS-407]: https://issues.xnat.org/browse/CS-407
[CS-409]: https://issues.xnat.org/browse/CS-409
[CS-457]: https://issues.xnat.org/browse/CS-457
[CS-458]: https://issues.xnat.org/browse/CS-458
[CS-480]: https://issues.xnat.org/browse/CS-480
[CS-488]: https://issues.xnat.org/browse/CS-488
[CS-492]: https://issues.xnat.org/browse/CS-492
[CS-494]: https://issues.xnat.org/browse/CS-494
[CS-500]: https://issues.xnat.org/browse/CS-500
[CS-502]: https://issues.xnat.org/browse/CS-502
[CS-503]: https://issues.xnat.org/browse/CS-503
[CS-510]: https://issues.xnat.org/browse/CS-510
[CS-513]: https://issues.xnat.org/browse/CS-513
[CS-520]: https://issues.xnat.org/browse/CS-520
[CS-531]: https://issues.xnat.org/browse/CS-531
[CS-535]: https://issues.xnat.org/browse/CS-535
[CS-537]: https://issues.xnat.org/browse/CS-537
[CS-547]: https://issues.xnat.org/browse/CS-547

## 1.5.1

[Released 2018-03-15](https://github.com/NrgXnat/container-service/releases/tag/1.5.1).

### Bugfixes

* [CS-479][] Restores wildcard expansion for container command-line strings.
    To do this, modified behavior introduced in previous version. When `override-entrypoint` is `null` or `false`, the container is created with...
    ```
    Image.Cmd = COMMAND (split into tokens like a shell would)
    Image.Entrypoint = null (to leave the entrypoint it as is)
    ```
    But when `override-entrypoint` is `true`...
    ```
    Image.Cmd = ["/bin/sh", "-c", COMMAND]
    Image.Entrypoint = null (to leave the entrypoint it as is)
    ```
    Adding back the explicit `/bin/sh` is what restores the ability to expand wildcards.

[CS-479]: https://issues.xnat.org/browse/CS-479

## 1.5.0

[Released 2018-02-15](https://github.com/NrgXnat/container-service/releases/tag/1.5.0).

### Features

* [CS-464][] Wrapup commands / containers
* [CS-461][] and [CS-462][] Change handling of image entrypoint. (Reverts changes introduced in 1.4.0 by [CS-433][].) For discussion of this issue, see this [xnat_discussion board post][entrypoint-post].
    The APIs we had been using are these for containers...
    ```
    Image.Cmd = ["/bin/sh", "-c", COMMAND]
    Image.Entrypoint = [""]
    ```
    And these for swarm services...
    ```
    ContainerSpec.Command = ["/bin/sh", "-c", COMMAND]
    ContainerSpec.Args = null
    ```
    This caused the image entrypoint to be overriden in all cases, with no recourse for the command author. With this pair of changes, we change the way we use the APIs to launch containers in all cases, but also provide an optional property on the Command (`"override-entrypoint"`) for whether to override the entrypoint or not (defaulting to `false`, i.e. do not override). So now, whether overriding the entrypoint or not, we pass the resolved command-line string to this API for containers...
    ```
    Image.Cmd = COMMAND (split into tokens like a shell would)
    ```
    Depending on whether the entrypoint is overridden or not, we set this value for containers...
    ```
    Image.Entrypoint = [""] (to override the entrypoint, or)
    Image.Entrypoint = null (to leave the entrypoint it as is)
    ```
    For swarm services, we have to use different APIs depending on whether we override the entrypoint or not. When not overriding, we use...
    ```
    ContainerSpec.Command = null
    ContainerSpec.Args = COMMAND (split into tokens like a shell would)
    ```
    When overriding, we use
    ```
    ContainerSpec.Command = ["/bin/sh", "-c", COMMAND] (command not split into tokens, because the /bin/sh will do that)
    ContainerSpec.Args = null
    ```
* [nrgxnat/container-service#6][] Add option to reserve memory and/or limit memory and CPU usage of containers via command entries "reserve-memory", "limit-memory", "limit-cpu". Update command documentation accordingly.

### Bugfixes

* [CS-475][] Ensure references to setup and wrapup containers can be resolved unambiguously by including the name of the source object on the parent container for which we created this particular setup or wrapup container.
* Run tests in swarm mode as well as non-swarm. Fix bugs and broken tests.

[nrgxnat/container-service#6]: https://github.com/NrgXnat/container-service/pull/6
[CS-433]: https://issues.xnat.org/browse/CS-433
[CS-461]: https://issues.xnat.org/browse/CS-461
[CS-462]: https://issues.xnat.org/browse/CS-462
[CS-464]: https://issues.xnat.org/browse/CS-464
[CS-475]: https://issues.xnat.org/browse/CS-475
[entrypoint-post]: https://groups.google.com/forum/#!msg/xnat_discussion/NBVjAS8gXhU/Zu7xJngCAgAJ

## 1.4.0

[Released 2018-01-05](https://github.com/NrgXnat/container-service/releases/tag/1.4.0).

### Features

* [CS-421][] Add Setup Commands. These are special commands that can be used to pre-process files from XNAT before they are mounted into a container launched from a "main" command.
* [CS-355][] When deleting a docker image through CS API, also delete all commands and wrappers associated with the image.
* Docs: Add new script that bulk uploads all changed source docs
* Docs: Add support for "NO UPLOAD" comment in source docs that should be skipped and not uploaded to wiki
* [CS-340][] Check in generated swagger.json from REST API dump, and scripts to upload it to wiki
* Add lots of new documentation
    * [CS-434][] Command resolution
    * [CS-434][] Container launching (internals)
    * Container launching (as a user)
    * Enabling commands on a project
    * Troubleshooting

### Bugfixes

* Docs: Fix mishandling of anchor tags/links on wiki pages. Confluence uses a macro for these, not raw HTML anchors.
* [CS-420][] Fix handling of non-standard default values for boolean inputs in bulk launches
* [CS-432][] Do not update container/workflow status if new events come in from docker and container is already in a "terminal" state (Completed, Failed, Killed, or related states)
* [CS-433][] Remove image entrypoints when launching containers.
* [CS-435][] Command wrappers with no descriptions were not displaying properly in the UI
* [CS-442][], [CS-443][], [CS-449][] Multiple failures when running XNAT in a non-root context: pulling images, project settings, bulk launching
* [CS-448][] Fix height of 'Edit Command' dialog so full code editor is displayed
* [CS-450][] Restrict usage of command automation to commands that match the context of selected events
* [CS-454][] Container working directory was not being saved at launch

[CS-340]: https://issues.xnat.org/browse/CS-340
[CS-355]: https://issues.xnat.org/browse/CS-355
[CS-420]: https://issues.xnat.org/browse/CS-420
[CS-421]: https://issues.xnat.org/browse/CS-421
[CS-430]: https://issues.xnat.org/browse/CS-430
[CS-432]: https://issues.xnat.org/browse/CS-432
[CS-433]: https://issues.xnat.org/browse/CS-433
[CS-434]: https://issues.xnat.org/browse/CS-434
[CS-435]: https://issues.xnat.org/browse/CS-435
[CS-442]: https://issues.xnat.org/browse/CS-442
[CS-443]: https://issues.xnat.org/browse/CS-443
[CS-448]: https://issues.xnat.org/browse/CS-448
[CS-449]: https://issues.xnat.org/browse/CS-449
[CS-450]: https://issues.xnat.org/browse/CS-450
[CS-454]: https://issues.xnat.org/browse/CS-454

## 1.3.2

[Released 2017-11-08](https://github.com/NrgXnat/container-service/releases/tag/1.3.2).

### Features

* [CS-282][] Deprecated the "Project Opt-in" setting that wanted to set a default behavior for projects when a new command was added to a site. The UI existed and appeared to do things but was not tied to a functional API.

### Bugfixes

* [CS-335][] Fix permissions errors with command project configuration
* [CS-348][] Ensure that project defaults would be used for container launches where present, instead of defaulting to site-wide defaults
* [CS-389][] Set ":latest" as the default tag value when importing an image
* [CS-392][] Improve behavior of dialog when removing project-specific defaults for a command
* [CS-399][] Refresh image list when deleting a command config
* [CS-410][] Fix command update so all fields in all child objects can update too
* [CS-402][] Commands launched automatically on session archive now create workflows
* [CS-402][] Remove en-dash from scan label. This allows automated launches to succeed.
* [CS-414][] Fix bug that caused command automation button to disappear when panel refreshed
* [CS-415][] Do not read old container events after updating docker server settings
* [CS-416][] Fix a lot of extraneous error messages when launching containers on scans of the form "Cannot construct a (whatever) URI. Parent URI is null."
* [CS-418][] Fix a bug with the handling of boolean checkboxes in the command launch UI"

[CS-282]: https://issues.xnat.org/browse/CS-282
[CS-335]: https://issues.xnat.org/browse/CS-335
[CS-348]: https://issues.xnat.org/browse/CS-348
[CS-389]: https://issues.xnat.org/browse/CS-392
[CS-392]: https://issues.xnat.org/browse/CS-392
[CS-399]: https://issues.xnat.org/browse/CS-392
[CS-402]: https://issues.xnat.org/browse/CS-402
[CS-410]: https://issues.xnat.org/browse/CS-410
[CS-414]: https://issues.xnat.org/browse/CS-414
[CS-415]: https://issues.xnat.org/browse/CS-415
[CS-416]: https://issues.xnat.org/browse/CS-416
[CS-418]: https://issues.xnat.org/browse/CS-418

## 1.3.1

[Released 2017-10-11](https://github.com/NrgXnat/container-service/releases/tag/1.3.1).

### Features

* [CS-376][] Enable bulk launching of containers from data tables on the project report page. Any filters that the user sets on these tables will be reflected in the list of items that get prepared for launch. The user can then confirm, select or deselect any of those items before launching containers. This feature also works for any project-specific stored search.

[CS-376]: https://issues.xnat.org/browse/CS-376

### Bugfixes

* [CS-395][] Catch a rare error that causes the search ID for a project data table to expire within an active session. This interrupts the user's ability to launch a batch of containers from this table.
* [CS-396][] Update text of buttons and dialogs related to bulk launches.

[CS-395]: https://issues.xnat.org/browse/CS-395
[CS-396]: https://issues.xnat.org/browse/CS-396

## 1.3.0

[Released 2017-09-29](https://github.com/NrgXnat/container-service/releases/tag/1.3.0).

### Features

* [CS-1][] Docker swarm integration. You can configure the container service to launch services on your docker swarm by pointing it to your swarm master and setting Swarm Mode to true. This is a global setting; when swarm mode is turned on all jobs will be run as services sent to the swarm master, when it is off all jobs will be run as containers on the docker server. In addition, the docker server `ping` function in swarm mode will check not only if the specified server is reachable, but also that it is configured as a swarm master (i.e. it has to respond correctly to `docker node ls`).
* [CS-377][] Allow editing of command definitions through the Admin UI. In order to make this work, a few fields have to be edited out of the command definition to prevent an ID conflict.
* [CS-368][] Add date filtering and sorting to container history table.
* [CS-381][] Improve layout of images panel in Admin UI
* [CS-383][] Allow command wrappers to use the context `"site"` to run at a site-wide context. Unlike wrappers with other datatype-specific contexts, these will not receive any object as an external input at runtime.

[CS-1]: https://issues.xnat.org/browse/CS-1
[CS-368]: https://issues.xnat.org/browse/CS-368
[CS-377]: https://issues.xnat.org/browse/CS-377
[CS-381]: https://issues.xnat.org/browse/CS-381
[CS-383]: https://issues.xnat.org/browse/CS-383

### Bugfixes

* [CS-382][] Fix command update API

[CS-382]: https://issues.xnat.org/browse/CS-382

## 1.2.1

[Released 2017-09-06](https://github.com/NrgXnat/container-service/releases/tag/1.2.1).

### Features

* [CS-343][] Whenever a container is launched, create a workflow. Keep the status updated as the container's status changes. This allows us to piggyback on the existing workflow UI (history table, alert banner, etc.) to display container status.
* [CS-359][] Docker server is now stored in a hibernate table rather than as a prefs bean. This should ease a possible future transition to multiple docker servers.
* [CS-346][] Project owners that have set new default run-time settings for command configurations can reset those settings to the site-wide defaults.
* [CS-374][] Add lots of properties to the xnat model objects, which are now available to use in commands:
    * Assessor
        * `project-id`
        * `session-id`
    * Session
        * `subject-id`
    * Scan
        * `project-id`
        * `session-id`
        * `frames`
        * `note`
        * `modality`
        * `quality`
        * `scanner`
        * `scanner-manufacturer`
        * `scanner-model`
        * `scanner-software-version`
        * `series-description`
        * `start-time`
        * `uid`

[CS-343]: https://issues.xnat.org/browse/CS-343
[CS-346]: https://issues.xnat.org/browse/CS-346
[CS-359]: https://issues.xnat.org/browse/CS-359
[CS-374]: https://issues.xnat.org/browse/CS-374

### Bugfixes

* [CS-349][] Assessor model objects have URLs that start with their parent session's `/experiments/{sessionId}` URL. This allows containers to be run on assessors, as long as the assessor has a defined resource directory that can be mounted.
* [CS-352][] `GET /docker/hubs` and `GET /docker/hubs/{id}` return correct `ping`.
* [CS-373][] Docker events will only be recorded in container history once.
* [CS-295][], [CS-353][] Only enable the command automation panel if commands are available to use in the container service, and only allow automations to use enabled commands.
* [CS-367][] Fix the display issues that caused long command labels to get cut off in the Actions Box "Run Containers" menu.
* [CS-351][] Don't automatically treat new image hosts as the default image host in the Admin control panel.

[CS-295]: https://issues.xnat.org/browse/CS-295
[CS-349]: https://issues.xnat.org/browse/CS-349
[CS-351]: https://issues.xnat.org/browse/CS-351
[CS-352]: https://issues.xnat.org/browse/CS-352
[CS-353]: https://issues.xnat.org/browse/CS-353
[CS-367]: https://issues.xnat.org/browse/CS-367
[CS-373]: https://issues.xnat.org/browse/CS-373

## 1.2

[Released 2017-08-18](https://github.com/NrgXnat/container-service/releases/tag/1.2).

### Features

* [CS-356][] This version, and forseeable future versions, will continue to support XNAT 1.7.3.
* Ping server on `GET /docker/server` and create
* [CS-111][], [CS-285][] Ping hub on /xapi/hubs operations and reflect that ping status in Admin UI
* [CS-62][] Special-case an error when someone wants to POST to /images/save but instead GETs /images/save.
* [CS-215][] POST /xapi/docker/pull will now return a 404 rather than a 500 when it cannot find the image you are attempting to pull
* [CS-318][] Containers returned from `GET /xapi/containers` and `GET /xapi/containers/{id}` now include top-level `status` property. This is mostly equal to the status in the most recent history item on the container, except for mapping the docker event statuses to more user-friendly statuses.
    * `created` -> `Created`
    * `started` -> `Running`
    * `die` -> `Done`
    * `kill` -> `Killed`
    * `oom` -> `Killed (Out of memory)`
* [CS-227][] Container inputs are no longer recorded with type `wrapper`. Now all wrapper inputs will be recorded as either `wrapper-external` or `wrapper-derived`. Containers launched and recorded before this version will still have inputs of type `wrapper`; we can't migrate them because information has been lost.
* [CS-145][], [CS-146][] Add convenience functions to the UI to enable / disable all commands in a site or in a project
* [CS-256][], [CS-307][] Force users to confirm delete request on an image that has had containers run in that XNAT
* [CS-205][], [CS-281][] Allow admins to view container logs directly in the container history table

[CS-62]: https://issues.xnat.org/browse/CS-62
[CS-111]: https://issues.xnat.org/browse/CS-111
[CS-145]: https://issues.xnat.org/browse/CS-145
[CS-146]: https://issues.xnat.org/browse/CS-146
[CS-205]: https://issues.xnat.org/browse/CS-205
[CS-215]: https://issues.xnat.org/browse/CS-215
[CS-227]: https://issues.xnat.org/browse/CS-227
[CS-256]: https://issues.xnat.org/browse/CS-256
[CS-281]: https://issues.xnat.org/browse/CS-281
[CS-285]: https://issues.xnat.org/browse/CS-285
[CS-307]: https://issues.xnat.org/browse/CS-307
[CS-318]: https://issues.xnat.org/browse/CS-318
[CS-356]: https://issues.xnat.org/browse/CS-356

### Bugfixes

* [CS-263][] When finalizing a container, check if user can edit parent before creating new resource. (Sorry, I thought I had already done this.)
* [CS-347][] Prevent project settings from overwritingn site-wide preferences for command configurations
* [XXX-60][], [XXX-61][] Prevent users from launching containers with input values containing strings that could be used for arbitrary code execution:

        ;
        `
        &&
        ||
        (
* [CS-349][] Better handle `/experiment/{assessor id}` URIs as inputs for assessor external inputs. (They will not be able to mount files, though. See [CS-354])
* [CS-350][] More reliably find assessor directories to mount

* [CS-337][] Fail more gracefully when user tries to use a docker host url with no scheme. An error message is logged that is not super helpful right now, but will become a little bit more helpful in a future version once [spotify/docker-client#870][] is merged and released.
* [CS-351][] Fix a bug with the "Default Image Host" toggle
* [CS-279][] Explicitly restrict access to container launch UI to project users with edit permissions (i.e. owners and members)
* [CS-297][] Fix the ability to delete a command configuration. Improve appearance of "Delete" buttons throughout the admin UI
* [CS-293][] Remove non-functional "Delete" buttons from instances in the UI where it was not supported
* [CS-341][] `GET /{some identifiers}/launch` returns `user-settable` boolean property on inputs
* [CS-339][], [CS-340][] Fix faulty display and handling of non-user-settable inputs in container launch UI
* [CS-274][] Improve functioning of Project opt-in settings.
* [CS-212][] Improve display and readability of error messages throughout
* [CS-317][], [CS-273][] Taxonomy cleanup in UI for consistency

[CS-212]: https://issues.xnat.org/browse/CS-212
[CS-263]: https://issues.xnat.org/browse/CS-263
[CS-273]: https://issues.xnat.org/browse/CS-273
[CS-274]: https://issues.xnat.org/browse/CS-274
[CS-279]: https://issues.xnat.org/browse/CS-279
[CS-293]: https://issues.xnat.org/browse/CS-293
[CS-297]: https://issues.xnat.org/browse/CS-297
[CS-317]: https://issues.xnat.org/browse/CS-317
[CS-337]: https://issues.xnat.org/browse/CS-337
[CS-339]: https://issues.xnat.org/browse/CS-339
[CS-340]: https://issues.xnat.org/browse/CS-340
[CS-341]: https://issues.xnat.org/browse/CS-341
[CS-347]: https://issues.xnat.org/browse/CS-347
[CS-349]: https://issues.xnat.org/browse/CS-349
[CS-350]: https://issues.xnat.org/browse/CS-350
[CS-351]: https://issues.xnat.org/browse/CS-351
[CS-354]: https://issues.xnat.org/browse/CS-354
[XXX-60]: https://issues.xnat.org/browse/XXX-60
[XXX-61]: https://issues.xnat.org/browse/XXX-61
[spotify/docker-client#870]: https://github.com/spotify/docker-client/pull/870

## 1.1

[Released 2017-08-04](https://github.com/NrgXnat/container-service/releases/tag/1.1).

### Features

* Integrated UI container launcher controls with the [Selectable Table plugin](https://bitbucket.org/xnatdev/selectable-table-plugin) to allow users to run containers on individual scans or multiple selected scans in bulk.
* Added master controls in the Admin UI and Project Settings UI to enable and disable all available commands for individual projects and at the site-wide level.
* [CS-286][] Remove unused enable-all/disable-all APIs
* [CS-288][] Project enabled API now returns an object with project enabled boolean, project name, and command enabled boolean.
* Change launch report format. Now instead of showing either wrapper ID or command ID + wrapper name (depending on which API you used to launch the container), the launch report always shows wrapper ID and command ID. The IDs are now formatted as integers, not strings.

[CS-286]: https://issues.xnat.org/browse/CS-286
[CS-288]: https://issues.xnat.org/browse/CS-288

### Bugfixes

* [CS-282][] (Not actually fixed yet.) The existing (unused) `/container-service/settings` API has been removed. This clears the way for the API to be refactored in the next version.
* [CS-289][] Mounting a session no longer mounts the project
* Mark command and wrapper descriptions in LaunchUi as Nullable. This prevents a potential bug that I haven't yet seen.

[CS-282]: https://issues.xnat.org/browse/CS-282
[CS-289]: https://issues.xnat.org/browse/CS-289

## 1.0

[Released 2017-07-28](https://github.com/NrgXnat/container-service/releases/tag/v1.0).

### Features

* Added Changelog
* Check if command exists before saving, rather than catching exception when it fails to save.
* [CS-205][] Add `/containers/{id}/logs` and `/containers/{id}/logs/{stdout|stderr}` APIs to fetch container logs.
* Rename wrapper input properties to remove "xnat":
    * `derived-from-xnat-input` -> `derived-from-wrapper-input`
    * `as-a-child-of-xnat-input` -> `as-a-child-of-wrapper-input`
* "Create docker server" API returns 201 on success, not 202.
* Add "directory" property to Session and Assessor model objects

[CS-205]: https://issues.xnat.org/browse/CS-205

### Bugfixes

* Add some additional null checks to services
* [CS-177][] Fix for untagged images showing as ':' in UI
* [CS-276][] Correctly track command and wrapper identifiers in LaunchReport
* Could not read containers from db. Broken by 3385972b.
* Wrapper database tables were broken after 7b9a9a28.

[CS-177]: https://issues.xnat.org/browse/CS-177
[CS-276]: https://issues.xnat.org/browse/CS-276

## 1.0-alpha.3-hotfix
[Released 2017-07-25](https://github.com/NrgXnat/container-service/releases/tag/v1.0-alpha.3-hotfix).

### Features

* [CS-242][] Add REST API to support bulk container launch UI
* Add resiliency to launcher if no default value is provided for a child input

[CS-242]: https://issues.xnat.org/browse/CS-242

### Bugfixes
* [CS-257][] Hide "Create Automation" button from project owners if they do not have admin privileges. Depends on [XNAT-5044](https://issues.xnat.org/browse/XNAT-5044) change.
* Fix: container -> container entity should use existing entity in db if it exists
* Fix: Initialize container entity when retrieving by container id

[CS-257]: https://issues.xnat.org/browse/CS-257

## 1.0-alpha.3

Let's consider this the first version for purposes of this changelog. Because we haven't kept track of what has changed from the beginning of the repo until now. You can always go dig through the commits if you are really curious.
