#!/bin/bash
# Enable IAM database authentication on an existing Cloud SQL Postgres instance.
#
# IMPORTANT: This restarts the instance (~1-2 min downtime).
# Existing password-based connections (reddit-scraper-fulcro, etc.) are NOT affected.
# It simply enables IAM as an *additional* auth method.
#
# Usage: ./gcp/enable-iam-auth.sh [--project PROJECT] [--instance INSTANCE]

set -euo pipefail

PROJECT="${1:-EXAMPLE-GCP-PROJECT-A}"
INSTANCE="${2:-postgres1}"

echo "=== Enabling IAM database authentication ==="
echo "Project:  $PROJECT"
echo "Instance: $INSTANCE"
echo ""
echo "WARNING: This will restart the instance (1-2 min downtime)."
echo "Existing password auth continues to work."
echo ""
read -p "Continue? (y/N) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
fi

gcloud sql instances patch "$INSTANCE" \
  --database-flags=cloudsql.iam_authentication=on \
  --project="$PROJECT"

echo ""
echo "Done. IAM auth enabled on $PROJECT:$INSTANCE"
echo "Verify: gcloud sql instances describe $INSTANCE --project=$PROJECT --format='yaml(settings.databaseFlags)'"
