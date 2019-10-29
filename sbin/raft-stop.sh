#!/usr/bin/env bash

if [ -z "${RAFT_HOME}" ]; then
    export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

. $RAFT_HOME/sbin/raft-config.sh

class="me.invkrh.raft.deploy.remote.ServerLauncherSystem"

curr_ins=`ls $RAFT_PID_DIR | grep $class | wc -l`

for ((i=0; i<$curr_ins; i++)); do
    "${RAFT_HOME}/sbin"/raft-daemon.sh stop $class $(( 1 + $i )) "$@"
done
