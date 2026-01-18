package com.fivesided.socialnotesposter

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            tvCharCount.text = getString(R.string.char_count_format, count, 266)
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
            Toast.makeText(this, R.string.new_note_started, Toast.LENGTH_SHORT).show()
        }

        // 6. Save/Update Draft Logic
        btnSave.setOnClickListener {
            val content = etNoteContent.text.toString()
            if (content.isNotEmpty()) {
                saveOrUpdateDraft(content)
            } else {
                Toast.makeText(this, R.string.nothing_to_save, Toast.LENGTH_SHORT).show()
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
        return HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().trim()
    }

    private fun syncDrafts() {
        lifecycleScope.launch(Dispatchers.IO) {
            var downloaded = 0
            var uploaded = 0
            var deleted = 0

            try {
                // Fetch drafts from WordPress with cache buster to force fresh results
                val response = ApiClient.service.getDrafts(cb = System.currentTimeMillis())
                if (response.isSuccessful) {
                    val wpDrafts = response.body() ?: emptyList()
                    val localDraftsAtStart = db.draftDao().getAllDrafts().first()
                    val wpIdsInResponse = wpDrafts.map { it.id }.toSet()

                    // Phase 1: Handle Deletions
                    localDraftsAtStart.forEach { localDraft ->
                        if (localDraft.wpId != null && !wpIdsInResponse.contains(localDraft.wpId)) {
                            // Verify actually gone with correct status/context
                            val checkResponse = ApiClient.service.getNote(localDraft.wpId)
                            if (checkResponse.code() == 404) {
                                db.draftDao().delete(localDraft)
                                deleted++
                            }
                        }
                    }

                    // Phase 2: WP -> Local Sync
                    val localDraftsAfterDelete = db.draftDao().getAllDrafts().first()
                    wpDrafts.forEach { wpDraft ->
                        val wpContent = stripHtml(wpDraft.content.raw ?: wpDraft.content.rendered)
                        val wpModified = wpDraft.modified_gmt.time
                        val localMatch = localDraftsAfterDelete.find { it.wpId == wpDraft.id }

                        if (localMatch == null) {
                            val contentMatch = localDraftsAfterDelete.find { it.wpId == null && it.content == wpContent }
                            if (contentMatch != null) {
                                db.draftDao().update(contentMatch.copy(wpId = wpDraft.id, lastModified = wpModified))
                            } else {
                                db.draftDao().insert(NoteDraft(wpId = wpDraft.id, content = wpContent, lastModified = wpModified))
                                downloaded++
                            }
                        } else if (wpModified > localMatch.lastModified) {
                            db.draftDao().update(localMatch.copy(content = wpContent, lastModified = wpModified))
                        }
                    }

                    // Phase 3: Local -> WP Sync
                    val finalLocalDrafts = db.draftDao().getAllDrafts().first()
                    finalLocalDrafts.forEach { localDraft ->
                        if (localDraft.wpId == null) {
                            val createResponse = ApiClient.service.postNote(SocialNoteRequest(localDraft.content, "draft"))
                            if (createResponse.isSuccessful) {
                                createResponse.body()?.let {
                                    db.draftDao().update(localDraft.copy(wpId = it.id, lastModified = it.modified_gmt.time))
                                    uploaded++
                                }
                            }
                        } else {
                            val wpMatch = wpDrafts.find { it.id == localDraft.wpId }
                            if (wpMatch != null && localDraft.lastModified > (wpMatch.modified_gmt.time + 1000)) {
                                ApiClient.service.updateNote(localDraft.wpId, SocialNoteRequest(localDraft.content, "draft"))
                            }
                        }
                    }
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(R.string.sync_status, downloaded, uploaded, deleted), Toast.LENGTH_LONG).show()
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
        btnPost.setText(R.string.posting_status)

        lifecycleScope.launch {
            try {
                val post = SocialNoteRequest(content, "publish")
                
                val response = if (currentDraft?.wpId != null) {
                    ApiClient.service.updateNote(currentDraft!!.wpId!!, post)
                } else {
                    ApiClient.service.postNote(post)
                }

                if (response.isSuccessful) {
                    // Success! Delete the local draft record if one exists.
                    currentDraft?.let {
                        db.draftDao().delete(it)
                    }

                    withContext(Dispatchers.Main) {
                        etNoteContent.text.clear()
                        currentDraft = null
                        Toast.makeText(this@MainActivity, R.string.published_toast, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    saveAsDraftOnError(content, getString(R.string.server_error_saved, response.code()))
                }
            } catch (e: Exception) {
                saveAsDraftOnError(content, getString(R.string.offline_saved))
            } finally {
                withContext(Dispatchers.Main) {
                    btnPost.isEnabled = true
                    btnPost.setText(R.string.post_button)
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
                    Toast.makeText(this@MainActivity, R.string.draft_updated, Toast.LENGTH_SHORT).show()
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
                                val updatedFromDb = db.draftDao().getAllDrafts().first().find { d -> d.id == savedDraft.id }
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
                    Toast.makeText(this@MainActivity, R.string.draft_saved, Toast.LENGTH_SHORT).show()
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
            .setTitle(R.string.wordpress_setup_title)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.save_button) { _, _ ->
                storage.saveCredentials(
                    urlInput.text.toString().trim(),
                    userInput.text.toString().trim(),
                    passInput.text.toString().trim()
                )
                ApiClient.init(this)
                Toast.makeText(this, R.string.setup_complete, Toast.LENGTH_SHORT).show()
                syncDrafts()
            }
            .show()
    }
}
