#!/usr/bin/env bash

source $(dirname "$0")/raft-env.sh

export CONF=$RAFT_HOME/config/raft.conf

java -cp $RAFT_HOME/build/akka-raft-assembly-0.1.0.jar \
me.invkrh.raft.deploy.daemon.DaemonSystem $CONF
