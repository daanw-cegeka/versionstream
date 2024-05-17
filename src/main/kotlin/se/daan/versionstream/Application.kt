import se.daan.versionstream.*
import java.sql.DriverManager

fun main() {
    val local = DriverManager.getConnection("jdbc:mysql://localhost:13306/local_build_mrt", "root", "root")
    val repo = MySQLRepo(local)
    val cache = MultiSnapshotCache(repo)

    val lastVersion1 = repo.getLastVersion()
    val get1 = cache.get(ActivityDefinition::class, 10415, lastVersion1)

    repo.append(
        10415,
        ActivityDefinition(10415, "7777", "Dood", "Dood", "Dood", "Dood", true, "", true, 4, true, null, true)
    )

    val lastVersion2 = repo.getLastVersion()

    val get22 = cache.get(ActivityDefinition::class, 10415, lastVersion2)
    val get222 = cache.get(ActivityDefinition::class, 10415, lastVersion2)
    val get21 = cache.get(ActivityDefinition::class, 10415, lastVersion1)


    repo.append(10415, ActivityDefinition::class, null)

    val lastVersion3 = repo.getLastVersion()

    val get31 = cache.get(ActivityDefinition::class, 10415, lastVersion1)
    val get32 = cache.get(ActivityDefinition::class, 10415, lastVersion2)
    val get33 = cache.get(ActivityDefinition::class, 10415, lastVersion3)

    val all = cache.getAll(ActivityGroup::class, lastVersion3)

}
