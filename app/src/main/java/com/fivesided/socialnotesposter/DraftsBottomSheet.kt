package com.fivesided.socialnotesposter

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DraftsBottomSheet(private val onDraftSelected: (NoteDraft) -> Unit) : BottomSheetDialogFragment() {

    private lateinit var db: AppDatabase
    private lateinit var listView: ListView
    private var drafts: List<NoteDraft> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.bottom_sheet_drafts, container, false)
        listView = view.findViewById(R.id.drafts_list_view)
        db = AppDatabase.getDatabase(requireContext())

        loadDrafts()

        listView.setOnItemClickListener { _, _, position, _ ->
            onDraftSelected(drafts[position])
            dismiss()
        }

        listView.setOnItemLongClickListener { _, _, position, _ ->
            showDeleteConfirmationDialog(drafts[position])
            true
        }

        return view
    }

    private fun loadDrafts() {
        lifecycleScope.launch {
            drafts = db.draftDao().getAllDrafts().first()
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, drafts.map { it.content })
            listView.adapter = adapter
        }
    }

    private fun showDeleteConfirmationDialog(draft: NoteDraft) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete Draft")
            .setMessage("Are you sure you want to delete this draft?")
            .setPositiveButton("Delete") { _, _ ->
                deleteDraft(draft)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteDraft(draft: NoteDraft) {
        lifecycleScope.launch {
            db.draftDao().delete(draft)
            loadDrafts() // Refresh the list
        }
    }
}
