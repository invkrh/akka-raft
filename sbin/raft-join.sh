#!/usr/bin/env bash

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

CLASS="me.invkrh.raft.deploy.Launcher"

. $RAFT_HOME/sbin/raft-env.sh

if [[ "$@" = *--help ]] || [[ "$@" = *-h ]]; then
    echo "Usage: ./sbin/raft-join.sh <master-url> [options]"
    exit 1
fi

if [ "$RAFT_SERVER_PORT" = "" ]; then
    export RAFT_SERVER_PORT="9090"
fi

MASTER=$1
shift

function start_instance {
    SERVER_NUM=$1
    shift
    PORT_NUM=$(( $RAFT_SERVER_PORT + $WORKER_NUM - 1 ))
    "${RAFT_HOME}/sbin"/raft-daemon.sh join $CLASS $SERVER_NUM \
    --port $PORT_NUM --master $MASTER "$@"
}

if [ "$RAFT_SERVER_INSTANCES" = "" ]; then
    start_instance 1 "$@"
else
    for ((i=0; i<RAFT_SERVER_INSTANCES; i++)); do
        start_instance $(( 1 + $i )) "$@"
    done
fi


