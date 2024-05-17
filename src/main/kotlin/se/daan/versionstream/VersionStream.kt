package se.daan.versionstream

import java.sql.Connection
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

interface Repo {
    fun append(id: Int, type: KClass<*>, entity: String?)
    fun getLastVersion(): Int
    fun fetchAllVersions(from: Int, to: Int): List<Version>
    fun <T : Any> fetch(clazz: KClass<T>, id: Int, version: Int): T?
    fun previousVersion(clazz: KClass<*>, id: Int, version: Int): Int?
    fun clear()
}

inline fun <reified T : Any> Repo.append(id: Int, entity: T) {
    append(id, entity::class, Json.encodeToString(entity))
}

class MySQLRepo(
    private val connection: Connection,
): Repo {
    override fun clear() {
        connection.prepareStatement("delete from versionstream").execute()
    }

    override fun append(id: Int, type: KClass<*>, entity: String?) {
        append(id, type.simpleName!!, entity)
    }

    private fun append(id: Int, type: String, entity: String?) {
        val statement = connection.prepareStatement(
            "insert into versionstream(stream, version, type, entity_id, data) value " +
                    "(1, IFNULL((select max(s2.version) from versionstream s2 where s2.stream=1) + 1, 0), ?, ?, ?)"
        )
        statement.setString(1, type)
        statement.setInt(2, id)
        statement.setString(3, entity)
        statement.execute()
    }

    override fun getLastVersion(): Int {
        val statement = connection.prepareStatement(
            "select max(version) from versionstream"
        )

        val executeQuery = statement.executeQuery()
        assert(executeQuery.next())
        return executeQuery.getInt(1)
    }

    override fun fetchAllVersions(from: Int, to: Int): List<Version> {
        val statement = connection.prepareStatement(
            "select type, entity_id, max(version) as version from versionstream where stream = 1 and version >= ? and version <= ? group by type, entity_id"
        )

        statement.setInt(1, from)
        statement.setInt(2, to)
        val query = statement.executeQuery()
        val acc = mutableListOf<Version>()
        while (query.next()) {
            val type = query.getString("type")
            val typeClass = when (type) {
                ActivityDefinition::class.simpleName -> ActivityDefinition::class
                ActivityGroup::class.simpleName -> ActivityGroup::class
                else -> throw UnsupportedOperationException()
            }
            val element = Version(typeClass, query.getInt("entity_id"), query.getInt("version"))
            acc.add(element)
        }
        return acc
    }

    override fun <K : Any> fetch(clazz: KClass<K>, id: Int, version: Int): K? {
        val statement = connection.prepareStatement(
            "select v.data from versionstream  v where stream = 1 and version = ? and type = ? and entity_id = ?"
        )

        statement.setInt(1, version)
        statement.setString(2, clazz.simpleName)
        statement.setInt(3, id)
        val resultSet = statement.executeQuery()
        assert(resultSet.next())
        val element = parseData(resultSet, clazz)
        return element as K?
    }

    private fun parseData(query: ResultSet, clazz: KClass<*>): Any? {
        val data: String? = query.getString("data")
        return if (data == null) {
            null
        } else {
            when (clazz) {
                ActivityDefinition::class ->
                    Json.decodeFromString<ActivityDefinition>(data)

                ActivityGroup::class ->
                    Json.decodeFromString<ActivityGroup>(data)

                String::class -> null

                else -> throw UnsupportedOperationException()
            }
        }
    }

    override fun previousVersion(clazz: KClass<*>, id: Int, version: Int): Int? {
        val statement = connection.prepareStatement(
            "select max(version) as version from versionstream  v where stream = 1 and version < ? and type = ? and entity_id = ?"
        )
        statement.setInt(1, version)
        statement.setString(2, clazz.simpleName)
        statement.setInt(3, id)

        val resultSet = statement.executeQuery()

        return if (resultSet.next()) {
            resultSet.getInt("version")
        } else {
            null
        }
    }
}

data class Version(
    val type: KClass<*>,
    val id: Int,
    val version: Int,
)

class MultiSnapshotCache(
    val repo: MySQLRepo,
) {
    var currentVersion: Int = -1
    var currentEntityVersions: ConcurrentHashMap<KClass<*>, MutableMap<Int, Int>> = ConcurrentHashMap()
    val dataCache: ConcurrentHashMap<Triple<KClass<*>, Int, Int>, CacheEntry> = ConcurrentHashMap()

    fun <K : Any> get(clazz: KClass<K>, id: Int, version: Int): K? {
        checkCurrentVersions(version)
        var cursor = currentEntityVersions[clazz]?.let { it[id] }
        while (true) {
            if (cursor == null) {
                return null
            }
            val myData = getCacheEntry(clazz, id, cursor)
            if (cursor <= version) {
                return myData.data as K?
            } else {
                cursor = myData.previousVersion
            }
        }
    }

    fun <T: Any> getAll(clazz: KClass<T>, version: Int): List<T> {
        checkCurrentVersions(version)
        return (currentEntityVersions[clazz] ?: emptyMap())
            .entries
            .mapNotNull { get(clazz, it.key, version) }
    }

    private fun checkCurrentVersions(version: Int) {
        if (version > currentVersion) {
            repo.fetchAllVersions(currentVersion + 1, version).forEach {
                val classMap = currentEntityVersions.computeIfAbsent(it.type) { ConcurrentHashMap() }
                classMap[it.id] = it.version
            }
            currentVersion = version
        }
    }

    private fun getCacheEntry(clazz: KClass<*>, id: Int, version: Int): CacheEntry {
        val key = Triple(clazz, id, version)

        return dataCache.computeIfAbsent(key) { fetch(clazz, id, version) }
    }

    private fun fetch(clazz: KClass<*>, id: Int, version: Int): CacheEntry {
        val entity = repo.fetch(clazz, id, version)
        val prev = repo.previousVersion(clazz, id, version)
        return CacheEntry(entity, prev)
    }
}

data class CacheEntry(
    val data: Any?,
    val previousVersion: Int?,
)


