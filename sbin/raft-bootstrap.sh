#!/usr/bin/env bash

source $(dirname "$0")/raft-env.sh

export BOOTSTRAP=$RAFT_HOME/config/bootstrap

java -cp $RAFT_HOME/build/akka-raft-assembly-0.1.0.jar \
me.invkrh.raft.deploy.bootstrap.BootstrapSystem $BOOTSTRAP $@