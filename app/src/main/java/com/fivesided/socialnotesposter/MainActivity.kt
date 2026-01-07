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

    private fun performPost(content: String) {
        Log.d(TAG, "performPost called with content: $content")
        btnPost.isEnabled = false
        btnPost.text = "Posting..."

        lifecycleScope.launch {
            try {
                Log.d(TAG, "Coroutine started, making network call...")
                val post = SocialNotePost(content, "publish")
                val response = ApiClient.service.postNote(post)

                if (response.isSuccessful) {
                    Log.d(TAG, "Post successful!")

                    val originalDraft = currentDraft
                    if (originalDraft != null) {
                        if (content == originalDraft.content) {
                            db.draftDao().delete(originalDraft)
                        } else {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Delete Original Draft?")
                                    .setMessage("You posted an edited version of a draft. Do you want to delete the original?")
                                    .setPositiveButton("Delete") { _, _ ->
                                        lifecycleScope.launch { db.draftDao().delete(originalDraft) }
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

    private fun saveOrUpdateDraft(content: String) {
        lifecycleScope.launch {
            val draft = currentDraft
            if (draft != null) {
                // Update existing draft
                val updatedDraft = draft.copy(content = content)
                db.draftDao().update(updatedDraft)
                currentDraft = updatedDraft
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Draft updated.", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Save as new draft
                val newDraft = NoteDraft(content = content)
                db.draftDao().insert(newDraft)
                // To allow for further edits to be saved as updates, we set the new draft as current
                // We need to retrieve it to get its auto-generated ID
                val draftsList = db.draftDao().getAllDrafts().first()
                if (draftsList.isNotEmpty()) {
                    currentDraft = draftsList.first()
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Draft saved.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun saveAsDraftOnError(content: String, message: String) {
        db.draftDao().insert(NoteDraft(content = content))
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
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
            }
            .show()
    }
}
