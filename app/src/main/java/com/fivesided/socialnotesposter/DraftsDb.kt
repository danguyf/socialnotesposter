import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drafts")
data class NoteDraft(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface DraftDao {
    @Insert suspend fun insert(draft: NoteDraft)
    @Query("SELECT * FROM drafts ORDER BY timestamp DESC") fun getAll(): Flow<List<NoteDraft>>
    @Delete suspend fun delete(draft: NoteDraft)
}

@Database(entities = [NoteDraft::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
}