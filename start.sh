#!/bin/sh
set -eu
cd "$(dirname "$0")"
docker compose up -d --build
printf 'Frontend: http://localhost:8221
'
