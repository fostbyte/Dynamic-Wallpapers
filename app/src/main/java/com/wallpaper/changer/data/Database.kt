package com.wallpaper.changer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Query("SELECT * FROM albums")
    fun getAllFlow(): Flow<List<Album>>

    @Query("SELECT * FROM albums")
    suspend fun getAll(): List<Album>

    @Query("SELECT * FROM albums WHERE id = :id")
    suspend fun getById(id: Long): Album?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: Album): Long

    @Update
    suspend fun update(album: Album)

    @Delete
    suspend fun delete(album: Album)
}

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE albumId = :albumId")
    fun getPhotosForAlbumFlow(albumId: Long): Flow<List<Photo>>

    @Query("SELECT * FROM photos WHERE albumId = :albumId")
    suspend fun getPhotosForAlbum(albumId: Long): List<Photo>

    @Query("SELECT * FROM photos")
    suspend fun getAllPhotos(): List<Photo>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: Photo): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<Photo>)

    @Query("DELETE FROM photos WHERE albumId = :albumId AND path = :path")
    suspend fun deletePhoto(albumId: Long, path: String)

    @Query("DELETE FROM photos WHERE albumId = :albumId")
    suspend fun deletePhotosForAlbum(albumId: Long)

    @Delete
    suspend fun delete(photo: Photo)
}

@Dao
interface AutomationRuleDao {
    @Query("SELECT * FROM automation_rules ORDER BY priority DESC")
    fun getAllRulesFlow(): Flow<List<AutomationRule>>

    @Query("SELECT * FROM automation_rules ORDER BY priority DESC")
    suspend fun getAllRules(): List<AutomationRule>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AutomationRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<AutomationRule>)

    @Update
    suspend fun update(rule: AutomationRule)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun delete(rule: AutomationRule)
    
    @Query("DELETE FROM automation_rules")
    suspend fun deleteAll()
}

@Dao
interface NodeGraphDao {
    @Query("SELECT * FROM nodes")
    suspend fun getAllNodes(): List<NodeEntity>

    @Query("SELECT * FROM connections")
    suspend fun getAllConnections(): List<ConnectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<NodeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConnections(connections: List<ConnectionEntity>)

    @Query("DELETE FROM nodes")
    suspend fun deleteAllNodes()

    @Query("DELETE FROM connections")
    suspend fun deleteAllConnections()

    @Transaction
    suspend fun saveGraph(nodes: List<NodeEntity>, connections: List<ConnectionEntity>) {
        deleteAllNodes()
        deleteAllConnections()
        insertNodes(nodes)
        insertConnections(connections)
    }
}

@Dao
interface AppSettingDao {
    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun getSetting(key: String): AppSetting?

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    fun getSettingFlow(key: String): Flow<AppSetting?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetting(setting: AppSetting)

    @Query("DELETE FROM app_settings WHERE `key` = :key")
    suspend fun deleteSetting(key: String)
}

@Database(
    entities = [Album::class, Photo::class, AutomationRule::class, NodeEntity::class, ConnectionEntity::class, AppSetting::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun photoDao(): PhotoDao
    abstract fun automationRuleDao(): AutomationRuleDao
    abstract fun nodeGraphDao(): NodeGraphDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "wallpaper_changer_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
