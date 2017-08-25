# akka-raft

[![CircleCI](https://circleci.com/gh/invkrh/akka-raft.svg?style=shield)](https://circleci.com/gh/invkrh/akka-raft)
[![codecov](https://codecov.io/gh/invkrh/akka-raft/branch/master/graph/badge.svg?token=nH0SP8GvXq)](https://codecov.io/gh/invkrh/akka-raft)

An implementation of raft consensus algorithm based on akka

## Features:

* [x] Deployment scripts
* [x] Leader election
* [x] Log replication
* [ ] Cluster membership changes
* [ ] Log compaction
* [ ] Client interaction

## Lessons learned:

### Akka

#### Stop actor

Both stop and PoisonPill will terminate the actor and stop the message queue.
They will cause the actor to cease processing messages, send a stop call to all
its children, wait for them to terminate, then call its postStop hook.
All further messages are sent to the dead letters mailbox.

The difference is in which messages get processed before this sequence starts.
In the case of the `stop` call, the message currently being processed is completed first,
with all others discarded. When sending a PoisonPill, this is simply another message
in the queue, so the sequence will start when the PoisonPill is received.
All messages that are ahead of it in the queue will be processed first.

#### Ask pattern ActorRef

Actor path under /temp is the guardian for all short-lived system-created actors,

#### Scala Future usage

When using future callbacks, such as onComplete, onSuccess, and onFailure, inside actors you
need to carefully avoid closing over the containing actor’s reference, i.e. do not call
methods or access mutable state on the enclosing actor from within the callback. This would
break the actor encapsulation and may introduce synchronization bugs and race conditions
because the callback will be scheduled concurrently to the enclosing actor. Unfortunately
there is not yet a way to detect these illegal accesses at compile time.
