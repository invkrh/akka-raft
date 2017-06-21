#!/usr/bin/env bash

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

CLASS="me.invkrh.raft.deploy.remote.ClusterInitiatorSystem"

. $RAFT_HOME/sbin/raft-env.sh

if [[ "$@" = *--help ]] || [[ "$@" = *-h ]]; then
    echo "Usage: ./sbin/raft-init.sh [options]"
    exit 1
fi

if [ "$RAFT_INITIATOR_HOST" = "" ]; then
    export RAFT_INITIATOR_HOST="`hostname -f`"
fi

if [ "$RAFT_INITIATOR_PORT" = "" ]; then
    export RAFT_INITIATOR_PORT=9090
fi

"${RAFT_HOME}/sbin"/raft-daemon.sh init $CLASS 1 \
--host $RAFT_INITIATOR_HOST --port $RAFT_INITIATOR_PORT
