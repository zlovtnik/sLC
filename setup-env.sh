#!/bin/bash
# Setup script for environment variables
# Run this script to set up your environment variables
# Usage: source setup-env.sh

set -euo pipefail

echo "Setting up environment variables for Health Check & Log Aggregator..."

# Redis Configuration
export REDIS_HOST="${REDIS_HOST:-localhost}"
export REDIS_PORT="${REDIS_PORT:-6379}"
export REDIS_DATABASE="${REDIS_DATABASE:-0}"
export REDIS_TIMEOUT="${REDIS_TIMEOUT:-5s}"
export REDIS_USERNAME="${REDIS_USERNAME:-}"

# Prompt for Redis password if not already set
if [ -z "$REDIS_PASSWORD" ]; then
    echo "Please enter your Redis password:"
    read -rs REDIS_PASSWORD
    export REDIS_PASSWORD
    echo "Redis password has been set."
else
    echo "Redis password is already set."
fi

# Prompt for Redis username if not already set (optional)
if [ -z "${REDIS_USERNAME}" ]; then
    read -rp "Enter Redis username (optional, press Enter to skip): " REDIS_USERNAME
    export REDIS_USERNAME
fi

echo "Environment variables configured:"
echo "  REDIS_HOST=$REDIS_HOST"
echo "  REDIS_PORT=$REDIS_PORT"
echo "  REDIS_DATABASE=$REDIS_DATABASE"
echo "  REDIS_TIMEOUT=$REDIS_TIMEOUT"
echo "  REDIS_USERNAME=${REDIS_USERNAME:-[EMPTY]}"
echo "  REDIS_PASSWORD=[HIDDEN]"

echo ""
echo "To make these variables permanent, add them to your shell profile (~/.bashrc, ~/.zshrc, etc.)"
echo "Example:"
echo "  echo 'export REDIS_PASSWORD=\"your-password\"' >> ~/.zshrc"