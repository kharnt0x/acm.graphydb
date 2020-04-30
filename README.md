
API to store people and companies and relationships between them inside an underlying graph database using AKKA Persistence and cluster sharding with a CQRS design with separate read and write models. Uses Apache Cassandra as a data store. Adapted from Lightbend Akka CQRS sample code.

Nodes on the graph are modelled as persistant event sourced actors. 

## What is working

### Graph DB
* Clustering/Sharding of data
* Event sourced
* Separate read and write models
* Able to store data of different types into a single generic actor
* Match query on graph node actor based on predicate and the generic type stored
* Basic way of storing relationships on the graph node

### API
* Able to add/update a Company/Person (currently using POST for both, obviously a PUT for updates is more correct)
* Able to add relationships (although this is literally just storing some strings right now, the relationships are not doing anything yet)

## What is not working
* The read model is currently not receiving events with the correct type for data stored on the node, instead as a Map. This is causing serialization issues when querying to the read model as we are expecting a Person/Company and not a Map. 
* Sometimes the initial requests made after the cluster comes up fail, due to some timing issues. 
* When a new node comes up (read or write) there will be serialazation issues on doing a GET due to the event type issues mentioned above

## What else I planned to get done

### Query model
I planned to implement a design where there is a parent actor for each data type (i.e. Company, Person) which keeps a register of it's children. This parent actor is then the contact point for queries on graph nodes for that type. 

For example, for a query on companies, the company group actor would take the query, create a query actor, give the query actor the list of company nodes and the query to use. The query actor would then send the query to all Company nodes and reply with a group of matching data nodes directly to the original caller of the query (rather than going through the company group actor)

### API
* API for adding relationships is far from nice, very much work in progress. No checking to see if the entity relating to exists yet. I wanted to generate the relationship id on creation and return it, then use a PUT command to update the relationship
* Adding a query API once I had created query actor/typed data node groups

### Relationships
I planned to properly implement relationships, checking that the data node the relationship is pointing at exists for example 

## Running the code

1. Start a Cassandra server by running:

```
sbt "runMain acm.graphy.Main cassandra"
```

2. Start a node that runs the write model:

```
sbt -Dakka.cluster.roles.0=write-model "runMain acm.graphy.Main 2551"
```

3. Start a node that runs the read model:

```
sbt -Dakka.cluster.roles.0=read-model "runMain acm.graphy.Main 2552"
```

4. More write or read nodes can be started started by defining roles and port:

```
sbt -Dakka.cluster.roles.0=write-model "runMain acm.graphy.Main 2553"
sbt -Dakka.cluster.roles.0=read-model "runMain acm.graphy.Main 2554"
```

Example curl commands:

```
# add a company
curl -X POST -H "Content-Type: application/json" -d '{"entityId":"company1", "company":{"name": "AWS", "location": "Sydney"}}' http://127.0.0.1:8051/linkedout/company

# get company
curl http://127.0.0.1:8051/linkedout/company/company1

# add a person
curl -X POST -H "Content-Type: application/json" -d '{"entityId":"person1", "person":{"name": "Bob", "age": 25}}' http://127.0.0.1:8051/linkedout/person

# get person
curl http://127.0.0.1:8051/linkedout/person/person1

# add relationship to a person on a company
curl -X POST -H "Content-Type: application/json" -d '{"entityId": "company1", "relationId": "rel1", "relationType": "employs", "relationTarget":"person1" }' http://127.0.0.1:8051/linkedout/company/relationship

# add relationship to a company on a person
curl -X POST -H "Content-Type: application/json" -d '{"entityId": "person1", "relationId": "rel1", "relationType": "employedby", "relationTarget":"company1" }' http://127.0.0.1:8051/linkedout/person/relationship

or same `curl` commands to port 8052 (note: GET currently fails here due to type issues on read model side).
