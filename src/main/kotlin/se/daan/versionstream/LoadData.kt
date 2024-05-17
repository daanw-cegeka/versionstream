package se.daan.versionstream

import java.sql.DriverManager

fun main() {
    val local = DriverManager.getConnection("jdbc:mysql://localhost:13306/local_build_mrt", "root", "root")
    val repo = MySQLRepo(local)
    repo.clear()

    // ssh -L:3306:renta-acc-mrt-mysql.intra.cegeka.be:3306 renta-steppingstone.intra.cegeka.be
    val acc = DriverManager.getConnection("jdbc:mysql://localhost:3306/mrt", "daanw", System.getenv("PASSWD"))

    val resultSet = acc.prepareStatement("select * from activitydefinition").executeQuery()
    while(resultSet.next()) {
        val entity = ActivityDefinition(
            resultSet.getInt("activitydefinition_id"),
            resultSet.getString("activity_code"),
            resultSet.getString("description_dutch"),
            resultSet.getString("description_english"),
            resultSet.getString("description_french"),
            resultSet.getString("description_german"),
            resultSet.getBoolean("filters"),
            resultSet.getBigDecimal("maximum_number_of_parts").toString(),
            resultSet.getBoolean("kilometrage"),
            resultSet.getInt("activitygroup_id"),
            resultSet.getBoolean("active"),
            resultSet.getString("activity_definition_unit"),
            resultSet.getBoolean("selectable")
        )
        repo.append(entity.id, entity)
    }

    val resultSet2 = acc.prepareStatement("select * from activitygroup").executeQuery()
    while(resultSet2.next()) {
        val entity = ActivityGroup(
            resultSet2.getInt("activitygroup_id"),
            resultSet2.getString("group_code"),
            resultSet2.getString("description_dutch"),
            resultSet2.getString("description_english"),
            resultSet2.getString("description_french"),
            resultSet2.getString("description_german"),
            resultSet2.getBoolean("active"),
        )
        repo.append(entity.id, entity)
    }
}