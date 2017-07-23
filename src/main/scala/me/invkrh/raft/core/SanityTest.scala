package me.invkrh.raft.core

import akka.actor.{Actor, ActorSystem, Props}

case class Holder(arr: Array[Int])

// scalastyle:off println
class AddrChecker extends Actor {
  override def receive: Receive = {
    case h: Holder =>
      println("In: " + h.toString)
      println("In: " + h.arr.toString)
      println("Before: " + h.arr.toList)
      Thread.sleep(5000)
      println("After: " + h.arr.toList)
      context.system.terminate()
  }

}
object SanityTest extends App {
  val system = ActorSystem("SanityTest")

  val array = Array(1, 2, 3, 4)
  val holder = Holder(array)
  println("Out: " + holder.toString)
  println("Out: " + holder.arr.toString)
  println("Initial: " + holder.arr.toList)

  val checker = system.actorOf(Props(new AddrChecker))

  checker ! holder
  Thread.sleep(3000)
  array(2) = 100
}
// scalastyle:on println
