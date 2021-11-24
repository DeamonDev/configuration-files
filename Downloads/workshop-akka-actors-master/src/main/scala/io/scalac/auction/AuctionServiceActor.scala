package io.scalac.auction
import AuctionActor._

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.{Behaviors, LoggerOps}

object AuctionServiceActor {
  trait Command

  case class Bid(auctionId: String, lotId: String, offer: Int, userId: String) extends Command
  case class CreateAuction(auctionId: String, lots: List[Lot]) extends Command
  case class GetAuction(auctionId: String, replyTo: ActorRef[Auction]) extends Command
  case class GetAuctions(auctionIds: List[String], replyTo: ActorRef[List[Auction]]) extends Command
  case class GetAllAuctions(replyTo: ActorRef[List[Auction]]) extends Command
  case class EditAuction(auctionId: String, lotsToAdd: List[Lot], lotsIdsToRemove: List[String]) extends Command
  case class StartAuction(auctionId: String) extends Command
  case class FinishAuction(auctionId: String) extends Command
  case class GetNumberOfAuctions(replyTo: ActorRef[Int]) extends Command
  case class CreateLot(lotId: String, onAuction: Boolean = false) extends Command
  case class CreateLots(lotIds: List[String]) extends Command
  case class GetLot(lotId: String, replyTo: ActorRef[Option[Lot]]) extends Command
  case class GetAllLots(replyTo: ActorRef[List[Lot]]) extends Command
  case class GetAllLotsById(auctionId: String, replyTo: ActorRef[List[Lot]]) extends Command


  // User Service Commands
  case object InitializeUserService extends Command
  case class RegisterUser(userId: String) extends Command
  case class GetUser(userId: String, replyTo: ActorRef[Option[User]]) extends Command
  case class GetUsers(replyTo: ActorRef[List[User]]) extends Command
  case class GetUserAuctions(userId: String, replyTo: ActorRef[List[String]]) extends Command with UserServiceActor.Command
  case class GetUserIds(replyTo: ActorRef[List[String]]) extends Command with UserServiceActor.Command
  case class EnrollUserToAuction(userId: String, auctionId: String) extends Command with UserServiceActor.Command


  def apply(): Behaviors.Receive[Command] = apply(List(), Map(), Map(), List(), None)

  def apply(auctionIds: List[String],
            mapIdToAuction: Map[String, ActorRef[AuctionActor.Command]],
            mapIdToAuctionInfo: Map[String, Auction],
            currentLots: List[(String, Boolean)],
            userServiceOption: Option[ActorRef[UserServiceActor.Command]]): Behaviors.Receive[Command] =
    Behaviors.receive {

      case (_, Bid(auctionId, lotId, offer, userId)) =>
        mapIdToAuction(auctionId) ! AuctionActor.Bid(lotId, offer, userId)
        Behaviors.same

      case (ctx, CreateLot(lotId, onAuction)) =>
        ctx.log.info(s"Added lot $lotId to the system")
        apply(auctionIds, mapIdToAuction, mapIdToAuctionInfo, currentLots :+ (lotId, onAuction), userServiceOption)

      case (_, GetLot(lotId, replyTo)) =>
        currentLots.map(_._1).contains(lotId) match {
          case true => replyTo ! Some(Lot(lotId))
          case false => replyTo ! None
        }

        Behaviors.same

      case (ctx, CreateLots(lotIds)) =>
        lotIds.foreach(lotId => ctx.self ! CreateLot(lotId))
        Behaviors.same

      case (ctx, GetAllLots(replyTo)) =>
        replyTo ! currentLots.map(x => Lot(x._1))
        Behaviors.same

      case (_, GetAllLotsById(auctionId, replyTo)) =>
        mapIdToAuction(auctionId) ! AuctionActor.GetAllLots(replyTo)
        Behaviors.same

      case (ctx, CreateAuction(auctionId, lots)) =>
        val registeredLotsNotOnAuction = currentLots.filter(lot => lot._2 == false).map(_._1)

        val unregisteredLots = lots.filter(lot => !currentLots.map(_._1).contains(lot.lotId))
        unregisteredLots.foreach { lot =>
          ctx.log.info(s"Lot $lot is not in the system, adding it")
          ctx.self ! CreateLot(lot.lotId, true)
        }

        val filteredLots = unregisteredLots ++ lots.filter(lot => registeredLotsNotOnAuction.contains(lot.lotId))

        val auctionActor = ctx.spawn(AuctionActor(auctionId), "auction-" + auctionId)
        auctionActor ! InitializeAuction(filteredLots)
        apply(auctionIds :+ auctionId,
          mapIdToAuction + (auctionId -> auctionActor),
          mapIdToAuctionInfo + (auctionId -> Auction(auctionId, filteredLots, "Closed")),
          currentLots,
          userServiceOption
        )

      case (_, GetNumberOfAuctions(replyTo)) =>
        replyTo ! auctionIds.size
        Behaviors.same

      case (_, GetAuction(auctionId, replyTo)) =>
        val auction = mapIdToAuctionInfo(auctionId)
        replyTo ! auction

        Behaviors.same

      case (_, GetAuctions(auctionIds, replyTo)) =>
        val listOfAuctions = mapIdToAuctionInfo.filter(e => auctionIds.contains(e._1)).toList.map(x => x._2)
        replyTo ! listOfAuctions

        Behaviors.same

      case (_, EditAuction(auctionId, lotsToAdd, lotsIdsToRemove)) =>
        val auctionActor = mapIdToAuction(auctionId)
        lotsToAdd.foreach(lot => auctionActor ! AddLot(lot))
        lotsIdsToRemove.foreach(lotId => auctionActor ! RemoveLot(lotId))

        // update our AuctionInfo map
        val editedAuction = mapIdToAuctionInfo(auctionId)

        val newCurrentLots =
          (currentLots ++ lotsToAdd.map(lot => (lot.lotId, true))).filter(x => !lotsIdsToRemove.contains(x._1))

        apply(
          auctionIds,
          mapIdToAuction,
          mapIdToAuctionInfo + (auctionId -> editedAuction.addAndRemoveLots(lotsToAdd, lotsIdsToRemove)),
          newCurrentLots,
          userServiceOption)

      case (_, GetAllAuctions(replyTo)) =>
        val listOfAllAuctions = mapIdToAuctionInfo.toList.map(_._2)
        replyTo ! listOfAllAuctions

        Behaviors.same

      case (_, StartAuction(auctionId)) =>
        val auctionActor = mapIdToAuction(auctionId)
        auctionActor ! ChangeStateTo(InProgress)

        val editedAuction = mapIdToAuctionInfo(auctionId)

        apply(
          auctionIds,
          mapIdToAuction,
          mapIdToAuctionInfo + (auctionId -> editedAuction.changeState("InProgress")),
          currentLots,
          userServiceOption
        )


      case (ctx, InitializeUserService) =>
        val userService = ctx.spawn(UserServiceActor.apply, "user-service")
        ctx.log.info("Initialized User Service")
        apply(auctionIds, mapIdToAuction, mapIdToAuctionInfo, currentLots, Some(userService))

      case (ctx, GetUser(userId, replyTo)) =>
        val userService = userServiceOption.get
        userService ! UserServiceActor.GetUser(userId, replyTo)

        Behaviors.same

      case (_, GetUsers(replyTo)) =>
        val userService = userServiceOption.get
        userService ! UserServiceActor.GetUsers(replyTo)

        Behaviors.same

      case (ctx, RegisterUser(userId)) =>
        val userService = userServiceOption.get
        userService ! UserServiceActor.RegisterUser(userId)

        Behaviors.same

      case (_, GetUserAuctions(userId, replyTo)) =>
        val userService = userServiceOption.get
        userService ! GetUserAuctions(userId, replyTo)

        Behaviors.same

      case (ctx, EnrollUserToAuction(userId, auctionId)) =>
        val userService = userServiceOption.get
        userService ! EnrollUserToAuction(userId, auctionId)

        Behaviors.same

      case (_, GetUserIds(replyTo)) =>
        val userService = userServiceOption.get
        userService ! GetUserIds(replyTo)

        Behaviors.same
    }
}