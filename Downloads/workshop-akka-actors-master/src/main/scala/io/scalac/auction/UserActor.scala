package io.scalac.auction

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors


object UserActor {
  trait Command

  case class EnrollUserToAuction(auctionId: String) extends Command
  case class GetUserAuctions(replyTo: ActorRef[List[String]]) extends Command
  case class GetUser(replyTo: ActorRef[User]) extends Command

  def apply(userId: String): Behaviors.Receive[Command] = apply(userId, List())

  def apply(userId: String, enrolledAuctions: List[String]): Behaviors.Receive[Command] = Behaviors.receive {

    case (_, GetUser(replyTo)) =>
      replyTo ! User(userId, enrolledAuctions)
      Behaviors.same

    case (_, EnrollUserToAuction(auctionId)) =>
      apply(userId, enrolledAuctions :+ auctionId)

    case (_, GetUserAuctions(replyTo)) =>
      replyTo ! enrolledAuctions
      Behaviors.same
  }

}
