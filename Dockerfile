# NOTE: This Dockerfile is meant to use with scripts/build-docker.bash
FROM ftd:intermediate-runner AS runner

LABEL maintainer="Xiao Liang"

ARG DEBIAN_FRONTEND=noninteractive

USER root
RUN apt-get update && apt-get -y install android-tools-adb
USER ftd-user
