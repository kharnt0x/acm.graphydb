package acm.graphy

import scala.concurrent.duration._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.SupervisorStrategy
import akka.actor.typed.receptionist.ServiceKey
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.RetentionCriteria
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.persistence.typed.scaladsl.ReplyEffect
import scala.reflect.runtime.universe._

/**
 * This is an event sourced actor. It has a state, [[GraphNode.State]], which
 * stores the Graph node, it's data and relationships
 *
 * Event sourced actors are interacted with by sending them commands,
 * see classes implementing [[GraphNode.Command]].
 *
 * Commands get translated to events, see classes implementing [[GraphNode.Event]].
 * It's the events that get persisted by the entity. Each event will have an event handler
 * registered for it, and an event handler updates the current state based on the event.
 * This will be done when the event is first created, and it will also be done when the entity is
 * loaded from the database - each event will be replayed to recreate the state
 * of the entity.
 */
object GraphNode {
  val PingServiceKey = ServiceKey[Command]("pingService")

  /**
   * The current state held by the persistent entity.
   */
  final case class State[A](relationships: Map[String, Relationship], data: Option[A]) extends CborSerializable {

    def hasRelationship(relationshipId: String): Boolean =
      relationships.contains(relationshipId)

    def isEmpty: Boolean =
      relationships.isEmpty

    def updateRelationship(relationshipId: String, relationType: String, relationTarget: String): State[A] = {
        copy(relationships = relationships + (relationshipId -> Relationship(relationType, relationTarget)))
    }

    def addData(data: A): State[A] = {
      copy(data = Some(data))
    }

    def removeRelationship(relationId: String): State[A] =
      copy(relationships = relationships - relationId)

    def toSummary: Summary[A] =
      Summary(relationships, data)
  }
  object State {
    def empty[A]: State[A] =
       State[A](relationships = Map.empty, data = None)
  }

  final case class Relationship(relationType: String, relationTarget: String) extends CborSerializable

  /**
   * This interface defines all the commands that the GraphNode persistent actor supports.
   */
  sealed trait Command extends CborSerializable

  /**
   * A command to a relationship to the graph node.
   *
   * It can reply with `Confirmation`, which is sent back to the caller when
   * all the events emitted by this command are successfully persisted.
   */
  final case class AddRelationship[A](relationshipId: String, relationType: String, relationTarget: String, replyTo: ActorRef[Confirmation[A]]) extends Command

  final case class AddData[A](data: A, replyTo: ActorRef[Confirmation[A]]) extends Command
  /**
   * A command to remove a relationship from the graph node.
   */
  final case class RemoveRelationship[A](relationshipId: String, replyTo: ActorRef[Confirmation[A]]) extends Command

  //todo: is this needed
  /**
   * A command to update a relationship on the graph node.
   */
  final case class UpdateRelationship[A](relationshipId: String, relationType: String, relationTarget: String, replyTo: ActorRef[Confirmation[A]]) extends Command

  /**
   * A command to get the current state of the graph node.
   */
  final case class Get[A](replyTo: ActorRef[Summary[A]]) extends Command

  final case class MatchQuery[A](f: A=> Boolean, replyTo: ActorRef[QueryResult[A]]) extends Command

  sealed trait QueryResult[A] extends CborSerializable

  final case class Matched[A](summary: Summary[A]) extends QueryResult[A]
  final case class NotMatched[A](summary: Summary[A]) extends QueryResult[A]

  /**
   * Summary of the graph node state, used in reply messages.
   */
  final case class Summary[A](relationships: Map[String, Relationship], data: Option[A]) extends CborSerializable

  sealed trait Confirmation[A] extends CborSerializable

  final case class Accepted[A](summary: Summary[A]) extends Confirmation[A]

  final case class Rejected[A](reason: String) extends Confirmation[A]

  /**
   * This interface defines all the events that the GraphNode supports.
   */
  sealed trait Event extends CborSerializable {
    def entityId: String
  }

  final case class DataAdded[A: TypeTag](entityId: String, data: A) extends Event

  final case class RelationshipAdded(entityId: String, relationshipId: String, relationType: String, relationTarget: String) extends Event

  final case class RelationshipRemoved(entityId: String, relationshipId: String) extends Event

  final case class RelationshipUpdated(entityId: String, relationshipId: String, relationType: String, relationTarget: String) extends Event

  def EntityKey[T: TypeTag]: EntityTypeKey[Command] = EntityTypeKey[Command](s"GraphNode-${typeOf[T].toString}")

  def init[A: TypeTag](system: ActorSystem[_], eventProcessorSettings: EventProcessorSettings): Unit = {
    val entityKey = EntityKey[A]
    ClusterSharding(system).init(Entity(entityKey) { entityContext =>
      val n = math.abs(entityContext.entityId.hashCode % eventProcessorSettings.parallelism)
      val eventProcessorTag = eventProcessorSettings.tagPrefix + "-" + n
      GraphNode[A](entityContext.entityId, Set(eventProcessorTag))
    }.withRole("write-model"))
  }

  def apply[A: TypeTag](entityId: String, eventProcessorTags: Set[String]): Behavior[Command] = {
    EventSourcedBehavior
      .withEnforcedReplies[Command, Event, State[A]](
        PersistenceId(EntityKey[A].name, entityId),
        State.empty[A],
        (state, command) =>
          handleCommand(entityId, state, command),
        (state, event) => handleEvent(state, event))
      .withTagger(_ => eventProcessorTags)
      .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 3))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))

  }

  private def handleCommand[A: TypeTag](entityId: String, state: State[A], command: Command): ReplyEffect[Event, State[A]] =
    command match {
      case AddRelationship(relationshipId, relationType, relationTarget, replyTo: ActorRef[Confirmation[A]]) =>
        if (state.hasRelationship(relationshipId))
          Effect.reply(replyTo)(Rejected(s"Relationship '$relationshipId' was already added to this graph node"))
        else
          Effect
            .persist(RelationshipAdded(entityId, relationshipId, relationType, relationTarget))
            .thenReply(replyTo)((updatedGraphNode) => Accepted(updatedGraphNode.toSummary))
      case AddData(data: A, replyTo: ActorRef[Confirmation[A]]) =>
        Effect.persist(DataAdded(entityId, data))
          .thenReply(replyTo)(updatedNode => Accepted(updatedNode.toSummary))
      case RemoveRelationship(relationshipId, replyTo: ActorRef[Confirmation[A]]) =>
        if (state.hasRelationship(relationshipId))
          Effect.persist(RelationshipRemoved(entityId, relationshipId))
            .thenReply(replyTo)(updatedNode => Accepted(updatedNode.toSummary))
        else
          Effect.reply(replyTo)(Accepted(state.toSummary)) // removing an item is idempotent
      case UpdateRelationship(relationshipId, relationType, relationTarget, replyTo: ActorRef[Confirmation[A]]) =>
          Effect
            .persist(RelationshipUpdated(entityId, relationshipId, relationType, relationTarget))
            .thenReply(replyTo)(updatedNode => Accepted(updatedNode.toSummary))
      case Get(replyTo: ActorRef[Summary[A]]) =>
        Effect.reply(replyTo)(state.toSummary)
      case MatchQuery(query: (A => Boolean), replyTo: ActorRef[QueryResult[A]]) =>
        if (state.data.exists(query)) {
          Effect.reply(replyTo)(Matched(state.toSummary))
        } else {
          Effect.reply(replyTo)(NotMatched(state.toSummary))
        }
    }

  private def handleEvent[A: TypeTag](state: State[A], event: Event) = {
    event match {
      case RelationshipAdded(_, relationshipId, relationType, relationTarget)            => state.updateRelationship(relationshipId, relationType, relationTarget)
      case evt: DataAdded[A]                        => state.addData(evt.data)
      case RelationshipRemoved(_, itemId)                    => state.removeRelationship(itemId)
      case RelationshipUpdated(_, relationshipId, relationType, relationTarget) => state.updateRelationship(relationshipId, relationType, relationTarget)
    }
  }
}
