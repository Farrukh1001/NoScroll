package com.noscroll.data.wellbeing

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.noscroll.domain.wellbeing.UsageAggregate
import java.time.LocalDate

/**
 * Local storage for screen-time aggregates.
 *
 * This is hand-written SQL over the AndroidX SQLite support library rather than Room. The schema is
 * three flat tables with no relations to speak of, and skipping the annotation processor keeps the
 * build free of code generation — which matters for a project whose release artefact people are
 * expected to reproduce from source. Migrations are explicit and versioned below.
 *
 * Only aggregates live here: a date, a package name, and a duration. No titles, no URLs, no queries,
 * no per-event history.
 */
class WellbeingDatabase(context: Context) {

    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DATABASE_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(VERSION) {
                override fun onCreate(db: SupportSQLiteDatabase) = createSchema(db)

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    // Version 1 is the first release. Future migrations are added as explicit steps
                    // here; aggregates are never silently dropped on upgrade.
                }

                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.setForeignKeyConstraintsEnabled(true)
                }
            })
            .build()
    )

    private fun createSchema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_app_usage (
                day TEXT NOT NULL,
                package_name TEXT NOT NULL,
                millis INTEGER NOT NULL,
                PRIMARY KEY (day, package_name)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_hourly (
                day TEXT NOT NULL,
                hour INTEGER NOT NULL,
                millis INTEGER NOT NULL,
                PRIMARY KEY (day, hour)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS daily_summary (
                day TEXT NOT NULL PRIMARY KEY,
                total_millis INTEGER NOT NULL DEFAULT 0,
                instagram_seconds INTEGER NOT NULL DEFAULT 0,
                goal_minutes INTEGER NOT NULL DEFAULT 0,
                blocked_count INTEGER NOT NULL DEFAULT 0,
                protections_healthy INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
    }

    /**
     * Replaces the stored aggregate for every day present in [aggregate].
     *
     * Runs in one transaction: a partial write would leave a day double-counted, which is worse than
     * leaving it stale until the next pass.
     */
    fun replaceAggregate(aggregate: UsageAggregate) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            aggregate.perDayPerPackage.forEach { (day, packages) ->
                db.delete("daily_app_usage", "day = ?", arrayOf(day.toString()))
                packages.forEach { (packageName, millis) ->
                    db.execSQL(
                        "INSERT OR REPLACE INTO daily_app_usage (day, package_name, millis) VALUES (?, ?, ?)",
                        arrayOf<Any>(day.toString(), packageName, millis),
                    )
                }
                val total = packages.values.sum()
                db.execSQL(
                    """
                    INSERT INTO daily_summary (day, total_millis) VALUES (?, ?)
                    ON CONFLICT(day) DO UPDATE SET total_millis = excluded.total_millis
                    """.trimIndent(),
                    arrayOf<Any>(day.toString(), total),
                )
            }
            aggregate.perDayHourly.forEach { (day, hours) ->
                db.delete("daily_hourly", "day = ?", arrayOf(day.toString()))
                hours.forEachIndexed { hour, millis ->
                    if (millis > 0) {
                        db.execSQL(
                            "INSERT OR REPLACE INTO daily_hourly (day, hour, millis) VALUES (?, ?, ?)",
                            arrayOf<Any>(day.toString(), hour, millis),
                        )
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun readAggregate(from: LocalDate, to: LocalDate): UsageAggregate {
        val db = helper.readableDatabase
        val perDayPerPackage = mutableMapOf<LocalDate, MutableMap<String, Long>>()
        val perDayHourly = mutableMapOf<LocalDate, MutableList<Long>>()

        db.query(
            "SELECT day, package_name, millis FROM daily_app_usage WHERE day BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val day = runCatching { LocalDate.parse(cursor.getString(0)) }.getOrNull() ?: continue
                perDayPerPackage.getOrPut(day) { mutableMapOf() }[cursor.getString(1)] = cursor.getLong(2)
            }
        }

        db.query(
            "SELECT day, hour, millis FROM daily_hourly WHERE day BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val day = runCatching { LocalDate.parse(cursor.getString(0)) }.getOrNull() ?: continue
                val hours = perDayHourly.getOrPut(day) { MutableList(24) { 0L } }
                val hour = cursor.getInt(1)
                if (hour in 0..23) hours[hour] = cursor.getLong(2)
            }
        }

        return UsageAggregate(
            perDayPerPackage = perDayPerPackage.mapValues { it.value.toMap() },
            perDayHourly = perDayHourly.mapValues { it.value.toList() },
        )
    }

    fun recordBlockedAttempt(day: LocalDate) {
        helper.writableDatabase.execSQL(
            """
            INSERT INTO daily_summary (day, blocked_count) VALUES (?, 1)
            ON CONFLICT(day) DO UPDATE SET blocked_count = blocked_count + 1
            """.trimIndent(),
            arrayOf<Any>(day.toString()),
        )
    }

    fun blockedCount(day: LocalDate): Int {
        helper.readableDatabase.query(
            "SELECT blocked_count FROM daily_summary WHERE day = ?",
            arrayOf(day.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun recordInstagramSeconds(day: LocalDate, seconds: Long) {
        helper.writableDatabase.execSQL(
            """
            INSERT INTO daily_summary (day, instagram_seconds) VALUES (?, ?)
            ON CONFLICT(day) DO UPDATE SET instagram_seconds = excluded.instagram_seconds
            """.trimIndent(),
            arrayOf<Any>(day.toString(), seconds),
        )
    }

    fun instagramSeconds(day: LocalDate): Long {
        helper.readableDatabase.query(
            "SELECT instagram_seconds FROM daily_summary WHERE day = ?",
            arrayOf(day.toString()),
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    /** Drops everything older than the retention window. Called after each aggregation pass. */
    fun pruneOlderThan(cutoff: LocalDate) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val arg = arrayOf(cutoff.toString())
            db.delete("daily_app_usage", "day < ?", arg)
            db.delete("daily_hourly", "day < ?", arg)
            db.delete("daily_summary", "day < ?", arg)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * The user's "delete my wellbeing data" action.
     *
     * It empties these three tables and nothing else. The Instagram budget and any pending protected
     * change live in different stores entirely and are untouched.
     */
    fun deleteAllWellbeingData() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("daily_app_usage", null, null)
            db.delete("daily_hourly", null, null)
            db.delete("daily_summary", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Every day that has data, newest first — used by the calendar. */
    fun daysWithData(limit: Int = 400): List<LocalDate> {
        val days = mutableListOf<LocalDate>()
        helper.readableDatabase.query(
            "SELECT day FROM daily_summary ORDER BY day DESC LIMIT ?",
            arrayOf<Any>(limit),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { LocalDate.parse(cursor.getString(0)) }.getOrNull()?.let { days += it }
            }
        }
        return days
    }

    fun close() = helper.close()

    companion object {
        const val DATABASE_NAME = "wellbeing.db"
        const val VERSION = 1
    }
}
