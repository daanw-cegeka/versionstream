# Version Streams
## What is a version stream?
A version stream is an ordered collection of all versions of the state of a group of
entities.

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
we want it to be. It can be the users, it can be feature flags, all products in
a product catalog, ...

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

# Sub streams

# Stream of Stream