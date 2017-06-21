#!/usr/bin/env bash

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

CLASS="me.invkrh.raft.deploy.remote.ServerLauncherSystem"

. $RAFT_HOME/sbin/raft-env.sh

if [[ "$@" = *--help ]] || [[ "$@" = *-h ]]; then
    echo "Usage: ./sbin/raft-join.sh <master-url> [options]"
    exit 1
fi

if [ "$RAFT_LAUNCHER_HOST" = "" ]; then
    export RAFT_LAUNCHER_HOST="`hostname -f`"
fi

if [ "$RAFT_LAUNCHER_PORT" = "" ]; then
    export RAFT_LAUNCHER_PORT="4545"
fi

INIT_ADDR=$1
shift

function start_instance {
    SERVER_NUM=$1
    shift
    PORT_NUM=$(( $RAFT_LAUNCHER_PORT + $SERVER_NUM - 1 ))
    "${RAFT_HOME}/sbin"/raft-daemon.sh join $CLASS $SERVER_NUM \
    --port $PORT_NUM --init $INIT_ADDR "$@"
}

if [ "$RAFT_SERVER_INSTANCES" = "" ]; then
    start_instance 1 "$@"
else
    for ((i=0; i<$RAFT_SERVER_INSTANCES; i++)); do
        start_instance $(( 1 + $i )) "$@"
    done
fi


