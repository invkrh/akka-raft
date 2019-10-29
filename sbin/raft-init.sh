#!/usr/bin/env bash
# TODO: simply scripts just start and stop, no more
if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

. $RAFT_HOME/sbin/raft-config.sh

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

class="me.invkrh.raft.deploy.remote.ClusterInitiatorSystem"

"${RAFT_HOME}/sbin"/raft-daemon.sh init $class 1 \
--host $RAFT_INITIATOR_HOST --port $RAFT_INITIATOR_PORT
