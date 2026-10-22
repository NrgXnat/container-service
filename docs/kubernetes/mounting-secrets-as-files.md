## Mounting Kubernetes Secrets as files

On the Kubernetes backend a Command can ask for a Secret that already exists in the cluster to be
mounted into the container as files, one file per key, on a tmpfs volume. XNAT never reads the
secret value: the Container Service only puts the Secret's *name* into the Pod spec and kubelet
does the mounting. This is the mechanism behind per-project credentials for external services
(see XNAT-8703, "Project Secrets").

This complements the existing [Container Service Secrets](https://wiki.xnat.org/container-service/making-use-of-container-service-secrets)
feature, which reads a value from a Java system property and injects it as an environment variable.

### Declaring the secret in a Command

```json
"secrets": [
  {
    "source":      {"type": "k8s-secret", "identifier": "redcap", "name": "redcap-#PROJECT#", "required": false},
    "destination": {"type": "file",       "identifier": "/run/secrets/redcap"}
  }
]
```

| Field | Meaning |
|-------|---------|
| `source.identifier` | Required. Logical name (letters, digits, `_`, `.`, `-`); one entry per external system, each with its own mount path. At most 56 characters; `.` and `_` become `-` in the volume name `secret-<identifier>`, so identifiers must stay unique after that rewrite. |
| `source.name` | Optional. Overrides the site template. May use the same placeholders; a value without placeholders is used literally (it must already be a valid Kubernetes name and is not sanitised). |
| `source.required` | Optional, default `true`. See "Optional secrets". |
| `destination.identifier` | Absolute, normalised mount directory (no empty, `.` or `..` segments). Must be a subdirectory, not `/run/secrets` or `/var/run/secrets` themselves (that would hide the pod's service account token), and paths of different secrets must be unique and must not nest. |

`k8s-secret` only pairs with `file`; `system-property` only with `environment-variable`. Both kinds may appear in the
same command. Setup and wrapup commands cannot declare file secrets. Docker and Swarm refuse to launch a container
with a file secret (`File secrets are not supported on the <backend> backend`, with the lower case backend name, e.g.
`docker` or `swarm`).

### Naming

Three levels, highest first: the command's `source.name`, the backend setting `secret-name-template`, the built-in
`xnat-#PROJECT#-{identifier}`. Placeholders (case sensitive): `#PROJECT#` (the launch project; wins over an input of the
same name), `#<key>#` (a wrapper or command input replacement key, e.g. `#session#`), `{identifier}`. An unresolved
placeholder fails the launch. A rendered name is sanitised to a Kubernetes name: lower cased, characters outside `[a-z0-9.-]` become `-`,
repeated `-` are collapsed and leading or trailing `.` and `-` are stripped. The result must still be a valid RFC 1123
subdomain (for example `a..b` is rejected, not repaired); longer than 253 characters is an error, never truncated. Sanitising is many-to-one (`Proj_01` and
`proj-01` collide): provision objects under the sanitised name and keep project ids unique after sanitising.
Names are rendered when the command is resolved and stored with the container record.

### Backend settings

Administer > Plugin Settings > Container Service > Compute Backend (Kubernetes), or `POST /xapi/docker/server`:

| Setting | Default | Meaning |
|---------|---------|---------|
| `secret-volume-type` | `secret` | `secret`: a Kubernetes Secret of that name in the job's namespace. `csi`: a Secrets Store CSI volume (`secrets-store.csi.k8s.io`) whose `secretProviderClass` is that name; the value is fetched from the external store at mount time. |
| `secret-name-template` | `xnat-#PROJECT#-{identifier}` | Must contain `{identifier}`. |
| `job-service-account` | unset | Pod `serviceAccountName`. Needed when the CSI provider authenticates as the pod (IRSA on EKS). |
| `job-namespace-mode` | `single` | `single`: jobs run in the kubeconfig context namespace, as before. `per-project`: in the namespace rendered from the template below. |
| `job-namespace-template` | unset | Required in per-project mode; must contain `#PROJECT#` and no other placeholder; rendered as a DNS label (63 characters max, dots become dashes). |
| `secret-mount-timeout-seconds` | 600 | See "Failures". 0 disables the watchdog. |

Namespace and secret settings apply to new launches; running jobs keep the namespace recorded on their container. The
mount timeout is re-read every minute and applies to containers already waiting.

In single mode the Container Service identity needs, in the context namespace, jobs create/get/delete/patch/update,
pods get/list/watch, pods/log get and events get/list/watch (plus `secretproviderclasses` get in csi mode); it does
not need, and must not have, permission to read Secrets. The Container Service itself only creates, patches (unsuspend) and deletes Jobs; `get` and `update` are kept so operators can inspect and edit Jobs with the same identity, and can be dropped for a stricter policy. See `container-service-on-kubernetes-setup.md` for the Role.

### Per-project namespaces

Each project namespace must be prepared before a launch (an onboarding script should do this):
a ServiceAccount named as `job-service-account` (with the IRSA annotation in csi mode); a Role plus RoleBinding
for the Container Service's own service account (the one in its kubeconfig) with jobs
create/get/delete/patch/update, pods get/list/watch, pods/log get, events get/list/watch, and in csi mode
`secretproviderclasses` get in API group `secrets-store.csi.x-k8s.io`; if PVC mounts are configured, the archive/build or
combined PVC named as in the backend settings; and the Secret or SecretProviderClass objects. The Container Service
itself only creates, patches (unsuspend) and deletes Jobs; `get` and `update` are kept so operators can inspect and
edit Jobs with the same identity, and can be dropped for a stricter policy. The Container Service starts watching a
namespace after it created a job there; a launch into a namespace that does not exist or has no RoleBinding fails
with `Namespace "..." does not exist or the Container Service identity has no RoleBinding there` (Kubernetes evaluates
RBAC before namespace existence, so an identity with only namespace-scoped RoleBindings gets 403 for a missing
namespace too; only an identity with cluster-wide permissions gets `Namespace "..." does not exist`). In csi mode an
un-onboarded project fails earlier, at resolution (`Failed (Command resolution)`, no container record): the
SecretProviderClass check runs before Job creation and its 403 reads `Namespace "..." does not exist, or the Container
Service identity has no RoleBinding there granting get on secretproviderclasses`. Launches without a project
(site-level) fail in per-project mode. After an XNAT restart the watches are re-registered from the namespaces of
unfinished containers, so no re-onboarding is needed.

The Container Service identity never needs, and must not have, permission to read Secrets.

### Optional secrets

With `required: false`: in `secret` mode the volume is `optional`, so a missing Secret mounts an empty directory;
in `csi` mode the SecretProviderClass is looked up at resolution and a missing one is dropped (the container has no
such directory). In `csi` mode a missing SecretProviderClass of a required secret fails the launch, and so does a
lookup that cannot decide (no `get` permission, CSI driver not installed) for any secret. An object that exists but
whose value cannot be fetched (store has no such secret, access denied) is still a mount failure, handled below.

### What the container sees

With the declaration above and a Secret holding keys `client_id` and `client_secret`:

```
/run/secrets/redcap/client_id
/run/secrets/redcap/client_secret
```

The files live on tmpfs and disappear when the Pod exits. The container reads them and uses the
values itself; the Container Service never logs or stores them, and the Pod spec contains only the
Secret name.

### Failures

| Stage | Where it shows |
|-------|----------------|
| Resolution (validation, csi SecretProviderClass missing or not checkable, including a csi-mode project whose namespace is not onboarded) | Launch response and workflow `Failed (Command resolution)`, no container record |
| Job creation (namespace missing or no RoleBinding: 403; namespace missing with cluster-wide permissions: 404) | Launch response and workflow `Failed (Container launch)` |
| Mount (`FailedMount` events) | Container history; the watchdog fails the container after `secret-mount-timeout-seconds` |

At resolution and job creation the Kubernetes API errors are translated, with the original text appended as
`(Kubernetes: ...)`:

| Kubernetes API response | Shown as |
|-------------------------|----------|
| SecretProviderClass lookup 404, object not found | SecretProviderClass "NAME" does not exist in namespace "NS": check the secret name template or create the object |
| SecretProviderClass lookup 404, resource unknown | The Secrets Store CSI driver is not installed in the cluster: the secretproviderclasses resource is unknown |
| SecretProviderClass lookup 403 (namespace missing, or no RoleBinding granting get on secretproviderclasses) | Namespace "NS" does not exist, or the Container Service identity has no RoleBinding there granting get on secretproviderclasses: onboard the project (create the namespace, ServiceAccount and RoleBinding) or extend the Role bound to the Container Service's service account |
| Job create 404, namespace not found (identity with cluster-wide permissions) | Namespace "NS" does not exist: onboard the project (create the namespace, ServiceAccount and RoleBinding) |
| Job create 403 | Namespace "NS" does not exist or the Container Service identity has no RoleBinding there: onboard the project (create the namespace, ServiceAccount and RoleBinding) |

`FailedMount` events on a secret volume are translated and recorded as `<translation> [<original text>]`:

| Kubernetes / kubelet message contains | Shown as |
|---------------------------------------|----------|
| `failed to get secretproviderclass NS/NAME` | SecretProviderClass "NAME" does not exist in namespace "NS": check the secret name template or create the object |
| `secret "NAME" not found` | Secret "NAME" does not exist in namespace "NS" (the namespace is shown in per-project mode) |
| `ResourceNotFoundException` | The external secret store has no secret for SecretProviderClass "NAME": check its objectName against the store |
| `Failed to fetch secret from all regions. Verify secret exists and required permissions are granted for: OBJECT` | The external secret store refused or has no secret "OBJECT" for SecretProviderClass "NAME": verify the object exists and that the job's ServiceAccount "SA" (IRSA role) may read it (the AWS provider's text when access is denied; it carries no error code, so a missing object is not ruled out) |
| `AccessDeniedException` | ServiceAccount "SA" is not allowed to read the secret behind SecretProviderClass "NAME": check its IRSA annotation and the IAM policy (reads `The job's ServiceAccount` when `job-service-account` is unset; only for providers that surface the error code, which the AWS provider does not) |
| `provider not found` | The Secrets Store CSI provider "PROVIDER" is not installed in the cluster |
| `CSI token error` and `tokenRequests` | The Secrets Store CSI driver was installed without tokenRequests: reinstall or upgrade it with tokenRequests[0].audience=sts.amazonaws.com |

Other messages are recorded unchanged. Repeated kubelet retries whose message differs only in the AWS `RequestID` are
recorded once in the container history, whether the kubelet updates the existing Event or creates a new one (the
events listed by `kubectl get events` still grow); the same warning is recorded again only after a different one for
that pod and reason, once the pod is running, or once it is deleted.

When a secret volume has been failing for longer than `secret-mount-timeout-seconds` (checked every minute), the
Container Service records `Secret mount timed out after N s: <reason>` in the history, queues finalization and
deletes the job; the workflow ends `Failed` with one notification. If finalizing is throttled, the job is kept and the
watchdog tries again on the next tick. If the job cannot be deleted, the watchdog retries
on the following ticks and gives up after 10 attempts, logging that the Kubernetes Job needs manual cleanup. Mount
failures of other volumes (PVC, hostPath) are recorded only, as before. Values never appear in history, logs, the
database or the job spec.
