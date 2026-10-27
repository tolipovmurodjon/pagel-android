package org.pagel.ai.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.pagel.ai.data.db.PagelDatabase

/** A fresh in-memory Room database per test (RULES.md, testing). */
fun inMemoryDb(): PagelDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PagelDatabase::class.java)
        .allowMainThreadQueries()
        .build()
