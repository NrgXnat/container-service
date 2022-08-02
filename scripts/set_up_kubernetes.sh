#!/bin/sh
set -e
set -o pipefail

_usage="
Usage: $(basename $0) KUBECONFIG_OUT [NAMESPACE]

Configure Kubernetes cluster namespace and service account for Container Service

The following cluster configuration will be performed:
 * Create a namespace. You can pass an optional input to the script which will be used for the
    name of the Namespace, or use the default \"container-service\".
 * Create a ServiceAccount within the Namespace, named \"\${namespace}-account\".
    The Container Service will use this ServiceAccount to connect to Kubernetes.
 * Create Roles for the API permissions the Container Service needs
 * Assign those Roles to the ServiceAccount through RoleBindings.

The credentials for the ServiceAccount will be written to a kubeconfig file at the path you
specify.

Requirements:
 * \`base64' must be installed
 * \`kubectl' must be installed
 * When \`kubectl' is invoked, the account must have create permissions on
     Namespaces, ServiceAccounts, Roles, ClusterRoles, and RoleBindings.
     An admin account, in short.
"

# Input
kubeconfig=${1:?$_usage}
namespace=${2:-"container-service"}

# Help
if [ $kubeconfig = "-h" -o $kubeconfig = "--help" ]; then
    echo $_usage
    exit 0
fi

# Find directory where this script is running so we can locate partner scripts.
# Taken from https://stackoverflow.com/a/53122736
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Apply cluster configuration
echo "Configuring cluster"
bash ${__dir}/configure_cluster_namespace_roles.sh "$namespace"

# Create kubeconfig
echo "Writing values to kubeconfig ${kubeconfig}"
bash ${__dir}/create_kubeconfig.sh "$kubeconfig" "$namespace"

echo "Done"
