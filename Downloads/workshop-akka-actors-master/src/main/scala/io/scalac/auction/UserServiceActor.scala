package io.scalac.auction

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.AskPattern.{Askable}
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

object UserServiceActor {
  import AuctionServiceActor._

  trait Command

  case class GetUser(userId: String, replyTo: ActorRef[Option[User]]) extends Command
  case class GetUsers(replyTo: ActorRef[List[User]]) extends Command
  case class RegisterUser(userId: String) extends Command

  def apply: Behaviors.Receive[Command] = apply(Map())

  def apply(mapIdToUser: Map[String, ActorRef[UserActor.Command]]): Behaviors.Receive[Command] = Behaviors.receive {

    case (ctx, GetUser(userId, replyTo)) =>
      mapIdToUser.keySet.contains(userId) match {
        case true => replyTo ! Some(User(userId, List()))
        case false => replyTo ! None
      }

      Behaviors.same

    case (_, GetUsers(replyTo)) =>
      val listOfUsers = mapIdToUser.keySet.toList.map(User(_, List()))
      replyTo ! listOfUsers

      Behaviors.same

    case (ctx, RegisterUser(userId)) =>
      ctx.log.info(s"Registering User $userId")
      apply(mapIdToUser + (userId -> ctx.spawn(UserActor(userId), "user-" + userId)))

    case (_, GetUserAuctions(userId, replyTo)) =>
      mapIdToUser(userId) ! UserActor.GetUserAuctions(replyTo)

      Behaviors.same

    case (ctx, EnrollUserToAuction(userId, auctionId)) =>
      ctx.log.info(s"Enrolling User $userId to Auction $auctionId")
      mapIdToUser(userId) ! UserActor.EnrollUserToAuction(auctionId)
      apply(mapIdToUser)

    case (_, GetUserIds(replyTo)) =>
      replyTo ! mapIdToUser.keySet.toList

      Behaviors.same
  }
}
