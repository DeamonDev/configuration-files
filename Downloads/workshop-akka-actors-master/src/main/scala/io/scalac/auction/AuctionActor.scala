package io.scalac.auction

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{Behaviors, LoggerOps}

case class Lot(lotId: String)

object AuctionActor {
  trait Command

  final case class InitializeAuction(lots: List[Lot]) extends Command
  final case class AuctionLevelBid(lotId: String, amount: Int, userId: String)
    extends AuctionActor.Command
  final case class NumberOfLots(replyTo: ActorRef[Int]) extends Command
  final case class GetCurrentlyWinningBid(lotId: String, replyTo: ActorRef[LotActor.Bid]) extends Command
  final case class Bid(lotId: String, amount: Int, userId: String)
    extends AuctionActor.Command
  final case class AddLot(lot: Lot) extends AuctionActor.Command
  final case class AddLots(lots: List[Lot]) extends AuctionActor.Command
  final case class RemoveLot(lotId: String) extends AuctionActor.Command
  final case class RemoveLots(lotsIds: List[String]) extends AuctionActor.Command
  final case class GetAllLots(replyTo: ActorRef[List[Lot]]) extends AuctionActor.Command

  trait State
  final case object Closed extends State {
    override def toString: String = "Closed"
  }
  final case object InProgress extends State {
    override def toString: String = "InProgress"
  }
  final case object Finished extends State {
    override def toString: String = "Finished"
  }

  final case class ChangeStateTo(state: State) extends Command
  final case class GetInfo(replyTo: ActorRef[Auction]) extends Command

  def apply(auctionId: String): Behaviors.Receive[Command] = apply(auctionId, List.empty, Map.empty, Closed)

  def apply(auctionId: String,
            lots: List[Lot],
            mapIdToActor: Map[String, ActorRef[LotActor.Command]],
            state: State): Behaviors.Receive[Command] =
    Behaviors.receive {

      case (ctx, InitializeAuction(lots)) =>
        val mapIdToActor = lots.map(lot => (lot.lotId -> ctx.spawn(LotActor(lot.lotId), auctionId + "-" + lot.lotId)))
          .toMap
        ctx.log.info(s"Initialized $auctionId with $lots")
        apply(auctionId, lots, mapIdToActor, Closed)

      case (ctx, Bid(lotId, amount, userId)) =>
        state match {
          case Closed =>
            ctx.log.info("You cannot bid while auction is closed")
            Behaviors.same
          case InProgress =>
            mapIdToActor(lotId) ! LotActor.Bid(amount, userId)
            Behaviors.same
          case Finished =>
            ctx.log.info("The auction is finished")
            Behaviors.same
        }

      case (ctx, AddLot(lot)) =>
        state match {
          case Closed =>
            lots.find(_.lotId == lot.lotId) match {
              case None =>
                ctx.log.info2("Adding {} to Auction {}", lot, auctionId)
                apply(
                  auctionId,
                  lots :+ lot,
                  mapIdToActor + (lot.lotId -> ctx.spawn(LotActor(lot.lotId), auctionId + "-" + lot.lotId)),
                  state)
              case Some(_) =>
                ctx.log.info("{} is in the auction already", lot)
                Behaviors.same
            }
          case InProgress =>
            ctx.log.info("Cannot add lot while action is in progress")
            Behaviors.same
          case Finished =>
            ctx.log.info("The auction is finished")
            Behaviors.same
        }

      case (ctx, RemoveLot(lotId)) =>
        state match {
          case Closed =>
            lots.find(_.lotId == lotId) match {
              case Some(_) =>
                ctx.log.info("Removed lot with lot id {}", lotId)
                apply(
                  auctionId,
                  lots.dropRight(1),
                  mapIdToActor.removed(lotId),
                  state
                )
              case None =>
                ctx.log.info("Lot with id {} not found", lotId)
                Behaviors.same
            }
          case InProgress =>
            ctx.log.info("Cannot remove lot while action is in progress")
            Behaviors.same
          case Finished =>
            ctx.log.info("The auction is finished")
            Behaviors.same
        }

      case (_, GetCurrentlyWinningBid(lotId, replyTo)) =>
        mapIdToActor(lotId) ! LotActor.GetCurrentlyWinningBid(replyTo)
        Behaviors.same

      case (ctx, ChangeStateTo(newState)) =>
        ctx.log.info(s"Changed states of $auctionId to $newState")
        apply(auctionId, lots, mapIdToActor, newState)

      case (_, NumberOfLots(replyTo)) =>
        replyTo ! lots.size
        Behaviors.same

      case (_, GetInfo(replyTo)) =>
        replyTo ! Auction(auctionId, lots, state.toString)
        Behaviors.same

      case (_, GetAllLots(replyTo)) =>
        replyTo ! lots
        Behaviors.same
    }
}