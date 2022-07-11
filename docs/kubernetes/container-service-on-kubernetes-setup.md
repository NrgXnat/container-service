# Container Service on Kubernetes: Setup

The audience for this doc is admins who will be enabling the Container Service to run containers on a Kubernetes cluster.

## Requirements

You must have a Kubernetes cluster, or the ability to create one.
Creating and administering a Kubernetes cluster is a big topic that is outside the scope of this document.
But we do present an example below of setting up a non-production cluster using minikube.

The XNAT archive and build directories must be persistently mounted on all nodes in your cluster.
This is currently a requirement for the Container Service to work on any compute backend, including
standalone Docker and Docker Swarm.

You must provide Kubernetes configuration file(s) in standard location(s) for the Container Service to read.
You cannot at time of writing configure access to the Kubernetes cluster from within the XNAT UI.
We will provide more detail below of what files you need and where they must go, depending on some details
of how you are running your XNAT.

The short version is this: provide a kubeconfig file at `${CATALINA_HOME}/.kube/config`. The user credentials
in that file should have, at minimum, these permissions:
```yaml
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods/log"]
  verbs: ["get"]
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
```

In the following sections we will cover how to create an account with these permissions and how to
create the kubeconfig file for the Container Service. We also present an example of configuring
a demo Kubernetes cluster with Minikube.

## Best Practices

These are not required for the Container Service to funciton on Kubernetes, but we do recommend them.

* A [Namespace](https://kubernetes.io/docs/concepts/overview/working-with-objects/namespaces/) dedicated to the Container Service
* A Service Account configured to access the resources Container Service needs within the Namespace

The best practice for administering a long-running process that needs access to the Kubernetes cluster API is
to create a Service Account for it and give that Service Account access to only the API resources it needs
using [Role Based Access Control (RBAC)](https://kubernetes.io/docs/reference/access-authn-authz/rbac/).
The Container Service is exactly that kind of long-running process, so we recommend creating a dedicated
Service Account for it.

The Container Service only requires fairly limited permissions to cluster resources. It does need full permissions on
Jobs, since those are the objects it creates to run containers. It also needs read permissions on Pods and their logs.
We do enable `get` permissions on the API health endpoints `/livez` and `/readyz` to verify the cluster connection.

If you choose not to create a dedicated Namespace or Service Account, everything should work fine. 
The Container Service will simply run in the `default` namespace with the `default` account.

## Cluster Configuration
We have written a script which is available in the container-service repo [here][setup-script]
to automate creating a Namespace and Service Account and configuring the Account with all the permissions
it needs using RBAC. The script also creates a kubeconfig file which the Container Service
can use to connect to your cluster (see section below).

If you prefer to do the configuration yourself rather than run our script, please feel free to do so.
The part of the script relevant to configuring the namespace and service account follows. Please
review it to ensure whatever configuration you set up has the same permissions.

```shell
# Values for cluster configuration
service_account="${namespace}-account"
job_role="job-admin"
job_role_binding="${service_account}-job-binding"
api_ready_role="api-ready-reader"
api_ready_role_binding="${service_account}-api-ready-binding"
service_account_secret="${service_account}-secret"

# Apply cluster configuration
echo "Configuring cluster"

kubectl apply -f - << EOF
---
apiVersion: v1
kind: Namespace
metadata:
  name: ${namespace}
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${service_account}
  namespace: ${namespace}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: ${job_role}
  namespace: ${namespace}
rules:
- apiGroups: [""]
  resources: ["pods"]
  verbs: ["get", "list", "watch"]
- apiGroups: [""]
  resources: ["pods/log"]
  verbs: ["get"]
- apiGroups: ["batch"]
  resources: ["jobs"]
  verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: ${api_ready_role}
rules:
  - nonResourceURLs: ["/readyz", "/readyz/*"]
    verbs: ["get"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: ${job_role_binding}
  namespace: ${namespace}
subjects:
- kind: ServiceAccount
  name: ${service_account}
roleRef:
  kind: Role
  name: ${job_role}
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: ${api_ready_role_binding}
subjects:
- kind: ServiceAccount
  name: ${service_account}
  namespace: ${namespace}
roleRef:
  kind: ClusterRole
  name: ${api_ready_role}
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: Secret
metadata:
  name: ${service_account_secret}
  namespace: ${namespace}
  annotations:
    kubernetes.io/service-account.name: ${service_account}
type: kubernetes.io/service-account-token
EOF
```

**Notes**  

* The `${namespace}` value is an input argument to the script and can be whatever you wish.
* The `Role` and `RoleBinding` are the minimum permissions required by the container service to function.
* The `ClusterRole` and `ClusterRoleBinding` are currently optional, but may become required in the future.
* We create a secret containing a non-expiring authentication token for the service account. This token is how the container service
  will authenticate as the service account when communicating with the cluster.

## Passing Configuration to the Container Service

In order to access the Kubernetes cluster, any client must know where to connect to the cluster and how to authenticate.
Typically, this is accomplished from outside the cluster using a kubeconfig file and from within the cluster—i.e. a
service running in a pod—using a service account token automatically mounted into the pod.

At time of writing, the Container Service does not provide any user interface for configuring
the connection to a Kubernetes cluster. Instead, it can discover the required Kubernetes configuration
from files in the standard locations, which it checks in this order:

* An environment variable `$KUBECONFIG` containing the path to a kubeconfig file.
* A kubeconfig file at `$CATALINA_HOME/.kube/config`.
* Files in `/var/run/secrets/kubernetes.io/serviceaccount`, where Kubernetes mounts credentials for service accounts in pods.
    See [Accessing the Kubernetes API from a Pod](https://kubernetes.io/docs/tasks/run-application/access-api-from-pod/) for more.
    
If you are running your XNAT outside the Kubernetes cluster, use one of the first two mechanisms to put a kubeconfig file where
XNAT can read it. If the kubeconfig file contains multiple contexts, the Container Service will use whichever one is set to the
"current context". If you use the automated script (link) to set up the Namespace and Service Account, it will create a
kubeconfig file that is set up with all the necessary information.

If you are running your XNAT in a pod within the Kubernetes cluster, you could use any of the three
mechanisms to provide credentials. If you use the third mechanism, then you must ensure that
the pod's service account has permissions
to all the required resources described in the [Best Practices](#best-practices) section.

## Creating a Kubeconfig File

Our [configuration script][setup-script] will create a kubeconfig file containing the information that
the Container Service needs to connect to the Kubernetes cluster as the proper ServiceAccount.

If you prefer to build this file yourself, or if you want to know what it does, we will go over
the contents of the script. (Technically the script linked above will both create the ServiceAccount
and create the kubeconfig file. The second part is contained in a partner script [here][kubeconfig-script].)

It takes four inputs:

1. The path to the output kubeconfig file. In the code below this is called `$kubeconfig`.
2. The namespace, called `$namespace`.
3. (Optional) The service account, called `$service_account`.
4. (Optional) The name of a secret containing a token to authenticate as the service account, called `$service_account_secret`

The values for 3 and 4 can be omitted if they are the defaults, `${namespace}-account` and `${namespace}-account-secret` respectively.

The first step is to read your kubernetes configuration to find the path to the cluster.

```shell
context=$(kubectl config current-context)
cluster=$(kubectl config view -o jsonpath='{.contexts[?(@.name=="'$context'")].context.cluster}')
server=$(kubectl config view -o jsonpath='{.clusters[?(@.name=="'$cluster'")].cluster.server}')
```

If you have multiple clusters configured, please ensure that the one you intend for the
Container Service to use is set in the "current context".

The next step is to read the token and certificate data for the ServiceAccount's Secret. Whoever
is running these commands must have permissions to get Secrets within the given Namespace.

```shell
# Service account secrets
token=$(kubectl --namespace $namespace get secret/$service_account_secret -o jsonpath='{.data.token}' | base64 --decode)

# Write certificate data to temp file
tmpdir=$(mktemp -d "${TMPDIR:-/tmp/}$(basename $0).XXXXXXXXXXXX")
ca_crt="${tmpdir}/ca.crt"
kubectl --namespace $namespace get secret/$service_account_secret -o jsonpath='{.data.ca\.crt}' | base64 --decode > $ca_crt
```

The final step is to write all the required values into a new kubeconfig file at the given location.

```shell
# This will be the name of the user and the context within the kubeconfig file
user_name="${service_account}"
context_name="${service_account}-${cluster}"

# Write data to kubeconfig file
kubectl config set-cluster "${cluster}" \
    --kubeconfig="${kubeconfig}" \
    --server="${server}" \
    --certificate-authority="${ca_crt}" \
    --embed-certs=true

kubectl config set-credentials "${user_name}" \
    --kubeconfig="${kubeconfig}" \
    --token="${token}"

kubectl config set-context "${context_name}" \
    --kubeconfig="${kubeconfig}" \
    --cluster="${cluster}" \
    --user="${user_name}" \
    --namespace="${namespace}"

kubectl config use-context "${context_name}" --kubeconfig="${kubeconfig}"
```

The kubeconfig file that is created at the given path `$kubeconfig` contains all the 
information needed to configure the Container Service's connection to the Kubernetes cluster.

# Cluster Setup Example
The first step is to have a Kubernetes cluster. There are many ways this can be accomplished,
and we won't cover every possibility here. But we will present an example setup using minikube.

The instructions to follow should be run from a terminal...
* that has access to the XNAT filesystem, such as your tomcat server, so we can create the required mounts, and
* with administrator access but not logged in as root, which is a minikube requirement.

## minikube background

[minikube](https://minikube.sigs.k8s.io/docs/) sets up a local Kubernetes cluster. It runs all the
Kubernetes infrastructure inside whatever VM manager or container runtime you have available. If you
have, say, docker running, minikube will deploy all the Kubernetes components as docker containers. 
minikube is not the best choice for production, as the entire cluster is contained within
a single VM on a single machine. In fact, according to [the minikube FAQ](https://minikube.sigs.k8s.io/docs/faq/#how-can-i-access-a-minikube-cluster-from-a-remote-network),

> minikube’s primary goal is to quickly set up local Kubernetes clusters, and therefore we strongly discourage using minikube in production...

For production clusters you could look at the Kubernetes docs on
[Deploying a cluster with kubeadm](https://kubernetes.io/docs/setup/production-environment/tools/kubeadm/create-cluster-kubeadm/),
or if you deploy to the cloud look into your cloud provider's native Kubernetes solution.
In the meantime minikube will get you started.

### Installation
We can follow the [minikube Get Started! docs](https://minikube.sigs.k8s.io/docs/start/). To install, 
we must first choose our installation platform; in our example we will install from a binary download on Linux x86-64.

    curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
    sudo install minikube-linux-amd64 /usr/local/bin/minikube

## Create a cluster

We can create the cluster using the [`minikube start` command](https://minikube.sigs.k8s.io/docs/commands/start/).
This command has many options if you want to customize the cluster configuration. For instance, 
the `--cpus` and `--memory` commands are useful to set the resources available to the cluster. The default
(and minimum) number of CPUs is 2. The default memory isn't documented; it may be system dependent. On my
example installation the default was 7900 MB.

Before we actually start the cluster, however, we will discuss file mounts. We will need to mount the
XNAT archive and build directories into the cluster. This is currently a requirement for the
Container Service to work on any compute backend. We may need to use the Path Translation settings if
the mounts are created within the cluster at a different path than where XNAT sees them outside.
But if we mount both directories into the cluster so that the paths inside and outside the cluster are the
same, we won't need to use Path Translation.

How do mounts work with minikube? We can set options to `minikube start` that will create a mount
for us, but only one. If you can create one mount point that contains both your archive and build
directories, and you are comfortable also mounting everything else contained there, this will work for you.
If you need to create multiple mount points you will have to create them after `minikube start` by
running [`minikube mount`](https://minikube.sigs.k8s.io/docs/handbook/mount/). But that command starts
a process that must continue running for the mount to be available, so you will likely want to put it
into the background and use `nohup` so it does not end when your terminal session does.

In my example the XNAT archive path is `/opt/data/archive` and the build directory is 
`/opt/data/build`. I am able to mount the `/opt/data` directory into my cluster and get
both directories that I need. That means I will mount the directories at `minikube start` time.

    $ minikube start --mount --mount-string /opt/data:/opt/data

The output from that command (for me) is:

    😄  minikube v1.25.2 on Amazon 2
    ✨  Automatically selected the docker driver. Other choices: none, ssh
    👍  Starting control plane node minikube in cluster minikube
    🚜  Pulling base image ...
    💾  Downloading Kubernetes v1.23.3 preload ...
        > preloaded-images-k8s-v17-v1...: 505.68 MiB / 505.68 MiB  100.00% 242.29 M
        > gcr.io/k8s-minikube/kicbase: 379.06 MiB / 379.06 MiB  100.00% 85.29 MiB p
    🔥  Creating docker container (CPUs=2, Memory=7900MB) ...
    🐳  Preparing Kubernetes v1.23.3 on Docker 20.10.12 ...
        ▪ kubelet.housekeeping-interval=5m
        ▪ Generating certificates and keys ...
        ▪ Booting up control plane ...
        ▪ Configuring RBAC rules ...
    🔎  Verifying Kubernetes components...
        ▪ Using image gcr.io/k8s-minikube/storage-provisioner:v5
    🌟  Enabled addons: storage-provisioner, default-storageclass
    🏄  Done! kubectl is now configured to use "minikube" cluster and "default" namespace by default

## Run a test job
We can test that our cluster is up and that we can correctly mount things by running a test pod.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: mount-test
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 600
  template:
    spec:
      containers:
        - name: mounty
          image: busybox:latest
          command: ["/bin/sh", "-c"]
          args: ["ls /datapath"]
          volumeMounts:
            - name: data
              mountPath: /datapath
      volumes:
        - name: data
          hostPath:
            path: /opt/data
      restartPolicy: Never
```

This is a definition for a pod that will mount `/opt/data` from the host into a container, and run `ls` on that mount.
Note that we have two steps of mounting indirection: one mount from our server into the Kubernetes cluster, and another
mount from a node in the cluster into the container.
If you have mounted your XNAT data at a different location within your cluster, you can change the value of
`.spec.volumes[0].hostPath.path` in the above yaml from `/opt/data` to wherever your data are mounted.

We can use this specification to start a pod by writing the above yaml to a file, say `testjob.yaml`, and running

    kubectl apply -f testjob.yaml

We expect to see the output

    job.batch/mount-test created

If we look at the logs, we should see the contents of our mounted directory. For me, that output is

    $ kubectl logs --selector="job-name=mount-test"
    archive
    build
    cache
    prearchive


[setup-script]: https://bitbucket.org/xnatdev/container-service/src/master/scripts/set_up_kubernetes.sh
[kubeconfig-script]: https://bitbucket.org/xnatdev/container-service/src/master/scripts/create_kubeconfig.sh