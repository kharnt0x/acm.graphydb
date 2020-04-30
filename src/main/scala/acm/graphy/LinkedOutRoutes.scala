package acm.graphy

import scala.concurrent.Future
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import GraphNode.Relationship
import spray.json.JsonFormat

object LinkedOutRoutes {

  final case class AddRelationship(entityId: String, relationId: String, relationType: String, relationTarget: String)

  final case class AddCompany(entityId: String, company: Company)

  final case class AddPerson(entityId: String, person: Person)

  final case class RemoveRelationship(entityId: String, relationId: String)

}

case class Company(name: String, location: String) extends CborSerializable

case class Person(name: String, age: Int) extends CborSerializable


class LinkedOutRoutes()(implicit system: ActorSystem[_]) {

  implicit private val timeout: Timeout =
    Timeout.create(system.settings.config.getDuration("linkedout.askTimeout"))
  private val sharding = ClusterSharding(system)

  import LinkedOutRoutes._
  import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
  import akka.http.scaladsl.server.Directives._
  import JsonFormats._

  val linkedOut: Route =
    pathPrefix("linkedout") {
      concat(
        pathPrefix("company") {
          concat(
            post {
              entity(as[AddCompany]) { data =>
                val entityRef = sharding.entityRefFor(GraphNode.EntityKey[Company], data.entityId)
                val reply: Future[GraphNode.Confirmation[Company]] =
                  entityRef.ask(GraphNode.AddData(data.company, _))
                onSuccess(reply) {
                  case GraphNode.Accepted(summary) =>
                    complete(StatusCodes.OK -> summary)
                  case GraphNode.Rejected(reason) =>
                    complete(StatusCodes.BadRequest, reason)
                }
              }
            },
            pathPrefix(Segment) { entityId =>
              concat(get {
                val entityRef = sharding.entityRefFor(GraphNode.EntityKey[Company], entityId)
                onSuccess(entityRef.ask(GraphNode.Get[Company])) { summary =>
                  if (summary.relationships.isEmpty && summary.data.isEmpty) complete(StatusCodes.NotFound)
                  else complete(StatusCodes.OK -> summary)
                }
              })
            },
            pathPrefix("relationship") {
              concat(
                post {
                  entity(as[AddRelationship]) { data =>
                    val entityRef = sharding.entityRefFor(GraphNode.EntityKey[Company], data.entityId)
                    val reply: Future[GraphNode.Confirmation[Company]] =
                      entityRef.ask(GraphNode.AddRelationship(data.relationId, data.relationType, data.relationTarget, _))
                    onSuccess(reply) {
                      case GraphNode.Accepted(summary) =>
                        complete(StatusCodes.OK -> summary)
                      case GraphNode.Rejected(reason) =>
                        complete(StatusCodes.BadRequest, reason)
                    }
                  }
                }
              )
            }
          )
        },
        pathPrefix("person") {
          concat(post {
            entity(as[AddPerson]) { data =>
              val entityRef = sharding.entityRefFor(GraphNode.EntityKey[Person], data.entityId)
              val reply: Future[GraphNode.Confirmation[Person]] =
                entityRef.ask(GraphNode.AddData(data.person, _))
              onSuccess(reply) {
                case GraphNode.Accepted(summary) =>
                  complete(StatusCodes.OK -> summary)
                case GraphNode.Rejected(reason) =>
                  complete(StatusCodes.BadRequest, reason)
              }
            }
          },
            pathPrefix(Segment) { entityId =>
              concat(get {
                val entityRef = sharding.entityRefFor(GraphNode.EntityKey[Person], entityId)
                onSuccess(entityRef.ask(GraphNode.Get[Person])) { summary =>
                  if (summary.relationships.isEmpty && summary.data.isEmpty) complete(StatusCodes.NotFound)
                  else complete(StatusCodes.OK -> summary)
                }
              })
            },
            pathPrefix("relationship") {
              concat(
                post {
                  entity(as[AddRelationship]) { data =>
                    val entityRef = sharding.entityRefFor(GraphNode.EntityKey[Person], data.entityId)
                    val reply: Future[GraphNode.Confirmation[Person]] =
                      entityRef.ask(GraphNode.AddRelationship(data.relationId, data.relationType, data.relationTarget, _))
                    onSuccess(reply) {
                      case GraphNode.Accepted(summary) =>
                        complete(StatusCodes.OK -> summary)
                      case GraphNode.Rejected(reason) =>
                        complete(StatusCodes.BadRequest, reason)
                    }
                  }
                }
              )
            }
          )
        })
    }

}

object JsonFormats {

  import spray.json.RootJsonFormat
  // import the default encoders for primitive types (Int, String, Lists etc)
  import spray.json.DefaultJsonProtocol._

  implicit val companyFormat: RootJsonFormat[Company] = jsonFormat2(Company)
  implicit val personFormat: RootJsonFormat[Person] = jsonFormat2(Person)
  implicit val relationshipFormat: RootJsonFormat[Relationship] = jsonFormat2(Relationship)

  implicit def summaryFormat[A: JsonFormat]: RootJsonFormat[GraphNode.Summary[A]] = jsonFormat2(GraphNode.Summary.apply[A])

  implicit val addCompanyFormat: RootJsonFormat[LinkedOutRoutes.AddCompany] = jsonFormat2(LinkedOutRoutes.AddCompany)
  implicit val addPersonFormat: RootJsonFormat[LinkedOutRoutes.AddPerson] = jsonFormat2(LinkedOutRoutes.AddPerson)
  implicit val addRelationshipFormat: RootJsonFormat[LinkedOutRoutes.AddRelationship] = jsonFormat4(LinkedOutRoutes.AddRelationship)
  implicit val removeRelationshipFormat: RootJsonFormat[LinkedOutRoutes.RemoveRelationship] = jsonFormat2(
    LinkedOutRoutes.RemoveRelationship)

}
