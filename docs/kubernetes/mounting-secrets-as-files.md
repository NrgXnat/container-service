## Mounting Kubernetes Secrets as files

On the Kubernetes backend a Command can ask for a Secret that already exists in the cluster to be
mounted into the container as files, one file per key, on a tmpfs volume. XNAT never reads the
secret value: the Container Service only puts the Secret's *name* into the Pod spec and kubelet
does the mounting. This is the mechanism behind per-project credentials for external services
(see XNAT-8703, "Project Secrets").

This complements the existing [Container Service Secrets](https://wiki.xnat.org/container-service/making-use-of-container-service-secrets)
feature, which reads a value from a Java system property and injects it as an environment variable.

### Declaring the secret in a Command

Add an entry to the Command's `secrets` list with the new source and destination types:

```json
"secrets": [
  {
    "source":      {"type": "k8s-secret", "identifier": "redcap"},
    "destination": {"type": "file",       "identifier": "/run/secrets/redcap"}
  }
]
```

* `source.type` `k8s-secret` — the secret is a Kubernetes Secret object. No value is obtained by XNAT.
* `source.identifier` — a logical name. It is combined with the launch context by the site template
  (below) to produce the real Secret name. A command may declare several entries with different
  identifiers (for example `redcap` and `openspecimen`) mounted at different paths.
* `source.name` (optional) — an explicit Secret name that bypasses the template.
* `destination.type` `file` — mount as files.
* `destination.identifier` — the directory inside the container. Each key of the Secret becomes a
  file in that directory; the mount is read-only. Use a dedicated subdirectory such as
  `/run/secrets/<identifier>`, never `/run/secrets` itself: Kubernetes mounts the pod's service
  account token under `/var/run/secrets/kubernetes.io/serviceaccount`, and a read-only mount at
  `/run/secrets` makes that fail with `runc create failed ... make mountpoint`.

The Docker and Docker Swarm backends refuse to launch a container that declares a `file`
destination, with the message `File secrets are not supported on the Docker backend`.

### Secret naming template

The Secret name is rendered from a site-level template and then sanitised into a valid Kubernetes
object name (lower case, `[a-z0-9.-]`, no leading or trailing dash):

| Placeholder    | Replaced with                                   |
|----------------|-------------------------------------------------|
| `#PROJECT#`    | The XNAT project the container is launched in   |
| `{identifier}` | The `source.identifier` from the Command         |

The default template is `xnat-#PROJECT#-{identifier}`, so a command declaring `redcap` launched in
project `Proj_01` mounts the Secret `xnat-proj-01-redcap`. Whoever provisions Secrets in the cluster
(or SecretProviderClass objects, see below) must follow the same convention.

### Site configuration

Until these are exposed as Compute Backend preferences they are read from Java system properties,
set for example in Tomcat's `CATALINA_OPTS`:

| System property                   | Default                       | Meaning |
|-----------------------------------|-------------------------------|---------|
| `xnat.cs.k8s.secretNameTemplate`  | `xnat-#PROJECT#-{identifier}` | Template above |
| `xnat.cs.k8s.secretVolumeType`    | `secret`                      | `secret` mounts a Kubernetes Secret object by name. `csi` mounts a Secrets Store CSI volume (`secrets-store.csi.k8s.io`) whose `secretProviderClass` attribute is the rendered name, for clusters that pull secrets from an external store such as AWS Secrets Manager or HashiCorp Vault |
| `xnat.cs.k8s.serviceAccountName`  | unset                         | If set, applied as the Pod's `serviceAccountName`. Needed when the CSI provider authenticates with the Pod's identity (for example IRSA on EKS) |

The Container Service's own Kubernetes identity needs permission to create, get, list, watch, delete,
patch and update Jobs (jobs are created suspended and then patched to start) and to read Pods,
Pod logs and Events in the namespace. It does **not** need, and should not be given, permission
to read Secrets: referencing a Secret from a Pod spec is enough for kubelet to mount it.

### What the container sees

With the declaration above and a Secret holding keys `client_id` and `client_secret`:

```
/run/secrets/redcap/client_id
/run/secrets/redcap/client_secret
```

The files live on tmpfs and disappear when the Pod exits. The container reads them and uses the
values itself; the Container Service never logs or stores them, and the Pod spec contains only the
Secret name.

### When the Secret is missing

Kubernetes reports a missing Secret only as a Warning Event on the Pod (`FailedMount: ... secret
"xnat-proj01-redcap" not found`); the Pod itself just stays in `ContainerCreating`. The Container
Service watches these Events and records the reason and message in the container's history, so the
cause is visible in the XNAT UI without `kubectl`. `FailedAttachVolume` and `FailedScheduling` are
surfaced the same way.
