#!/usr/bin/env bash

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

. $RAFT_HOME/sbin/raft-config.sh

if [[ "$@" = *--help ]] || [[ "$@" = *-h ]] || [[ "$#" != 1 ]] || [[ "$#" != 2 ]]; then
    echo "Usage: ./sbin/raft-join.sh <master-url> <new-instances>"
    exit 1
fi

if [ "$RAFT_LAUNCHER_HOST" = "" ]; then
    export RAFT_LAUNCHER_HOST="`hostname -f`"
fi

if [ "$RAFT_LAUNCHER_PORT" = "" ]; then
    export RAFT_LAUNCHER_PORT="4545"
fi

init_addr=$1
shift
new_ins=$1
shift

class="me.invkrh.raft.deploy.remote.ServerLauncherSystem"

function start_instance {
    SERVER_NUM=$1
    shift
    PORT_NUM=$(( $RAFT_LAUNCHER_PORT + $SERVER_NUM - 1 ))
    "${RAFT_HOME}/sbin"/raft-daemon.sh join $class $SERVER_NUM \
    --port $PORT_NUM --init $init_addr "$@"
}

curr_ins=`ls $RAFT_PID_DIR | grep $class | wc -l`


if [ "$new_ins" = "" ]; then
    start_instance $(( curr_ins + 1 )) "$@"
else
    for ((i=0; i<$new_ins; i++)); do
        start_instance $(( curr_ins + 1 + $i )) "$@"
    done
fi


