package io.scalac.auction

import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.util.Timeout
import io.scalac.auction.AuctionServiceActor.{CreateAuction, CreateLot, EditAuction, FinishAuction, GetAllAuctions, GetAllLots, GetAllLotsById, GetAuction, GetLot, InitializeUserService, StartAuction}
import akka.http.scaladsl.Http
import akka.Done
import akka.http.scaladsl.server.{PathMatcher, Route}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import io.scalac.auction.UserServiceActor.GetUser
import spray.json.DefaultJsonProtocol._

import scala.io.StdIn
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

object AuctionServiceApp extends AuctionService {

  implicit val system: ActorSystem[AuctionServiceActor.Command] = ActorSystem(AuctionServiceActor.apply, "service")
  implicit val executionContext = system.executionContext

  val auctionServiceActor: ActorRef[AuctionServiceActor.Command] = system

  override def getLotById(lotId: String): Future[Option[Lot]] = {
    implicit val timeout: Timeout = 5.seconds
    val lotOptionFuture: Future[Option[Lot]] = auctionServiceActor.ask { ref =>
      GetLot(lotId, ref)
    }.mapTo[Option[Lot]]

    lotOptionFuture
  }

  override def getLotsByAuctionId(auctionId: String): Future[List[Lot]] = {
    implicit val timeout: Timeout = 60.seconds
    val allLotsByIdFuture: Future[List[Lot]] = auctionServiceActor.ask { ref =>
      GetAllLotsById(auctionId, ref)
    }.mapTo[List[Lot]]

    allLotsByIdFuture
  }


  override def getLots: Future[List[Lot]] = {
    implicit val timeout: Timeout = 60.seconds
    val allLotsFuture: Future[List[Lot]] = auctionServiceActor.ask { ref =>
      GetAllLots(ref)
    }.mapTo[List[Lot]]

    allLotsFuture
  }

  override def checkLot(lotId: String): Future[Option[Lot]] = {
    implicit val timeout: Timeout = 3.seconds
    val lotOptionFuture: Future[Option[Lot]] = auctionServiceActor.ask {
      ref => GetLot(lotId, ref)
    }.mapTo[Option[Lot]]

    lotOptionFuture
  }

  override def createLot(lotId: String): Unit = {
    auctionServiceActor ! CreateLot(lotId)
  }

  override def createLots(lotIds: List[String]): Unit = {
    lotIds.foreach(lot => createLot(lot))
  }

  override def createAuction(auctionId: String, lots: List[Lot]): Future[Auction] = {
    auctionServiceActor ! CreateAuction(auctionId, lots)
    implicit val timeout: Timeout = 3.seconds
    val auctionFuture: Future[Auction] = auctionServiceActor.ask {
      ref => GetAuction(auctionId, ref)
    } .mapTo[Auction]

    auctionFuture
  }

  override def startAuction(auctionId: String): Unit = auctionServiceActor ! StartAuction(auctionId)

  override def endAuction(auctionId: String): Unit = auctionServiceActor ! FinishAuction(auctionId)

  override def getAuctions: Future[List[Auction]] = {
    implicit val timeout: Timeout = 60.seconds
    val allAuctionsFuture: Future[List[Auction]] = auctionServiceActor.ask { ref =>
      GetAllAuctions(ref)
    }.mapTo[List[Auction]]

    allAuctionsFuture
  }

  override def editAuction(auctionId: String, newLots: List[Lot], lotsIdToRemove: List[String]): Unit =
    auctionServiceActor ! EditAuction(auctionId, newLots, lotsIdToRemove)


  def initializeUserService(): Unit = auctionServiceActor ! InitializeUserService

  def getUser(userId: String): Future[Option[User]] = {
    implicit val timeout: Timeout = 60.seconds
    val userOptionFuture: Future[Option[User]] = auctionServiceActor.ask { ref =>
      AuctionServiceActor.GetUser(userId, ref)
    }.mapTo[Option[User]]

    userOptionFuture
  }

  def getUsers: Future[List[User]] = {
    implicit val timeout: Timeout = 60.seconds
    val userListFuture: Future[List[User]] = auctionServiceActor.ask { ref =>
      AuctionServiceActor.GetUsers(ref)
    }.mapTo[List[User]]

    userListFuture
  }

  def getUserIds: Future[List[String]] = {
    implicit val timeout: Timeout = 3.seconds
    val userIdsFuture: Future[List[String]] = auctionServiceActor.ask {ref =>
      AuctionServiceActor.GetUserIds(ref)
    }.mapTo[List[String]]

    userIdsFuture
  }

  def getUsers_v2: Future[List[User]] = { // not so nice, since uses Await...
    implicit val timeout: Timeout = 30.seconds

    val userIds = Await.result(getUserIds, 5.seconds)
    val listOfUsers = userIds.map(userId => User(userId, Await.result(getUserAuctions(userId), 5.seconds)))

    Future(listOfUsers)
  }

  def registerUser(userId: String): Unit = auctionServiceActor ! AuctionServiceActor.RegisterUser(userId)

  def getUserAuctions(userId: String): Future[List[String]] = {
    implicit val timeout: Timeout = 3.seconds
    val userAuctionsFuture: Future[List[String]] = auctionServiceActor.ask { ref =>
      AuctionServiceActor.GetUserAuctions(userId, ref)
    }.mapTo[List[String]]

    userAuctionsFuture
  }

  def enrollUserToAuction(userId: String, auctionId: String) =
    auctionServiceActor ! AuctionServiceActor.EnrollUserToAuction(userId, auctionId)

  def checkWhetherUserIsEnrolledOnAuction(userId: String, auctionId: String): Future[Boolean] =
    getUserAuctions(userId).map(auctions => auctions.contains(auctionId))

  def checkWhetherAuctionIsInProgress(auctionId: String): Future[Boolean] ={
    implicit val timeout: Timeout = 3.seconds
    val auctionStatusFuture: Future[Boolean] = auctionServiceActor.ask { ref =>
      AuctionServiceActor.GetAuction(auctionId, ref)
    }.mapTo[Auction].map(auction => auction.state == "InProgress")

    auctionStatusFuture
  }



  implicit val lotFormat = jsonFormat1(Lot)
  implicit val actionFormat = jsonFormat3(Auction)
  implicit val editAuctionFormat = jsonFormat3(EditAuctionRequest)
  implicit val auctionRequestFormat = jsonFormat2(CreateAuctionRequest)
  implicit val startAuctionRequestFormat = jsonFormat1(StartAuctionRequest)
  implicit val endAuctionRequestFormat = jsonFormat1(EndAuctionRequest)
  implicit val createLotRequest = jsonFormat1(CreateLotRequest)
  implicit val createLotsRequest = jsonFormat1(CreateLotsRequest)

  implicit val registerUserRequest = jsonFormat1(RegisterUserRequest)
  implicit val userFormat = jsonFormat2(User)
  implicit val enrollUserOnAuctionRequest = jsonFormat2(EnrollUserOnAuctionRequest)


  def main(args: Array[String]): Unit = {

    initializeUserService()

    val route =
      concat(
        path("create-auction") {
          post {
            entity(as[CreateAuctionRequest]) { auctionRequest =>
              createAuction(auctionRequest.auctionId, auctionRequest.lots)
              complete("auction-created")
            }
          }
        },
        path("create-lot") {
          post {
            entity(as[CreateLotRequest]) { createLotRequest =>
              val maybeLot: Future[Option[Lot]] = checkLot(createLotRequest.lotId)

              onSuccess(maybeLot) {
                case Some(lot) =>
                  complete(s"Cannot add $lot since it is in the auction system already")
                case None =>
                  createLot(createLotRequest.lotId)
                  complete(s"Lot with ${createLotRequest.lotId} added to the system")
              }
            }
          }
        },
        path("register-user") {
          post {
            entity(as[RegisterUserRequest]) { registerUserRequest =>
              val maybeUser: Future[Option[User]] = getUser(registerUserRequest.userId)

              onSuccess(maybeUser) {
                case Some(user) =>
                  complete(s"User ${user.userId} is already in the system")
                case None =>
                  registerUser(registerUserRequest.userId)
                  complete(s"Registered user ${registerUserRequest.userId}")
              }
            }
          }

        },
        path("enroll-user-on-auction") {
          post {
            entity(as[EnrollUserOnAuctionRequest]) { enrollUserOnAuctionRequest =>
              val maybeOnAuction: Future[Boolean] =
                checkWhetherUserIsEnrolledOnAuction(enrollUserOnAuctionRequest.userId, enrollUserOnAuctionRequest.auctionId)

              onSuccess(maybeOnAuction) {
                case true =>
                  complete(s"User ${enrollUserOnAuctionRequest.userId} is already registered on auction ${enrollUserOnAuctionRequest.auctionId}")
                case false =>
                  enrollUserToAuction(enrollUserOnAuctionRequest.userId, enrollUserOnAuctionRequest.auctionId)
                  complete(s"Enrolled ${enrollUserOnAuctionRequest.userId} to auction ${enrollUserOnAuctionRequest.auctionId}")
              }
            }
          }
        },
        path("edit-auction") {
          post {
            entity(as[EditAuctionRequest]) { editAuctionRequest =>
              editAuction(editAuctionRequest.auctionId, editAuctionRequest.lotsToAdd, editAuctionRequest.lotIdsToRemove)
              complete("auction-edited")
            }
          }
        },
        path("lots") {
          get {
            complete(getLots)
          }
        },
        path("users") {
          get {
            complete(getUsers_v2)
          }
        },
        path("auctions") {
          get {
            complete(getAuctions)
          }
        },
        get {
          pathPrefix("auctions" / LongNumber) { auctionId =>
            complete(getLotsByAuctionId(auctionId.toString))
          }
        },
        get {
          pathPrefix("user-auctions" / Segment) { userId: String =>
            complete(getUserAuctions(userId))
          }
        },
        get {
          pathPrefix("lots" / LongNumber) { lotId =>
            val futureOptionLot: Future[Option[Lot]] = getLotById(lotId.toString)

            onSuccess(futureOptionLot) {
              case Some(lot) => complete(lot)
              case None => complete(StatusCodes.NotFound)
            }
          }
        },
        path("start-auction") {
          post {
            entity(as[StartAuctionRequest]) { startAuctionRequest =>
              startAuction(startAuctionRequest.auctionId)
              complete("auction-started")
            }
          }
        },
        path("end-auction") {
          post {
            entity(as[EndAuctionRequest]) { endAuctionRequest =>
              endAuction(endAuctionRequest.auctionId)
              complete("auction-finished")
            }
          }
        }
      )

    val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)
    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine()
    bindingFuture
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}