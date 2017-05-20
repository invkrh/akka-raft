#!/usr/bin/env bash

source $(dirname "$0")/raft-env.sh

export BOOTSTRAP=$RAFT_HOME/config/bootstrap
export SVRCONF=$RAFT_HOME/config/server.properties

java -cp $RAFT_HOME/build/akka-raft-assembly-0.1.0.jar \
me.invkrh.raft.deploy.daemon.DaemonSystem $BOOTSTRAP $SVRCONF
