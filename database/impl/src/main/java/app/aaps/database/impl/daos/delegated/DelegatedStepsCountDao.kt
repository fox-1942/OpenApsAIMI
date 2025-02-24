package app.aaps.database.impl.daos.delegated

import app.aaps.database.entities.StepsCount
import app.aaps.database.entities.interfaces.DBEntry
import app.aaps.database.impl.daos.stepsCountDao

internal class DelegatedStepsCountDao(
    changes: MutableList<DBEntry>,
    private val dao: stepsCountDao
): DelegatedDao(changes), stepsCountDao by dao {

    override fun insertNewEntry(entry: StepsCount): Long {
        changes.add(entry)
        return dao.insertNewEntry(entry)
    }

    override fun updateExistingEntry(entry: StepsCount): Long {
        changes.add(entry)
        return dao.updateExistingEntry(entry)
    }
}
