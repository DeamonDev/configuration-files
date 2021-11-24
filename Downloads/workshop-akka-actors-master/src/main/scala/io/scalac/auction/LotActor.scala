package io.scalac.auction

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps

object LotActor {
  trait Command

  case class Bid(offer: Int, userId: String) extends Command
  case class GetCurrentlyWinningBid(replyTo: ActorRef[Bid]) extends Command

  def apply(lotId: String): Behaviors.Receive[Command] = apply(lotId, Bid(0, ""), List())

  def apply(lotId: String, winningBid: Bid, history: List[Bid]): Behaviors.Receive[Command] =
    Behaviors.receive {

      case (ctx, Bid(offer, userId)) =>
        if (winningBid.offer < offer) {
          ctx.log.info2("{} you did that! Currently winning amount is {}", userId, offer)
          apply(lotId, Bid(offer, userId), history :+ Bid(offer, userId))
        } else {
          ctx.log.info2("{} you have to offer more than {}", userId, winningBid.offer)
          Behaviors.same
        }

      case (ctx, GetCurrentlyWinningBid(replyTo)) =>
        replyTo ! winningBid
        Behaviors.same
    }
}