#!/bin/sh
set -e
set -o pipefail

_usage="
Usage: $(basename $0) KUBECONFIG_OUT NAMESPACE SERVICE_ACCOUNT

This script writes a kubeconfig file to a specified location which contains
the credentials to authenticate as a specified ServiceAccount.

Requirements:
 * \`base64' must be installed
 * \`kubectl' must be installed
 * When \`kubectl' is invoked, the account must have create permissions on
     Namespaces, ServiceAccounts, Roles, ClusterRoles, and RoleBindings.
     An admin account, in short.
"

# Input
kubeconfig=${1:?$_usage}

# Help
if [ $kubeconfig = "-h" -o $kubeconfig = "--help" ]; then
    echo $_usage
    exit 0
fi

namespace=${2:?$_usage}
service_account=${3:-"${namespace}-account"}

# Create kubeconfig
mkdir -p $(dirname $kubeconfig)  # Make output directory

# Parse values out of current kubeconfig
context=$(kubectl config current-context)
cluster=$(kubectl config view -o jsonpath='{.contexts[?(@.name=="'$context'")].context.cluster}')
server=$(kubectl config view -o jsonpath='{.clusters[?(@.name=="'$cluster'")].cluster.server}')

# Service account secrets
secret_name=$(kubectl --namespace $namespace get sa $service_account -o jsonpath='{.secrets[0].name}')
token=$(kubectl --namespace $namespace get secret/$secret_name -o jsonpath='{.data.token}' | base64 --decode)

# Write certificate data to temp file
tmpdir=$(mktemp -d "${TMPDIR:-/tmp/}$(basename $0).XXXXXXXXXXXX")
ca_crt="${tmpdir}/ca.crt"
kubectl --namespace $namespace get secret/$secret_name -o jsonpath='{.data.ca\.crt}' | base64 --decode > $ca_crt

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
