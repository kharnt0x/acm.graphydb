package acm.graphy

import java.util.UUID

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike
import GraphNode.Relationship

class GraphNodeSpec extends ScalaTestWithActorTestKit(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "target/snapshot-${UUID.randomUUID().toString}"
    """) with AnyWordSpecLike {

  private var counter = 0
  def newEntityId(): String = {
    counter += 1
    s"graphnode-$counter"
  }

  "The Graph Node" when {

    "add relationship" in {
      val node = testKit.spawn(GraphNode(newEntityId(), Set.empty))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      node ! GraphNode.AddRelationship("foo", "bar", "baz", probe.ref)
      probe.expectMessage(GraphNode.Accepted(GraphNode.Summary[Company](Map("foo" -> Relationship("bar", "baz")), None)))
    }

    "add data" in {
      val node = testKit.spawn(GraphNode(newEntityId(), Set.empty))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      node ! GraphNode.AddData(Company("foo", "bar"), probe.ref)
      probe.expectMessage(GraphNode.Accepted(GraphNode.Summary[Company](Map.empty, Some(Company("foo", "bar")))))
    }

    "reject already added relationship" in {
      val node = testKit.spawn(GraphNode(newEntityId(), Set.empty))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      node ! GraphNode.AddRelationship("foo", "bar", "baz", probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      node ! GraphNode.AddRelationship("foo", "ben", "bug", probe.ref)
      probe.expectMessageType[GraphNode.Rejected[Company]]
    }

    "remove relationship" in {
      val node = testKit.spawn(GraphNode(newEntityId(), Set.empty))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      node ! GraphNode.AddRelationship("foo", "bar", "baz", probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      node ! GraphNode.RemoveRelationship("foo", probe.ref)
      probe.expectMessage(GraphNode.Accepted(GraphNode.Summary[Company](Map.empty, None)))
    }

    "adjust quantity" in {
      val node = testKit.spawn(GraphNode(newEntityId(), Set.empty))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      node ! GraphNode.AddRelationship("foo", "bar", "baz", probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      node ! GraphNode.UpdateRelationship("foo", "ben", "bug", probe.ref)
      probe.expectMessage(GraphNode.Accepted(GraphNode.Summary[Company](Map("foo" -> Relationship("ben", "bug")), None)))
    }

    "keep its state" in {
      val nodeId = newEntityId()
      val node = testKit.spawn(GraphNode(nodeId, Set.empty))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      node ! GraphNode.AddRelationship("foo", "bar", "baz", probe.ref)
      probe.expectMessage(GraphNode.Accepted(GraphNode.Summary[Company](Map("foo" -> Relationship("bar", "baz")), None)))

      node ! GraphNode.AddData(Company("foo", "bar"), probe.ref)
      probe.expectMessage(GraphNode.Accepted(GraphNode.Summary[Company](Map("foo" -> Relationship("bar", "baz")), Some(Company("foo", "bar")))))

      testKit.stop(node)

      // start again with same nodeId
      val restartedGraphNode = testKit.spawn(GraphNode(nodeId, Set.empty))
      val stateProbe = testKit.createTestProbe[GraphNode.Summary[Company]]
      restartedGraphNode ! GraphNode.Get(stateProbe.ref)
      stateProbe.expectMessage(GraphNode.Summary[Company](Map("foo" -> Relationship("bar", "baz")), Some(Company("foo", "bar"))))
    }

    "when receiving match predicate query" should {
      val node = testKit.spawn(GraphNode[Company](newEntityId(), Set.empty))
      val commandprobe = testKit.createTestProbe[GraphNode.Confirmation[Company]]
      val queryprobe = testKit.createTestProbe[GraphNode.QueryResult[Company]]
      node ! GraphNode.AddData(Company("foo", "bar"), commandprobe.ref)
      commandprobe.expectMessageType[GraphNode.Accepted[Company]]
      "respond matched if matching" in {
        node ! GraphNode.MatchQuery[Company](a => a.location == "bar", queryprobe.ref)
        queryprobe.expectMessage(GraphNode.Matched(GraphNode.Summary[Company](Map.empty, Some(Company("foo", "bar")))))
      }
      "respond notmatched if not matching" in {
        node ! GraphNode.MatchQuery[Company](a => a.location == "rab", queryprobe.ref)
        queryprobe.expectMessage(GraphNode.NotMatched(GraphNode.Summary[Company](Map.empty, Some(Company("foo", "bar")))))
      }
    }
  }

}
