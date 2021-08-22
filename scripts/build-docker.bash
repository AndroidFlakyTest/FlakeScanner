#!/usr/bin/env bash

set -e
set -u
set -o pipefail

cat .gitignore template.dockerignore > .dockerignore
sbt docker:publishLocal
docker build -t ftd:latest .
