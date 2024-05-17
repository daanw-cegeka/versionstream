# Version Streams
## What is a version stream?
A version stream is an ordered collection of all versions of the state of a group of
entities. Another way to look at it, is like a key-value store with a full history.

Initially the version stream is empty. Then we create a new entity `Entity 1`.
The stream now has version `0` and so does the state of `Entity 1`. We say that
`Entity 1` currently has version `0`.

```
Entity 1    | v0 |
```
| Entity   | Current version |
|----------|-----------------|
| Entity 1 | v0              |

Next we add another entity `Entity 2`. The stream now has the version `1` and
we also assign the version `1` to the state of `Entity 2`.  We say that
`Entity 1` currently has version `0` and `Entity 2` has version `1`.

```
Entity 1    | v0 |
Entity 2           | v1 |
```

| Entity   | Current version |
|----------|-----------------|
| Entity 1 | v0              |
| Entity 2 | v1              |

Next we update `Entity 2`. The stream now has the version `2` and we also
assign the version `2` to the new state of `Entity 2`. We say that `Entity 1` 
currently has version `0` and `Entity 2` has version `2`.

```
Entity 1    | v0 |
Entity 2           | v1 | | v2 |
```

| Entity   | Current version |
|----------|-----------------|
| Entity 1 | v0              |
| Entity 2 | v2              |

Finally, we delete `Entity 1`. We can implement this as an update with `null`
as the state of `Entity 1`. The stream now has the version `3` and we also
assign the version `3` to the tombstone of `Entity 1`. We say that `Entity 1`
currently has version `3` and `Entity 2` has version `2`.

```
Entity 1    | v0 |               | v3 (RIP) |
Entity 2           | v1 | | v2 |
```

| Entity     | Current version |
|------------|-----------------|
| (Entity 1) | (v3)            |
| Entity 2   | v2              |

### Entities
In our example we were vague about what the entities. An entity can be whatever
we want it to be as long as every distinct entity has a distinct identifier. It
can be the users, it can be feature flags, all products in a product catalog, ...

We can also use a mix of types. We can, for example, put the users and feature
flags all in one Version Stream.

Note that the versions of an entity are not incremental, but they are
monotonically increasing.

### Immutability
An important property is that the data is immutable. This is a very useful
property to have for caching and concurrent access pattern.

### Multiple Version Streams
In our example we only showed one Version Stream, but nothing prevents us from
having multiple Version Streams. We could, for example, have one stream per
user to track the users preferences.

## Applications
### History / Audit
Because all versions are preserved, we can always recover older versions and
observe what happened with the entities.

### Replicating data over unreliable network
Replicating data is also relatively straightforward. All the follower needs to
remember is the last version that was replicated. The follower can then ask the
Version Stream what the new updates are and apply them.

Out-of-order events can also be rejected because we can compare it to the last
state we received. Comparisons should be made at the Entity-level though, not
at the level of the whole stream.

A lost updates can easily be corrected. If we are lucky, it will fix itself
when the next version comes along. Otherwise, we can also manually "resend"
the missing data.

### Caching HTTP calls
Because the versions are immutable, we can take advantage of the caching
facilities of HTTP.

For example, lets say we have a product catalog that does not change very
often. We can create a Version Stream for the whole catalog. When the client
does a GET for all products, we can set an E-Tag equal to the version of
the stream.

When the client then wants to validate its cached data, we only need to compare
the provided E-Tag with the current version of the stream. 99% of the time
it's still OK and very little data needs to be sent.

## Implementation details
This is an example implementation that makes the following assumption:
- MySQL 8 with InnoDB is used
- The insert is done with isolation level "Repeatable Read" or higher

### Table
```
create table versionstream(
    stream int not null,
    version int not null,
    entity varchar(100) not null,
    data text,
    primary key (stream, version, entity) -- Also serves as index
    index idx_entity_version (stream, entity, version)
)
```
Note: the types are somewhat arbitrary, you can choose whatever suites you
best. One exception is the `version`, this needs to be numerical. Another
attention point is the `data`, which must be nullable to support deletes.

`entity` can also be split in two columns: `type` and `entity_id`. The type
is often the class (eg `User`) and the `entity_id` can be a number or UUID. We
recommend to use the `enum` type for `type` because it gets translated to a
small number. Which might make things faster.

### Repository
The following pseudocode represent the code to implement a repository for
the Version Streams.

The queries for the versions are separated from the queries to fetch data.
This makes the example easier to understand, and it works well with the
Multi Snapshot Cache.

```
append(stream, id, entity, data) {
    sql( insert into versionstream(stream, version, entity, data) 
         values (
            :stream,
            IFNULL((select max(s2.version) from versionstream s2 where s2.stream = :stream) + 1, 0),
            :entity,
            :data
         )
    )
}
   
fetchLastVersion(stream) {
    return sql( select max(version) 
                from versionstream
                where stream = :stream
           )
}

fetchNewVersions(stream, from, to) {
    return sql( select entity, max(version) as version 
                from versionstream 
                where stream = :stream and
                version >= :from and version <= :to
                group by entity
           )
}

fetchData(stream, entity, version) {
    return sql( select data from versionstream 
                where stream = :stream and 
                version = :version and
                entity = :entity
           )
```
#### Performance considerations
Even though appending is a fast operation, it can become a bottleneck.
On my local machine an insert takes on average less than 10ms, so 100 appends
per second should be very feasible.

You should also be aware that the Version Stream is locked for as long as you
keep the transaction open which exaggerates the problem.

A solution could be to look at the Stream-of-Streams pattern or partitioning
the stream.

We do not recommend to keep a separate counter to hand out version numbers in
parallel. This can lead to a situation where, for example, version `8` is commited
before version `7`. This will imply that version `8` will be available to everyone
before version `7`, which might lead to lost updates. Workaround might be possible,
but that might add complexity.

#### Appending data
It is very important that the insert runs with isolation level
"Repeatable Read" or "Serializable". Otherwise, it will not cover the gap.

When MySQL executes this query, it will use the primary index to fetch the
last version. It will then take a lock on that row and on the gap after
it. The lock covers `(:stream, max-version) ... (:stream, infinity)`.
This means that no other transaction can insert data with the same
`stream` and a higher or equal `version`. For more details, search for
gap-locks in the MySQL manual.

The `IFNULL` covers the case when we have an empty Version Stream.

#### Fetch last version
The primary index starts with `(stream, version, ...)`. MySQL takes advantage
of this to very quickly find the largest value of `version` for our `stream`.

#### Fetch new version
In our ideal world, MySQL would use the index `idx_entity_version`, but it
seems to work fine and fast by using the primary index. It works fast because
it only needs the data that is already present in the index. There is no need
to consult additional data in the table.

#### Fetching data
We use the primary index to fetch the data, which is really fast.

# Multi Snapshot Cache
## What is the Multi Snapshot Cache
The Multi Snapshot Cache is a cache that answers the following query:
What was the data of Entity `E` at the time of version `V` of the
Version Stream?

The entire history of the event are preserved, so it should theoretically be
possible to reconstruct the state of the entities like it was at version `V`.
We call this a snapshot at version `V`.

In a CRUD application, we have to careful with caching. What if an entity is
updated by another transaction in the middle of our transaction? When do we
invalidate our cache?

In Version Streams however, the states are immutable, which makes it a great
candidate for caching. And we can do all the work within a single snapshot.

It's also very easy to check if there is fresh data available. When we refresh
our cache, we also store the version number of the Version Stream at that time.
To check if there is fresh data available, all we have to do is to compare the
version number of the cache with the version number of the Version Stream. This
is not only very reliable, it is also very fast.

## Applications
### High performance with high consistency
In traditional applications, when you want to use caching, there is a trade-off
to be made between high performance and consistency with the database. When we
use Version Streams with a Multi Snapshot Cache, we no longer need to make that
trade-off.

Consider the following flow
```
    Application           Multi Snapshot Cache          Database
        |                           |                        |
        |----------------- start transaction --------------->|
        |                           |                        |
        |---------------- get current version -------------->|
        |<- - - - - - - - - -  version V  - - - - - - - - - -|
        |                           |                        |
        |------ get entity E ------>|                        |
        |      with version V       |                        |
        |                       cache hit                    |
        |                           |                        |
        |--------------- get other data -------------------->|
        |                           |                        |
        |-----------------  end transaction  --------------->|
        |                           |                        |
```

`TODO: rewrite, this makes no sense {`
We assume that the transaction runs on MySQL with isolation level
"Repeatable Read". When we ask for the current version, we get back version `V`.
This version `V` is calculated based on all the data in the transactions
snapshot of the database.

The data in the cache is part of the Version Streams snapshot on version `V`. 
If we assume that the version is updated atomically with the data in the 
database, then we can conclude that the data in the cache is identical to
the corresponding data in the database snapshot.

The data in our cache is just as consistent as if we were to query the database
again for those entities, but without having to sacrifice performance.
`}`

TODO: maybe use mathematical language to proof this

### Using a single snapshot across multiple services
We can extend the previous idea and put a REST interface between the
Multi Snapshot Cache and the repository, and then put a cache in other
services. This way, we have a consistent snapshot of the data across
all services.

It can work like this:

```
    User            Service 1           Service 2        Master Service    
     |                    |                 |                 |
     |------------------ get current version ---------------->|
     |<- - - - - - - - - - -  version V  - - - - - - - - - - -|
     |                    |                 |                 |
     |-- interact with -->|                 |                 |
     |    snapshot V      |---------- get data with --------->| 
     |                    |            snapshot V             |
     |                    |                 |                 |
     |----------- interact with ----------->|                 |
     |             snapshot V               |                 |
     |                    |             cache hit             |
     |                    |                 |                 |
     |                    |<-- coordinate ->|                 |
     |                    | with snapshot V |                 |
     |                    |                 |                 |
```

All services use the same data and this will make the system as a whole more
reliable. When the data is eventual consistent across the services, there is
a chance that things will go wrong because different services might make
different assumptions or decisions based on the different data.

## Implementation details
The tricky part is that we don't want store the whole state of a snapshot in
memory. This would be very wasteful. For example, if we have 1000 entities
and want to store 5 snapshots, it would require 5000 entries in the cache.
Ideally, we would only like to story the 1004 unique versions.

Luckily, there is an optimisation we can use. If we can assume that only the
most recent snapshots will be queried and that new snapshots are added 
infrequently, then we can store the most recent version of the entities
(= the last snapshot) and have every entity point to its previous version.

Take for example the following Version Stream:
```
Entity 1    | v0 |               |(v3)|
Entity 2           | v1 | | v2 |
Entity 3                                | v4 |
```

| Entity     | Current version |
|------------|-----------------|
| (Entity 1) | (v3)            |
| Entity 2   | v2              |
| Entity 3   | v4              |

We can represent it like this:

```
                    Most recent version
| v0 | <- |(v3)| <- Entity 1
| v1 | <- | v2 | <- Entity 2
          | v4 | <- Entity 3
```

To calculate the state at version 2, we can calculate backwards:
- Entity 1 is currently version 3, which is too new, so we fall back to
    version 0. Observe that we have undone the deletion of Entity 1.
- Entity 2 is at version 2, this is fine.
- Entity 3 is at version 4, there is nothing to fall back to, so we conclude
    that it did not yet exist at version 2 of the Version Stream.

### Pseudo code
We will use the `Repository` from the previous chapter. To keep things simple
we will only use one stream called `0`.

```
MultiSnapshotCache {
    repo: Repository
    
    currentVersion: Int
    currentEntityVersions: Map<EntityId, Version>
    dataCache: Cache<(EntityId, Version), Data>
    
    getData(entityId, snapshot) {
        updateCurrentVersions(snapshot)
        cursor = currentEntityVersions[entityId]
        while (true) {
            if (cursor == null) {
                return null
            }
            cacheEntry = getCacheEntry(entity, cursor)
            if (cursor <= version) {
                return cacheEntry.data
            } else {
                cursor = cacheEntry.previousVersion
            }
        }
    }
    
    updateCurrentVersions(snapshot) {
        if (snapshot > currentVersion) {
            newVersions = repo.fetchNewVersions(0, currentVersion + 1, snapshot)
            for (newVersion in newVersions) {
                currentyEntityVersions[newVersion.entity] = newVersion.version
            }
            // You can also use snapshot if you're sure it's a valid version
            currentVersion = max(newVersions.version)
        }
    }
    
    getCacheEntry(entityId, version) {
        entry = dataCache[(entityId, version)]
        if(entry == null) {
            entry = repo.fetchData(0, entityId, version)
            dataCache[(entityId, version)] = entry
        }
        return entry
    }
}
```

#### Thread safety
The pseudocode does not explicitly take care of thread safety, but this is
nonetheless important.

Luckily the data is immutable and the calculations are idempotent. For the most
part, the worst we can do is to do the same calculations twice. Which might 
actually be more performant than introducing locks. (Remember, we assume that
data does not change that often).

We do recommend to use a `Map` and `Cache` implementation that can handle
concurrent access. In the JVM ecosystem, a `ConcurrentHashMap` would suffice.

It is also important to update `currentVersion` after `currentyEntityVersions`
have been updated. It's better to do the work twice, then it is to lose updates.

### About caching data and storing the current versions
For caching the data, any eviction strategy is safe to use. For example FIFO,
LRU, LFU or even no eviction.

In the pseudocode we always store the current versions. It is also possible to
use an evicting cache for the current version. But because it is a very small
structure we can keep everything in memory. It can be handy to query something
like: give me all the products at version `V`. To do this query you need a list
of products to start with. Otherwise, we might need to go to the DB every time
to check if we are not missing entries.

If the entries can be evicted you also need to be able to make a distinction
between "we don't know" and "we know there is no such entity". Otherwise, it
needs to go to the database every time an entity is queried that does not
exist.

# Additional patterns
## Sub streams
Another technique that can be used is to project a Version Stream onto multiple
sub-streams.

Let's take the following example

```
User 1    | v0 |               |(v3)|
User 2           | v1 | | v2 |
Feature 1                             | v4 |
Feature 2                                    | v5 |
```

| Entity    | Current version |
|-----------|-----------------|
| (User 1)  | (v3)            |
| User 2    | v2              |
| Feature 1 | v4              |
| Feature 2 | v5              |

We can also look at this as two different streams. One for the users and
one for the features.

```
User 1    | v0 |               |(v3)|
User 2           | v1 | | v2 |
```

| Entity    | Current version |
|-----------|-----------------|
| (User 1)  | (v3)            |
| User 2    | v2              |

and

```
Feature 1    | v4 |
Feature 2           | v5 |
```

| Entity    | Current version |
|-----------|-----------------|
| Feature 1 | v4              |
| Feature 2 | v5              |

Note that these are just projections / views. New versions are appended to
the original Version Stream.

## Partitioned streams
We can also partition a stream. Let's say that we originally had stream with
all users, but the application is becoming more popular. Then we can redesign
our system like this: instead of creating one stream for all users, we create
one stream for the even users and one for the odd users.

Stream 1:
```
User 1    | v0 |  |(v1)|
User 3                   | v2 |
```

| Entity   | Current version |
|----------|-----------------|
| (User 1) | (v1)            |
| User 3   | v2              |

Stream 2:
```
User 2    | v0 | | v1 |
User 4                  | v2 |
```

| Entity | Current version |
|--------|-----------------|
| User 2 | v1              |
| User 4 | v2              |

We can then later recombine them with the Stream-of-Streams pattern.

## Stream-of-Streams
Sometimes we are forced to split a stream into multiple streams. Typically,
because of a performance bottleneck. The good news is that there is a way
to recombine them.

It is also possible to combine streams of different types. For example, we
can have a stream for each type of master data and then combine them into one
big stream of all master data.

Let's take again the users example.

Stream 1:
```
User 1    | v0 |  |(v1)|
User 3                   | v2 |
```

| Entity   | Current version |
|----------|-----------------|
| (User 1) | (v1)            |
| User 3   | v2              |

Stream 2:
```
User 2    | v0 | | v1 |
User 4                  | v2 |
```

| Entity | Current version |
|--------|-----------------|
| User 2 | v1              |
| User 4 | v2              |

We can add an extra stream. In this new stream every original stream will be
a key, and we will use the streams version as the value that changes over time.

Stream-of-Streams:
```
Stream1    | v0 -> v0 |            | v2 -> v1 |                         | v5 -> v2 |
Stream2                | v1 -> v0 |            | v3 -> v1 | | v4 -> v2 |
```

| Entity   | Current version | Maps to        |
|----------|-----------------|----------------|
| Stream 1 | v2              | v2 of Stream 1 |
| Stream 2 | v1              | v2 of Stream 2 |

But as you can see, the amount of version can explode. And this while we were
already dealing with a performance bottleneck.

We can solve this by sampling or buffering the original streams. Implementing
this is non-trivial because there is concurrency going on, and we have to
balance latency with performance without losing updates.

Stream-of-Streams:
```
Stream1    | v0 -> v0 |            | v2 -> v2 |
Stream2                | v1 -> v2 |
```

| Entity   | Current version | Maps to        |
|----------|-----------------|----------------|
| Stream 1 | v2              | v2 of Stream 1 |
| Stream 2 | v1              | v2 of Stream 2 |

## Stream-of-Streams-of-Streams
Sure why not?