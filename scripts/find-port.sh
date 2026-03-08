#!/bin/bash
# Find the first available port from a fixed pool (9001-9010).
# Exits with error if all ports are occupied.

for port in $(seq 9001 9010); do
    if ! nc -z localhost "$port" 2>/dev/null; then
        echo "$port"
        exit 0
    fi
done

echo "Error: all ports 9001-9010 are in use" >&2
exit 1
