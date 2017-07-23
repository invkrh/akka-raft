package me.invkrh.raft.core

import akka.actor.{Actor, ActorSystem, Props}

case class Holder(coll: List[Int])

// scalastyle:off println
class AddrChecker extends Actor {
  override def receive: Receive = {
    case h: Holder =>
      println("In: " + h.toString)
      println("In: " + h.coll.toString)
      println("Before: " + h.coll)
      Thread.sleep(5000)
      println("After: " + h.coll)
      context.system.terminate()
  }

}

object SanityTest extends App {
  val system = ActorSystem("SanityTest")

  var coll = List(1, 2, 3, 4)
  val holder = Holder(coll)
  println("Out: " + holder.toString)
  println("Out: " + holder.coll.toString)
  println("Initial: " + holder.coll)

  val checker = system.actorOf(Props(new AddrChecker))

  checker ! holder
  Thread.sleep(3000)
  coll = List(4, 3, 2, 1)
}
// scalastyle:on println
