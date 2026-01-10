package com.fivesided.socialnotesposter

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var storage: AuthStorage
    private lateinit var db: AppDatabase
    private lateinit var etNoteContent: EditText
    private lateinit var btnPost: Button
    private lateinit var tvCharCount: TextView

    private var currentDraft: NoteDraft? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize dependencies
        storage = AuthStorage(this)
        ApiClient.init(this)
        db = AppDatabase.getDatabase(this)

        // Bind UI elements
        etNoteContent = findViewById(R.id.etNoteContent)
        btnPost = findViewById(R.id.btnPost)
        tvCharCount = findViewById(R.id.tvCharCount)
        val btnNew = findViewById<ImageButton>(R.id.btnNew)
        val btnSave = findViewById<ImageButton>(R.id.btnSave)

        // 1. Check Authentication Status
        if (!storage.hasCredentials()) {
            showSetupDialog()
        } else {
            syncDrafts()
        }

        // 2. Handle Incoming "Shared" Text
        handleIncomingIntent(intent)

        // 3. Live Character Counter
        etNoteContent.addTextChangedListener {
            val count = it?.length ?: 0
            tvCharCount.text = "$count / 280"
            btnPost.isEnabled = count > 0
        }

        // 4. Post Logic
        btnPost.setOnClickListener {
            Log.d(TAG, "Post button clicked.")
            val content = etNoteContent.text.toString()
            performPost(content)
        }

        // 5. New Note Logic
        btnNew.setOnClickListener {
            etNoteContent.text.clear()
            currentDraft = null
            Toast.makeText(this, "New note started.", Toast.LENGTH_SHORT).show()
        }

        // 6. Save/Update Draft Logic
        btnSave.setOnClickListener {
            val content = etNoteContent.text.toString()
            if (content.isNotEmpty()) {
                saveOrUpdateDraft(content)
            } else {
                Toast.makeText(this, "Nothing to save.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_drafts -> {
                DraftsBottomSheet { draft ->
                    etNoteContent.setText(draft.content)
                    currentDraft = draft
                }.show(supportFragmentManager, "drafts")
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                etNoteContent.setText(it)
                currentDraft = null
            }
        }
    }

    private fun stripHtml(html: String): String {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim()
    }

    private fun syncDrafts() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = ApiClient.service.getDrafts()
                if (response.isSuccessful) {
                    val wpDrafts = response.body() ?: emptyList()
                    val localDraftsAtStart = db.draftDao().getAllDrafts().first()
                    val wpIdsInResponse = wpDrafts.map { it.id }.toSet()

                    // 1. Delete local drafts whose wpId is no longer on WP
                    localDraftsAtStart.forEach { localDraft ->
                        if (localDraft.wpId != null && !wpIdsInResponse.contains(localDraft.wpId)) {
                            Log.d(TAG, "Deleting orphaned local draft: ${localDraft.wpId}")
                            db.draftDao().delete(localDraft)
                        }
                    }

                    // 2. Sync WP -> Local (Update or Create)
                    val localDraftsAfterDelete = db.draftDao().getAllDrafts().first()
                    wpDrafts.forEach { wpDraft ->
                        val wpContent = stripHtml(wpDraft.content.raw ?: wpDraft.content.rendered)
                        val wpModified = wpDraft.modified_gmt.time
                        val localMatch = localDraftsAfterDelete.find { it.wpId == wpDraft.id }

                        if (localMatch == null) {
                            // De-duplicate: check if same content exists locally without wpId
                            val contentMatch = localDraftsAfterDelete.find { it.wpId == null && it.content == wpContent }
                            if (contentMatch != null) {
                                db.draftDao().update(contentMatch.copy(wpId = wpDraft.id, lastModified = wpModified))
                            } else {
                                db.draftDao().insert(NoteDraft(wpId = wpDraft.id, content = wpContent, lastModified = wpModified))
                            }
                        } else if (wpModified > localMatch.lastModified) {
                            db.draftDao().update(localMatch.copy(content = wpContent, lastModified = wpModified))
                        }
                    }

                    // 3. Sync Local -> WP (Upload new ones)
                    val finalLocalDrafts = db.draftDao().getAllDrafts().first()
                    finalLocalDrafts.forEach { localDraft ->
                        if (localDraft.wpId == null) {
                            Log.d(TAG, "Uploading new local draft to WP...")
                            val createResponse = ApiClient.service.postNote(SocialNoteRequest(localDraft.content, "draft"))
                            if (createResponse.isSuccessful) {
                                createResponse.body()?.let {
                                    db.draftDao().update(localDraft.copy(wpId = it.id, lastModified = it.modified_gmt.time))
                                }
                            }
                        } else {
                            val wpMatch = wpDrafts.find { it.id == localDraft.wpId }
                            if (wpMatch != null && localDraft.lastModified > (wpMatch.modified_gmt.time + 1000)) {
                                Log.d(TAG, "Updating existing WP draft: ${localDraft.wpId}")
                                ApiClient.service.updateNote(localDraft.wpId, SocialNoteRequest(localDraft.content, "draft"))
                            }
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "Sync complete")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
            }
        }
    }

    private fun performPost(content: String) {
        Log.d(TAG, "performPost called with content: $content")
        btnPost.isEnabled = false
        btnPost.text = "Posting..."

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Coroutine started, making network call...")
                val post = SocialNoteRequest(content, "publish")
                
                val response = if (currentDraft?.wpId != null) {
                    ApiClient.service.updateNote(currentDraft!!.wpId!!, post)
                } else {
                    ApiClient.service.postNote(post)
                }

                if (response.isSuccessful) {
                    Log.d(TAG, "Post successful!")

                    val originalDraft = currentDraft
                    if (originalDraft != null) {
                        if (content == originalDraft.content) {
                            deleteDraftCompletely(originalDraft)
                        } else {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Delete Original Draft?")
                                    .setMessage("You posted an edited version of a draft. Do you want to delete the original?")
                                    .setPositiveButton("Delete") { _, _ ->
                                        deleteDraftCompletely(originalDraft)
                                    }
                                    .setNegativeButton("Keep", null)
                                    .show()
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        etNoteContent.text.clear()
                        currentDraft = null
                        Toast.makeText(this@MainActivity, "Published!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.w(TAG, "Post failed with code: ${response.code()}")
                    saveAsDraftOnError(content, "Server error (${response.code()}). Saved to drafts.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during post", e)
                saveAsDraftOnError(content, "Offline. Saved to drafts.")
            } finally {
                Log.d(TAG, "Finally block executed.")
                withContext(Dispatchers.Main) {
                    btnPost.isEnabled = true
                    btnPost.text = "Post"
                }
            }
        }
    }

    fun deleteDraftCompletely(draft: NoteDraft) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.draftDao().delete(draft)
            draft.wpId?.let {
                try {
                    ApiClient.service.deleteNote(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete from WP", e)
                }
            }
        }
    }

    private fun saveOrUpdateDraft(content: String) {
        lifecycleScope.launch {
            val draft = currentDraft
            val now = System.currentTimeMillis()
            if (draft != null) {
                val updatedDraft = draft.copy(content = content, lastModified = now)
                db.draftDao().update(updatedDraft)
                currentDraft = updatedDraft
                
                withContext(Dispatchers.IO) {
                    try {
                        if (updatedDraft.wpId != null) {
                            ApiClient.service.updateNote(updatedDraft.wpId, SocialNoteRequest(content, "draft"))
                        } else {
                            val res = ApiClient.service.postNote(SocialNoteRequest(content, "draft"))
                            if (res.isSuccessful) {
                                res.body()?.let {
                                    db.draftDao().update(updatedDraft.copy(wpId = it.id, lastModified = it.modified_gmt.time))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update WP draft", e)
                    }
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Draft updated.", Toast.LENGTH_SHORT).show()
                }
            } else {
                val newDraft = NoteDraft(content = content, lastModified = now)
                db.draftDao().insert(newDraft)
                
                val savedDraft = db.draftDao().getAllDrafts().first().first()
                currentDraft = savedDraft
                
                withContext(Dispatchers.IO) {
                    try {
                        val res = ApiClient.service.postNote(SocialNoteRequest(content, "draft"))
                        if (res.isSuccessful) {
                            res.body()?.let {
                                db.draftDao().update(savedDraft.copy(wpId = it.id, lastModified = it.modified_gmt.time))
                                val updatedFromDb = db.draftDao().getAllDrafts().first().firstOrNull { d -> d.id == savedDraft.id }
                                if (updatedFromDb != null) {
                                    currentDraft = updatedFromDb
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create WP draft", e)
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Draft saved.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveAsDraftOnError(content: String, message: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            db.draftDao().insert(NoteDraft(content = content, lastModified = System.currentTimeMillis()))
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSetupDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_setup, null)
        val urlInput = dialogView.findViewById<EditText>(R.id.etBlogUrl)
        val userInput = dialogView.findViewById<EditText>(R.id.etUsername)
        val passInput = dialogView.findViewById<EditText>(R.id.etAppPassword)

        AlertDialog.Builder(this)
            .setTitle("WordPress Setup")
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton("Save") { _, _ ->
                storage.saveCredentials(
                    urlInput.text.toString().trim(),
                    userInput.text.toString().trim(),
                    passInput.text.toString().trim()
                )
                ApiClient.init(this)
                Toast.makeText(this, "Setup complete!", Toast.LENGTH_SHORT).show()
                syncDrafts()
            }
            .show()
    }
}
