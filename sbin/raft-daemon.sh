#!/usr/bin/env bash

source $(dirname "$0")/raft-env.sh

if [ -z "${RAFT_HOME}" ]; then
  export RAFT_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi

if [ -z "${RAFT_CONF}" ]; then
  export RAFT_CONF="$RAFT_HOME/config/raft.conf"
fi

export MAIN_CLASS=me.invkrh.raft.deploy.Daemon

java -cp $RAFT_HOME/build/akka-raft-assembly-0.1.0.jar $MAIN_CLASS $RAFT_CONF $@
