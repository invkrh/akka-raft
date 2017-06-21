#!/usr/bin/env bash

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

CLASS="me.invkrh.raft.deploy.remote.LauncherRemoteSystem"

. $RAFT_HOME/sbin/raft-env.sh

if [ "$RAFT_SERVER_INSTANCES" = "" ]; then
    "${RAFT_HOME}/sbin"/raft-daemon.sh stop $CLASS 1 "$@"
else
    for ((i=0; i<$RAFT_SERVER_INSTANCES; i++)); do
        "${RAFT_HOME}/sbin"/raft-daemon.sh stop $CLASS $(( 1 + $i )) "$@"
    done
fi
