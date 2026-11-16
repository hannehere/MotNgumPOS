package vn.motngum.agent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class StoredOrder(
    val id: Long,
    val source: String,
    val code: String,
    val customer: String,
    val note: String,
    val items: List<Item>,
    val status: String,          // new | printed | done | cancelled
    val changedAfterPrint: Boolean,
    val printCount: Int,         // -1 = in lỗi / chưa in, không tự in lại
    val rawText: String,
    val screenshot: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val cupCount: Int get() = items.sumOf { it.qty }
}

/** Lưu đơn trong SQLite trên máy (không cần máy tính / internet). */
class OrderStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "motngum.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                source TEXT NOT NULL, code TEXT NOT NULL DEFAULT '', customer TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '', items_json TEXT NOT NULL DEFAULT '[]',
                status TEXT NOT NULL DEFAULT 'new', changed_after_print INTEGER NOT NULL DEFAULT 0,
                print_count INTEGER NOT NULL DEFAULT 0, raw_text TEXT NOT NULL DEFAULT '',
                screenshot TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_code ON orders(source, code, created_at)")
        db.execSQL("CREATE INDEX idx_status ON orders(status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun get(id: Long): StoredOrder? = readableDatabase
        .rawQuery("SELECT * FROM orders WHERE id=?", arrayOf(id.toString())).use { if (it.moveToFirst()) row(it) else null }

    @Synchronized
    fun list(limit: Int = 150): List<StoredOrder> = readableDatabase
        .rawQuery("SELECT * FROM orders ORDER BY id DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }

    @Synchronized
    fun findRecent(source: String, code: String, withinHours: Int = 12): StoredOrder? {
        if (code.isEmpty()) return null
        val since = System.currentTimeMillis() - withinHours * 3_600_000L
        return readableDatabase.rawQuery(
            "SELECT * FROM orders WHERE source=? AND code=? AND created_at>=? ORDER BY id DESC LIMIT 1",
            arrayOf(source, code, since.toString()),
        ).use { if (it.moveToFirst()) row(it) else null }
    }

    @Synchronized
    fun insert(source: String, order: ParsedOrder, raw: String, printCount: Int = 0): StoredOrder {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insertOrThrow("orders", null, ContentValues().apply {
            put("source", source); put("code", order.code); put("customer", order.customer)
            put("note", order.note); put("items_json", itemsToJson(order.items)); put("raw_text", raw)
            put("print_count", printCount); put("created_at", now); put("updated_at", now)
        })
        if (order.code.isEmpty()) update(id, ContentValues().apply { put("code", "T$id") })
        return get(id)!!
    }

    /**
     * Thêm đơn mới hoặc cập nhật đơn cùng mã trong 12 giờ (Grab dùng lại mã GF-xxx mỗi ngày).
     * Trả về (đơn, hành động): created | updated | unchanged | changed_after_print.
     */
    @Synchronized
    fun upsertParsed(source: String, order: ParsedOrder, raw: String): Pair<StoredOrder, String> {
        val existing = findRecent(source, order.code) ?: return insert(source, order, raw) to "created"
        val newJson = itemsToJson(order.items)
        if (newJson == itemsToJson(existing.items) || order.cupCount < existing.cupCount) return existing to "unchanged"
        if (existing.status == "new") {
            update(existing.id, ContentValues().apply {
                put("items_json", newJson); put("raw_text", raw)
                if (order.customer.isNotEmpty()) put("customer", order.customer)
                if (order.note.isNotEmpty()) put("note", order.note)
            })
            return get(existing.id)!! to "updated"
        }
        if (order.cupCount > existing.cupCount) {
            update(existing.id, ContentValues().apply {
                put("items_json", newJson); put("raw_text", raw); put("changed_after_print", 1)
            })
            return get(existing.id)!! to "changed_after_print"
        }
        return existing to "unchanged"
    }

    @Synchronized
    fun setStatus(id: Long, status: String) = update(id, ContentValues().apply { put("status", status) })

    @Synchronized
    fun markPrinted(id: Long) {
        writableDatabase.execSQL(
            "UPDATE orders SET status=CASE WHEN status='new' THEN 'printed' ELSE status END," +
                " print_count=MAX(print_count,0)+1, changed_after_print=0 WHERE id=?",
            arrayOf(id),
        )
    }

    @Synchronized
    fun markPrintFailed(id: Long) =
        writableDatabase.execSQL("UPDATE orders SET print_count=-1 WHERE id=? AND print_count=0", arrayOf(id))

    @Synchronized
    fun setScreenshot(id: Long, path: String) = update(id, ContentValues().apply { put("screenshot", path) }, touch = false)

    /** Đơn mới, chưa in, không thay đổi trong delayMs => tới lúc in. */
    @Synchronized
    fun dueForPrint(delayMs: Long): List<StoredOrder> = readableDatabase.rawQuery(
        "SELECT * FROM orders WHERE status='new' AND print_count=0 AND items_json!='[]' AND updated_at<=? ORDER BY id",
        arrayOf((System.currentTimeMillis() - delayMs).toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    @Synchronized
    fun deleteOlderThan(days: Int) {
        writableDatabase.delete("orders", "created_at<?", arrayOf((System.currentTimeMillis() - days * 86_400_000L).toString()))
    }

    private fun update(id: Long, cv: ContentValues, touch: Boolean = true) {
        if (touch) cv.put("updated_at", System.currentTimeMillis())
        writableDatabase.update("orders", cv, "id=?", arrayOf(id.toString()))
    }

    private fun row(c: Cursor): StoredOrder {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n)) ?: ""
        fun i(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        return StoredOrder(
            id = i("id"), source = s("source"), code = s("code"), customer = s("customer"), note = s("note"),
            items = itemsFromJson(s("items_json")), status = s("status"),
            changedAfterPrint = i("changed_after_print") != 0L, printCount = i("print_count").toInt(),
            rawText = s("raw_text"), screenshot = s("screenshot"), createdAt = i("created_at"), updatedAt = i("updated_at"),
        )
    }

    companion object {
        @Volatile
        private var inst: OrderStore? = null
        fun get(context: Context): OrderStore = inst ?: synchronized(this) { inst ?: OrderStore(context).also { inst = it } }

        fun itemsToJson(items: List<Item>): String = JSONArray().apply {
            items.forEach {
                put(JSONObject().put("name", it.name).put("qty", it.qty).put("options", JSONArray(it.options))
                    .put("note", it.note).put("price", it.price))
            }
        }.toString()

        fun itemsFromJson(s: String): List<Item> {
            val arr = runCatching { JSONArray(s) }.getOrElse { return emptyList() }
            return List(arr.length()) { idx ->
                val o = arr.getJSONObject(idx)
                val opts = o.optJSONArray("options")
                Item(
                    name = o.optString("name"), qty = o.optInt("qty", 1),
                    options = MutableList(opts?.length() ?: 0) { opts!!.getString(it) },
                    note = o.optString("note"), price = o.optString("price"),
                )
            }
        }
    }
}
