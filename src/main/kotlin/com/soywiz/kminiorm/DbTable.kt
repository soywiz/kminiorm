package com.soywiz.kminiorm

import java.io.*
import java.sql.*
import kotlin.reflect.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*

suspend fun <T : Any> Db.table(clazz: KClass<T>) = DbTable(this, clazz).also { it.initialize() }
suspend inline fun <reified T : Any> Db.table() = table(T::class)

abstract class BaseDbTable<T : Any> : DbQueryable {
    abstract val table: DbTable<T>
    private val _db get() = table.db
    private val _quotedTableName get() = table.quotedTableName

    suspend fun showColumns(): Map<String, Map<String, Any?>> {
        return query("SHOW COLUMNS FROM $_quotedTableName;").associateBy { it["COLUMN_NAME"]?.toString() ?: "-" }
    }

    suspend fun initialize() = this.apply {
        query("CREATE TABLE IF NOT EXISTS $_quotedTableName;")
        val oldColumns = showColumns()
        for (column in table.columns) {
            if (column.name in oldColumns) continue // Do not add columns if they already exists

            query(buildString {
                append("ALTER TABLE ")
                append(_quotedTableName)
                append(" ADD ")
                append(column.quotedName)
                append(" ")
                append(column.sqlType)
                if (column.isNullable) {
                    append(" NULLABLE")
                } else {
                    append(" NOT NULL")
                    when {
                        column.jclazz == String::class.java -> append(" DEFAULT (\"\")")
                        column.jclazz.isSubclassOf(Number::class) -> append(" DEFAULT (0)")
                    }
                }
                append(";")
            })
        }

        for (column in table.columns) {
            //println("$column: ${column.quotedName}: ${column.isUnique}, ${column.isIndex}")
            if (column.isUnique || column.isIndex) {
                val unique = column.isUnique
                query(buildString {
                    append("CREATE ")
                    if (unique) append("UNIQUE ")
                    append("INDEX IF NOT EXISTS ${column.quotedName} ON $_quotedTableName (${column.quotedName});")
                })
            }
        }
    }

    suspend fun insert(instance: T): T {
        insert(_db.mapper.convertValueToMap(instance))
        return instance
    }

    suspend fun insert(instance: Partial<T>) {
        insert(instance.data.fix())
    }

    suspend fun insert(data: Map<String, Any?>): DbResult {
        val entries = data.entries.toList()
        return query(buildString {
            append("INSERT INTO ")
            append(_quotedTableName)
            append("(")
            append(entries.joinToString(", ") { _db.quoteColumnName(it.key) })
            append(")")
            append(" VALUES ")
            append("(")
            append(entries.joinToString(", ") { "?" })
            append(")")
        }, *entries.map { it.value }.toTypedArray())
    }

    suspend fun select(skip: Long? = null, limit: Long? = null, query: DbQueryBuilder<T>.() -> DbQuery<T> = { everything }): Iterable<T> {
        return query(buildString {
            append("SELECT ")
            append("*")
            append(" FROM ")
            append(_quotedTableName)
            append(" WHERE ")
            append(query(DbQueryBuilder as DbQueryBuilder<T>).toString(_db))
            if (limit != null) append(" LIMIT $limit")
            if (skip != null) append(" OFFSET $skip")
            append(";")
        }).map { _db.mapper.convertValue(it.mapValues { (key, value) ->
            //println("it: $value, ${value?.javaClass}: ${value is InputStream}")
            when (value) {
                is InputStream -> value.readBytes()
                is Blob -> value.binaryStream.readBytes()
                else -> value
            }
        }, table.clazz.java) }
    }

    suspend fun find(query: DbQueryBuilder<T>.() -> DbQuery<T> = { everything }): Iterable<T> = select(query = query)
    suspend fun findOne(query: DbQueryBuilder<T>.() -> DbQuery<T> = { everything }): T? = find(query).firstOrNull()

    suspend fun update(value: Partial<T>, limit: Long? = null, query: DbQueryBuilder<T>.() -> DbQuery<T>): Int {
        val entries = value.data.fix().entries
        val keys = entries.map { it.key }
        val values = entries.map { it.value }
        return query(buildString {
            append("UPDATE ")
            append(table.quotedTableName)
            append(" SET ")
            append(keys.joinToString(", ") { _db.quoteColumnName(it) + "=?" })
            append(" WHERE ")
            append(query(DbQueryBuilder as DbQueryBuilder<T>).toString(_db))
            if (limit != null) append(" LIMIT $limit")
            append(";")
        }, *values.toTypedArray()).updateCount
    }

    private fun Map<String, Any?>.fix(): Map<String, Any?> {
        // @TODO: Use @Name annotation
        return this
    }
}

class DbTable<T: Any>(val db: Db, val clazz: KClass<T>) : BaseDbTable<T>() {
    val tableName = clazz.findAnnotation<Name>()?.name ?: clazz.simpleName ?: error("$clazz doesn't have name")
    val quotedTableName = db.quoteTableName(tableName)
    val columns = clazz.memberProperties.filter { it.findAnnotation<Ignore>() == null }.map { ColumnDef(db, it) }

    class ColumnDef<T : Any> internal constructor(val db: Db, val property: KProperty1<T, *>) {
        val jclazz get() = property.returnType.jvmErasure
        val name = property.findAnnotation<Name>()?.name ?: property.name
        val quotedName = db.quoteColumnName(name)
        val sqlType by lazy { property.returnType.toSqlType(db, property) }
        val isNullable get() = property.returnType.isMarkedNullable
        val isUnique = property.findAnnotation<Unique>() != null
        val isIndex = property.findAnnotation<Index>() != null
    }

    override val table: DbTable<T> get() = this
    override suspend fun query(sql: String, vararg params: Any?): DbResult = db.query(sql, *params)

    suspend fun <R> transaction(callback: (DbTableTransaction<T>) -> R): R = db.transaction {
        callback(DbTableTransaction(this@DbTable, this))
    }
}

class DbTableTransaction<T: Any>(override val table: DbTable<T>, val transaction: DbTransaction) : BaseDbTable<T>() {
    override suspend fun query(sql: String, vararg params: Any?): DbResult = transaction.query(sql, *params)
}


fun KType.toSqlType(db: Db, annotations: KAnnotatedElement): String {
    return when (this.jvmErasure) {
        Int::class -> "INTEGER"
        ByteArray::class -> "BLOB"
        String::class -> {
            val maxLength = annotations.findAnnotation<MaxLength>()
            //if (maxLength != null) "VARCHAR(${maxLength.length})" else "TEXT"
            if (maxLength != null) "VARCHAR(${maxLength.length})" else "VARCHAR"
        }
        else -> "VARCHAR"
    }
}
