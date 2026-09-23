package cc.lifefuck.minichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地聊天记录数据库。
 *
 * 设计原则：
 * - 聊天记录明文只保存在用户手机，不上传、不上云。
 * - 退出重进后从本地读取历史明文，避免端到端加密 payload 因缺少自己的 key 而无法解密自己发的消息。
 * - 服务端只保留加密 payload，本地保留解密后的明文副本。
 * - 所有数据库操作均在 Dispatchers.IO 中执行，避免阻塞 UI 线程。
 */
class LocalMessageDb(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "local_chat.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "messages"

        // 列名
        private const val COL_SERVER_ID = "server_id"
        private const val COL_USERNAME = "username"
        private const val COL_CONTENT = "content"
        private const val COL_TIME = "time_str"
        private const val COL_AVATAR = "avatar"
        private const val COL_CREATED_AT = "created_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // server_id 是 INTEGER PRIMARY KEY，会自动映射到 SQLite 的 rowid 并生成主键索引，无需额外建索引
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_SERVER_ID INTEGER PRIMARY KEY,
                $COL_USERNAME TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_TIME TEXT NOT NULL,
                $COL_AVATAR TEXT,
                $COL_CREATED_AT INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    /**
     * 插入或替换一条本地消息。
     *
     * @param serverId 服务端消息 ID（主键，去重）
     * @param username 发送者昵称
     * @param content 解密后的明文内容
     * @param time 显示时间 HH:mm
     * @param avatar 发送者头像 base64，可为空
     */
    suspend fun insertOrReplace(
        serverId: Int,
        username: String,
        content: String,
        time: String,
        avatar: String? = null
    ) = withContext(Dispatchers.IO) {
        if (serverId <= 0) return@withContext
        val cv = ContentValues().apply {
            put(COL_SERVER_ID, serverId)
            put(COL_USERNAME, username)
            put(COL_CONTENT, content)
            put(COL_TIME, time)
            put(COL_AVATAR, avatar)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /**
     * 批量保存消息列表。
     */
    suspend fun saveAll(messages: List<Msg>) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            messages.filter { it.id > 0 && it.content.isNotBlank() }.forEach {
                insertOrReplaceSync(it.id, it.username, it.content, it.time, it.avatar)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 读取全部本地消息，按 server_id 升序。
     */
    suspend fun loadAll(): List<Msg> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Msg>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COL_SERVER_ID, COL_USERNAME, COL_CONTENT, COL_TIME, COL_AVATAR),
            null, null, null, null,
            "$COL_SERVER_ID ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    Msg(
                        id = cursor.getInt(cursor.getColumnIndexOrThrow(COL_SERVER_ID)),
                        username = cursor.getString(cursor.getColumnIndexOrThrow(COL_USERNAME)),
                        content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
                        time = cursor.getString(cursor.getColumnIndexOrThrow(COL_TIME)),
                        avatar = cursor.getString(cursor.getColumnIndexOrThrow(COL_AVATAR))
                            ?.takeIf { it.isNotBlank() }
                    )
                )
            }
        }
        list
    }

    /**
     * 清空本地聊天记录。
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        writableDatabase.delete(TABLE_NAME, null, null)
    }

    /**
     * 同步插入，仅用于事务内部批量写入。
     */
    private fun insertOrReplaceSync(
        serverId: Int,
        username: String,
        content: String,
        time: String,
        avatar: String? = null
    ) {
        if (serverId <= 0) return
        val cv = ContentValues().apply {
            put(COL_SERVER_ID, serverId)
            put(COL_USERNAME, username)
            put(COL_CONTENT, content)
            put(COL_TIME, time)
            put(COL_AVATAR, avatar)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            TABLE_NAME,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
