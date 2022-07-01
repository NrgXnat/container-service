# Private Registries with Kubernetes

Every container run by the Container Service must start from an image.
The container is essentially a "running" version of the image. The image
consists of layers of files that must be present on the node where the container will run.
If the image is not present when you initiate a container launch, the compute
backend will attempt to pull the image from some registry. For some registries and some images,
the request to pull an image must be authenticated.

The credentials for pulling images can be stored in XNAT for Docker and Docker Swarm
backends. However, at time of writing we do not support configuring registry credentials
within XNAT for Kubernetes backends. Instead, the registry credentials must be configured
within Kubernetes.

If you have configured your Kubernetes cluster with a Namespace and a ServiceAccount dedicated
to the Container Service, then configuring your Kubernetes to pull authenticated images should be easy.
You can follow the Kubernetes documentation to
[Add ImagePullSecrets to a service account](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/#add-imagepullsecrets-to-a-service-account).
We will make one small adjustment to the instructions, so I will reproduce the steps here.

First you make a Secret containing the registry credentials. (In the example this is called
`myregistrykey` but it can be named whatever you like.) The Secret must be created within the
Namespace where you run your containers.

```shell
kubectl create secret docker-registry myregistrykey \
        --namespace <namespace> \
        --docker-server=<registry url> \
        --docker-username=<registry user username> \
        --docker-password=<registry user password> \
        --docker-email=<registry user email>
```

Next, you configure the `default` service account in the namespace to use this Secret when pulling images.

```shell
kubectl patch serviceaccount default --namespace <namespace> -p '{"imagePullSecrets": [{"name": "myregistrykey"}]}'
```

Replace `myregistrykey` with whatever name you used when creating the Secret.

Note that we added the `imagePullSecrets` to the `default` service account, 
not the service account that we recommended you create for the Container Service itself.
All the containers launched _by_ the Container Service will use the `default` service account, so
this is the account that should have access to the registry credentials secret for pulling images.

Those two steps should enable all Pods launched by the Container Service to pull images with authentication.
