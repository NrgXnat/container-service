<!-- NO UPLOAD -->
# Use case demos

## Prerequisites
These instructions will get you an XNAT with container service running at https://xnat-31.xnat.org. Throughout the text, I will refer to this XNAT root URL as ${XNAT}, so you can substitute your own different URL if you wish.

### XNAT 1.7
You need to have an XNAT 1.7 up and running. There are a lot of ways to do that; this is one way, in which .

* Clone the [xnat-web repo](https://bitbucket.org/xnatdev/xnat-web)
* Clone the [xnat-vagrant repo](https://bitbucket.org/xnatdev/xnat-vagrant)
* Create a directory on your local machine into which we can put jars that we want XNAT to run. I will call this `/path/to/plugins`.
* In `xnat-vagrant` create a file in `configs/xnat-dev/local.yaml` with the following contents:

```yaml
name: xnat
vm_ip: 10.1.7.31
shares:
  '/path/to/plugins':
    - '/data/xnat/home/plugins'
```

* Open a terminal to `xnat-vagrant`, and run the following command:

    $ ./run xnat-dev setup

* While that runs, open another terminal to `xnat-web`. Run the following command:

    $ ./gradlew war

* Create a file: `xnat-web/gradle.properties` with the following contents:

```properties
deployHost=xnat-31.xnat.org
deployPort=8080
deployContext=/
deployUser=deploy
deployPassword=deploy
```

* Once the `xnat-vagrant` setup is finished, in the `xnat-web` terminal, run the following command:

    $ ./gradlew cargoRedeployRemote

### Container service and dependencies
Right now the container service needs to be built from source. Once we have a release version, you can just download it.

Gather all the jars below into your local plugins directory `/path/to/plugins`. Then restart your XNAT 1.7.

#### Container Service jar
* Clone the [container-service repo](https://github.com/nrgXnat/container-service)
* Build the jar: `[container-service]$ ./gradlew clean jar`
* Copy the jar to your plugins directory `$ cp build/libs/containers-1.0-SNAPSHOT.jar /path/to/plugins/`.

#### Transporter jar
* Clone the [transporter repo](https://github.com/nrgXnat/xnat-transporter)
* Build the jar: `[xnat-transporter]$ ./gradlew clean jar`
* Copy the jar to your plugins directory `$ cp build/libs/transporter-1.0-SNAPSHOT.jar /path/to/plugins/`.

#### Docker client
* Download the **shaded** jar from [bintray](https://bintray.com/bintray/jcenter/org.mandas%3Adocker-client) or [maven central](http://search.maven.org/#search%7Cga%7C1%7Ccom.spotify.docker-client).

### Setup

#### Sitewide setup
If this is a new XNAT, you'll have to go through the sitewide setup. Log in and you'll be redirected to a screen with a bunch of options. The defaults are all fine, so hit "Save all".

#### Add docker server configuration to XNAT
If you aren't using the most basic docker/XNAT configuration (XNAT machine has read/write access to the docker socket in the default location) you need to tell XNAT how to reach your docker server. Use `curl` or some other HTTP client to POST a bit of JSON to XNAT:

    $ curl -u ${adminUser}:${adminPassword} -X POST -H 'Content-type: application/json' -d '{"host": "'${DOCKER_HOST}'"}' ${XNAT}/xapi/docker/server

Where `${adminUser}` and `${adminPassword}` are the credentials of an admin account, ${XNAT} is your XNAT's URL, and ${DOCKER_HOST} is your docker's URL (which could be http://... or https://... for remote docker servers, or unix://... if XNAT can read the docker socket). If your docker requires a certificate for communication, the certificate file must be readable by XNAT; change the body of the JSON that you post above to `'{"host": "'${DOCKER_HOST}'", "cert-path": "'${DOCKER_CERT_PATH}'"}'`.

## Demo: Dicom to Nifti
### Get the image
TODO The xnat/dcm2niix-scan image is not yet on Docker hub, so you can't pull it. I.e. neither of the first two options below will work yet. You need to build it yourself.

You can tell XNAT to pull the image down for you.

    $ curl -u ${adminUser}:${adminPassword} -X POST ${XNAT}/xapi/docker/images/pull?image=xnat/dcm2niix-scan

Or you can pull the image down yourself.

    $ docker pull xnat/dcm2niix-scan

Or you can build the image from [the Dockerfile](https://raw.githubusercontent.com/NrgXnat/docker-images/master/dcm2niix-scan/Dockerfile).

    $ docker build -t xnat/dcm2niix-scan .

### Add the command to XNAT
If you told XNAT to pull the image for you above, it will have imported the Command from the image labels. If not, you can manually import the Command from the image labels.

    $ curl -u ${adminUser}:${adminPassword} -X POST ${XNAT}/xapi/docker/images/save?image=xnat/dcm2niix-scan

You'll get the Command object back. Note the `id`, as we will need it for the next step.

### Set the command to run when a scan is archived
With the command id from the last step as `${commandId}`, POST to XNAT to register the command to run when a scan is archived.

    $ curl -u ${adminUser}:${adminPassword} -X POST -H 'Content-type: application/json' -d '{"commandId": "'${commandId}'", "eventType": "ScanArchived"}' ${XNAT}/xapi/commandeventmapping

### Archive a session
First make sure you have a project. You can create one on the main page of your XNAT in the navigation header: New > Project. Give it a name and ID.

Now you will need to create a session. I usually do this by uploading some sample DICOM files that I downloaded from the [OsiriX DICOM Library](http://www.osirix-viewer.com/resources/dicom-image-library/), specifically the BRAINX. I use the compressed uploader (Upload > Images > Compressed Uploader).

That will run the `dcm2niix-scan` image on all your scans. But it doesn't upload anything because that doesn't work yet.