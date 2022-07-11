#!/bin/sh
set -e
set -o pipefail

_usage="
Usage: $(basename $0) [NAMESPACE]

Configure Kubernetes cluster namespace and service account for Container Service

The following cluster configuration will be performed:
 * Create a namespace. You can pass an input to the script which will be used for the
    name of the Namespace, or use the default \"container-service\".
 * Create a ServiceAccount within the Namespace, named \"\${namespace}-account\".
    The Container Service will use this ServiceAccount to connect to Kubernetes.
 * Create Roles for the API permissions the Container Service needs
 * Assign those Roles to the ServiceAccount through RoleBindings.

Requirements:
 * \`kubectl' must be installed
 * When \`kubectl' is invoked, the account must have create permissions on
     Namespaces, ServiceAccounts, Roles, ClusterRoles, and RoleBindings.
     An admin account, in short.
"

# Input
namespace=${1:-"container-service"}

# Values for cluster configuration
service_account="${namespace}-account"
job_role="job-admin"
job_role_binding="${service_account}-job-binding"
api_ready_role="api-ready-reader"
api_ready_role_binding="${service_account}-api-ready-binding"
service_account_secret="${service_account}-secret"

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
