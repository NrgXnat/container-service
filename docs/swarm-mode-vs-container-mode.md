<!-- id: 41025575 -->

This document sketches out the differences in the container service backend that will be implemented to enable "swarm mode", i.e. to run containers that are scheduled by a swarm rather than directly on a docker server.

## Server configuration
Our current model has XNAT configured to communicate with a single docker server. We will not change that one-to-one connection at this time. Instead, we will add a global flag to the server configuration called "Swarm mode". The system admin can set this flag to indicate that the docker server they have configured is a swarm manager and they wish to launch their jobs as swarm services. As of version 2.0.2, the system admin can configure [swarm node constraints](https://docs.docker.com/engine/swarm/services/#placement-constraints), which can be configured to be passed along to users upon launch (e.g., if you want users to be able to select a high-memory node, etc).

When this flag is set, all jobs will be launched as swarm services; when it is not set, all jobs will be launched as containers directly on the configured server.

## Commands
Right now I anticipate no differences to the command format. Users will not need to make any changes to existing commands.

## Launching containers
The actions taken by users to launch jobs will not change, although if swarm constraints are configured to be user-settable by the system admin, then these options will be selectable as advanced options from the container launch screen.

The only difference will be in the back-end code to create the job. In the current code, after resolving the command using the runtime parameter values, the container service uses that resolved command to create and launch a container on the server. After this update, the container service will still resolve the command the same way, but will then check the global flag indicating whether the server is operating in swarm mode. If the server is not in swarm mode, then the resolved command will be used to create a container just as is done now. If the server is in swarm mode, then the resolved command will be used to create a service spec with one replica and no restart condition. In this way, the service will be used to create a task that replicates the existing mode of launching a job as a container with a finite life cycle. We are using the swarm merely as a job scheduler.
