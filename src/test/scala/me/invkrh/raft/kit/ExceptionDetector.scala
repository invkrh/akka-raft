package me.invkrh.raft.kit

import akka.actor.{Actor, ActorRef, OneForOneStrategy, Props}
import akka.actor.SupervisorStrategy.Restart

class ExceptionDetector(actorName: String, probes: ActorRef*) extends Actor {
  override val supervisorStrategy: OneForOneStrategy = OneForOneStrategy() {
    case thr: Throwable =>
      probes foreach { _ ! thr }
      Restart // or make it configurable/controllable during the test
  }
  def receive: PartialFunction[Any, Unit] = {
    case p: Props => sender ! context.actorOf(p, actorName)
  }
}
