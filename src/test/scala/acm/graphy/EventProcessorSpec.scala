package acm.graphy

import java.io.File

import akka.actor.testkit.typed.scaladsl.LoggingTestKit
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.eventstream.EventStream
import akka.persistence.cassandra.testkit.CassandraLauncher
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.scalatest.wordspec.AnyWordSpecLike

class EventProcessorSpec extends ScalaTestWithActorTestKit(ConfigFactory.parseString(s"""
      akka.actor.provider = local
      
      akka.persistence.cassandra {
        events-by-tag {
          eventual-consistency-delay = 200ms
        }
      
        query {
          refresh-interval = 500 ms
        }
      
        journal.keyspace-autocreate = on
        journal.tables-autocreate = on
        snapshot.keyspace-autocreate = on
        snapshot.tables-autocreate = on
      }
      datastax-java-driver {
        basic.contact-points = ["127.0.0.1:19042"]
        basic.load-balancing-policy.local-datacenter = "datacenter1"
      }
      
      akka.actor.testkit.typed.single-expect-default = 5s
      # For LoggingTestKit
      akka.actor.testkit.typed.filter-leeway = 5s
    """).withFallback(ConfigFactory.load())) with AnyWordSpecLike {

  val databaseDirectory = new File("target/cassandra-EventProcessorSpec")

  override protected def beforeAll(): Unit = {
    CassandraLauncher.start(
      databaseDirectory,
      CassandraLauncher.DefaultTestConfigResource,
      clean = true,
      port = 19042, // default is 9042, but use different for test
      CassandraLauncher.classpathForResources("logback-test.xml"))

    Main.createTables(system)

    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    CassandraLauncher.stop()
    FileUtils.deleteDirectory(databaseDirectory)
  }

  "The events from the Graph Node" should {
    "be consumed by the event processor" in {
      val graphnode1 = testKit.spawn(GraphNode[Company]("company-1", Set("tag-0")))
      val probe = testKit.createTestProbe[GraphNode.Confirmation[_]]

      val eventProbe = testKit.createTestProbe[GraphNode.Event]()

      testKit.system.eventStream ! EventStream.Subscribe(eventProbe.ref)

      testKit.spawn[Nothing](
        EventProcessor(
          new LinkedOutEventProcessorStream(system, system.executionContext, "EventProcessor", "tag-0")))

      graphnode1 ! GraphNode.AddData(Company("test", "sydney"), probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      eventProbe.expectMessage(GraphNode.DataAdded[Company]("company-1", Company("test", "sydney")))

      graphnode1 ! GraphNode.AddRelationship("foo", "employs", "person-1", probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      eventProbe.expectMessage(GraphNode.RelationshipAdded("company-1", "foo", "employs", "person-1"))

      graphnode1 ! GraphNode.AddRelationship("bar", "snoo", "gar", probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      eventProbe.expectMessage(GraphNode.RelationshipAdded("company-1", "bar", "snoo", "gar"))
      graphnode1 ! GraphNode.UpdateRelationship("bar", "foo", "baz", probe.ref)
      probe.expectMessageType[GraphNode.Accepted[Company]]
      eventProbe.expectMessage(GraphNode.RelationshipUpdated("company-1", "bar", "foo", "baz"))

      val graphnode2 = testKit.spawn(GraphNode[Person]("person-1", Set("tag-0")))
      // also verify that EventProcessor is logging
      LoggingTestKit.info("consumed RelationshipAdded(person-1,another,foo,bar)").expect {
        graphnode2 ! GraphNode.AddRelationship("another", "foo", "bar", probe.ref)
        probe.expectMessageType[GraphNode.Accepted[Person]]
      }
      eventProbe.expectMessage(GraphNode.RelationshipAdded("person-1", "another", "foo", "bar"))
    }
  }

}
