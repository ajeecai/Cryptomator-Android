package org.cryptomator.presentation.ui.dialog

import android.content.DialogInterface
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.cryptomator.generator.Dialog
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.DialogPerFileConflictBinding

@Dialog
class PerFileConflictDialog : BaseDialog<PerFileConflictDialog.Callback, DialogPerFileConflictBinding>(DialogPerFileConflictBinding::inflate) {

    interface Callback {
        fun onPerFileConflictReplace(fileName: String)
        fun onPerFileConflictSkip(fileName: String)
        fun onPerFileConflictCancelBatch()
    }

    override fun setupDialog(builder: AlertDialog.Builder): android.app.Dialog {
        val fileName = requireArguments().getString(FILE_NAME_ARG) ?: ""
        builder.setTitle(getString(R.string.dialog_per_file_conflict_title))
            .setPositiveButton(getString(R.string.dialog_per_file_conflict_replace)) { _: DialogInterface?, _: Int -> callback?.onPerFileConflictReplace(fileName) }
            .setNegativeButton(getString(R.string.dialog_per_file_conflict_skip)) { _: DialogInterface?, _: Int -> callback?.onPerFileConflictSkip(fileName) }
            .setNeutralButton(getString(R.string.dialog_per_file_conflict_cancel_batch)) { _: DialogInterface?, _: Int -> callback?.onPerFileConflictCancelBatch() }
            .setOnCancelListener { callback?.onPerFileConflictCancelBatch() }
        return builder.create()
    }

    override fun setupView() {
        val fileName = requireArguments().getString(FILE_NAME_ARG) ?: ""
        binding.tvMessage.text = String.format(getString(R.string.dialog_per_file_conflict_message), fileName)
    }

    companion object {
        private const val FILE_NAME_ARG = "fileName"
        fun newInstance(fileName: String): PerFileConflictDialog {
            val dialog = PerFileConflictDialog()
            val args = Bundle()
            args.putString(FILE_NAME_ARG, fileName)
            dialog.arguments = args
            return dialog
        }
    }
}
