package org.cryptomator.presentation.ui.dialog

import android.content.DialogInterface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.cryptomator.generator.Dialog
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.DialogUploadLoadingBinding
import org.cryptomator.presentation.databinding.ViewDialogErrorBinding
import org.cryptomator.presentation.model.FileProgressStateModel
import org.cryptomator.presentation.model.ProgressModel
import org.cryptomator.presentation.model.ProgressStateModel
import org.cryptomator.presentation.util.ResourceHelper

@Dialog
class UploadCloudFileDialog : BaseProgressErrorDialog<UploadCloudFileDialog.Callback, DialogUploadLoadingBinding>(DialogUploadLoadingBinding::inflate) {

	private var numberOfFileCurrentlyUploaded = 0
	private var encryptionProgressMeansTheNextFileIsUploaded = true

	interface Callback {

		fun onUploadCanceled()
	}

	override fun onStart() {
		super.onStart()
		showProgress(ProgressModel.GENERIC)
		allowClosingDialog(false)
		enableOrientationChange(false)
		val dialog = dialog as AlertDialog?
		dialog?.let {
			val cancelButton = dialog.getButton(android.app.Dialog.BUTTON_NEUTRAL)
			cancelButton.setOnClickListener {
				callback?.onUploadCanceled()
				cancelButton.isEnabled = false
				// Close the dialog immediately after cancel to reflect user action.
				dismissAllowingStateLoss()
			}
		}
	}

	override fun setupDialog(builder: AlertDialog.Builder): android.app.Dialog {
		return builder //
			.setTitle(effectiveTitle()) //
			.setNeutralButton(getString(R.string.dialog_upload_file_cancel_button)) { _: DialogInterface?, _: Int -> } //
			.create()
	}

	private fun effectiveTitle(): String {
		return getString(R.string.dialog_upload_file_title)
	}

	override fun setupView() {}

	override fun showProgress(progress: ProgressModel) {
		super.showProgress(progress)
		if (progress.state() === ProgressStateModel.COMPLETED) {
			dismissAllowingStateLoss()
		} else {
			updateRemainingFilesText(progress)
			if (progress.state().imageResourceId() != 0) {
				binding.llDialogIntermediateProgress.ivProgressIcon.setImageDrawable(ResourceHelper.getDrawable(progress.state().imageResourceId()))
			}
			// Compute overall progress across all files instead of per-file only.
			val total = numberOfUploadedFiles().coerceAtLeast(1)
			val perFilePercent = progress.progress().coerceIn(0, 100)
			// numberOfFileCurrentlyUploaded counts fully completed files so far.
			val completedFiles = numberOfFileCurrentlyUploaded.coerceAtLeast(0)
			val overall = (((completedFiles * 100) + perFilePercent).toFloat() / total).toInt().coerceIn(0, 100)
			binding.llDialogIntermediateProgress.pbDialog.progress = overall
		}
	}

	private fun updateRemainingFilesText(progress: ProgressModel) {
		if (progress.state() is FileProgressStateModel) {
			updateRemainingFilesText(progress.state() as FileProgressStateModel)
		}
	}

	private fun updateRemainingFilesText(state: FileProgressStateModel) {
		if (state.`is`(FileProgressStateModel.ENCRYPTION)) {
			if (encryptionProgressMeansTheNextFileIsUploaded) {
				encryptionProgressMeansTheNextFileIsUploaded = false
				numberOfFileCurrentlyUploaded++
				binding.fileUpload.text = String.format(getString(R.string.dialog_upload_file_remaining), numberOfFileCurrentlyUploaded, numberOfUploadedFiles())
			}
		} else {
			encryptionProgressMeansTheNextFileIsUploaded = true
		}
	}

	private fun numberOfUploadedFiles(): Int {
		return requireArguments().getSerializable(ARG_NUMBER_OF_UPLOADED_FILES) as Int
	}

	override fun updateProgress(progress: Int) {
		binding.llDialogIntermediateProgress.pbDialog.progress = progress
	}

	override fun dialogProgressLayout(): LinearLayout {
		return binding.llDialogIntermediateProgress.llProgress
	}

	override fun dialogProgressTextView(): TextView {
		return binding.llDialogIntermediateProgress.tvProgress
	}

	override fun dialogErrorBinding(): ViewDialogErrorBinding {
		return binding.llDialogError
	}

	companion object {

		private const val ARG_NUMBER_OF_UPLOADED_FILES = "totalUploadingFiles"
		fun newInstance(numberOfUploadedFiles: Int): UploadCloudFileDialog {
			val args = Bundle()
			args.putSerializable(ARG_NUMBER_OF_UPLOADED_FILES, numberOfUploadedFiles)
			val fragment = UploadCloudFileDialog()
			fragment.arguments = args
			return fragment
		}
	}
}
