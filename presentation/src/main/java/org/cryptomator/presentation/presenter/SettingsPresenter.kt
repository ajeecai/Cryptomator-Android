package org.cryptomator.presentation.presenter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.AsyncTask
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.google.common.base.Optional
import org.cryptomator.data.util.NetworkConnectionCheck
import org.cryptomator.domain.di.PerView
import org.cryptomator.domain.usecases.DoUpdateCheckUseCase
import org.cryptomator.domain.usecases.DoUpdateUseCase
import org.cryptomator.domain.usecases.NoOpResultHandler
import org.cryptomator.domain.usecases.UpdateCheck
import org.cryptomator.generator.Callback
import org.cryptomator.presentation.BuildConfig
import org.cryptomator.presentation.R
import org.cryptomator.presentation.exception.ExceptionHandlers
import org.cryptomator.presentation.logging.Logfiles
import org.cryptomator.presentation.logging.ReleaseLogger
import org.cryptomator.presentation.model.ProgressModel
import org.cryptomator.presentation.service.PhotoContentJob
import org.cryptomator.presentation.ui.activity.view.SettingsView
import org.cryptomator.presentation.ui.dialog.AskIgnoreBatteryOptimizationsDialog
import org.cryptomator.presentation.ui.dialog.UpdateAppAvailableDialog
import org.cryptomator.presentation.ui.dialog.UpdateAppDialog
import org.cryptomator.presentation.util.EmailBuilder
import org.cryptomator.presentation.util.FileUtil
import org.cryptomator.presentation.workflow.PermissionsResult
import org.cryptomator.util.SharedPreferencesHandler
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlin.system.exitProcess
import timber.log.Timber

@PerView
class SettingsPresenter @Inject internal constructor(
	private val updateCheckUseCase: DoUpdateCheckUseCase,  //
	private val updateUseCase: DoUpdateUseCase,  //
	private val networkConnectionCheck: NetworkConnectionCheck,  //
	exceptionMappings: ExceptionHandlers,  //
	private val fileUtil: FileUtil,  //
	private val sharedPreferencesHandler: SharedPreferencesHandler
) : Presenter<SettingsView>(exceptionMappings) {

	fun checkAutoUploadEnabledAndBatteryOptimizationDisabled() {
		if (sharedPreferencesHandler.usePhotoUpload()) {
			showAskIgnoreBatteryOptimizationsDialogWhenDisabled()
		}
	}

	fun onSendErrorReportClicked() {
		view?.showProgress(ProgressModel.GENERIC)
		// no usecase here because the backend is not involved
		CreateErrorReportArchiveTask().execute()
	}

	fun onUploadLogsToServerClicked() {
		view?.showProgress(ProgressModel.GENERIC)
		UploadLogsTask().execute()
	}

	fun onDebugModeChanged(enabled: Boolean) {
		ReleaseLogger.updateDebugMode(enabled)
	}

	private fun sendErrorReport(attachment: File) {
		EmailBuilder.anEmail() //
			.to("support@cryptomator.org") //
			.withSubject(context().getString(R.string.error_report_subject)) //
			.withBody(errorReportEmailBody()) //
			.attach(attachment) //
			.send(activity())
	}

	private fun errorReportEmailBody(): String {
		val variant = when (BuildConfig.FLAVOR) {
			"apkstore" -> {
				"APK Store"
			}
			"fdroid" -> {
				"F-Droid"
			}
			"lite" -> {
				"F-Droid Main Repo"
			}
			"accrescent" -> {
				"Accrescent"
			}
			else -> "Google Play"
		}
		return StringBuilder().append("## ").append(context().getString(R.string.error_report_subject)).append("\n\n") //
			.append("### ").append(context().getString(R.string.error_report_section_summary)).append('\n') //
			.append(context().getString(R.string.error_report_summary_description)).append("\n\n") //
			.append("### ").append(context().getString(R.string.error_report_section_device)).append("\n") //
			.append("Cryptomator v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") ").append(variant).append("\n") //
			.append("Android ").append(Build.VERSION.RELEASE).append(" / API").append(Build.VERSION.SDK_INT).append("\n") //
			.append("Device ").append(Build.MODEL) //
			.toString()
	}

	fun grantLocalStoragePermissionForAutoUpload() {
		val permissions = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
			arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
		} else {
			arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
		}
		requestPermissions(
			PermissionsResultCallbacks.onLocalStoragePermissionGranted(),  //
			R.string.permission_snackbar_auth_auto_upload,  //
			*permissions
		)
	}

	@Callback
	fun onLocalStoragePermissionGranted(result: PermissionsResult) {
		if (result.granted()) {
			PhotoContentJob.scheduleJob(context())
			showAskIgnoreBatteryOptimizationsDialogWhenDisabled()
		} else {
			view?.disableAutoUpload()
		}
	}

	private fun showAskIgnoreBatteryOptimizationsDialogWhenDisabled() {
		val powerManager = context().getSystemService(Context.POWER_SERVICE) as PowerManager
		if (!powerManager.isIgnoringBatteryOptimizations(context().packageName) && !sharedPreferencesHandler.askBatteryOptimizationsDialogDisabled()) {
			view?.showDialog(AskIgnoreBatteryOptimizationsDialog.newInstance())
		}
	}

	fun askIgnoreBatteryOptimizationsAccepted() {
		val intent = Intent()
		intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
		startIntent(intent)
	}

	fun onAskIgnoreBatteryOptimizationsRejected(askAgain: Boolean) {
		if (!askAgain) {
			sharedPreferencesHandler.setAskBatteryOptimizationsDialogDisabled(true)
		}
	}

	fun onCheckUpdateClicked() {
		if (networkConnectionCheck.isPresent) {
			updateCheckUseCase //
				.withVersion(BuildConfig.VERSION_NAME)
				.run(object : NoOpResultHandler<Optional<UpdateCheck>>() {
					override fun onSuccess(result: Optional<UpdateCheck>) {
						if (result.isPresent) {
							updateStatusRetrieved(result.get(), context())
						} else {
							Timber.tag("SettingsPresenter").i("UpdateCheck finished, latest version")
							Toast.makeText(context(), getString(R.string.notification_update_check_finished_latest), Toast.LENGTH_SHORT).show()
						}
						sharedPreferencesHandler.updateExecuted()
						view?.refreshUpdateTimeView()
					}

					override fun onError(e: Throwable) {
						showError(e)
					}
				})
		} else {
			Toast.makeText(context(), R.string.error_update_no_internet, Toast.LENGTH_SHORT).show()
		}
	}

	private fun updateStatusRetrieved(updateCheck: UpdateCheck, context: Context) {
		showNextMessage(updateCheck.releaseNote(), context)
	}

	private fun showNextMessage(message: String, context: Context) {
		if (message.isNotEmpty()) {
			view?.showDialog(UpdateAppAvailableDialog.newInstance(message))
		} else {
			view?.showDialog(UpdateAppAvailableDialog.newInstance(context.getText(R.string.dialog_update_available_message).toString()))
		}
	}

	fun installUpdate() {
		view?.showDialog(UpdateAppDialog.newInstance())
		val uri = fileUtil.contentUriForNewTempFile("cryptomator.apk")
		val file = fileUtil.tempFile("cryptomator.apk")
		updateUseCase //
			.withFile(file) //
			.run(object : NoOpResultHandler<Void?>() {
				override fun onError(e: Throwable) {
					showError(e)
				}

				override fun onSuccess(result: Void?) {
					super.onSuccess(result)
					val intent = Intent(Intent.ACTION_VIEW)
					intent.setDataAndType(uri, "application/vnd.android.package-archive")
					intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
					intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
					context().startActivity(intent)
				}
			})
	}

	fun restartApp() {
		// process gets restarted so just exit it
		exitProcess(0)
	}

	private inner class CreateErrorReportArchiveTask : AsyncTask<Void?, IOException?, File?>() {

		override fun doInBackground(vararg params: Void?): File? {
			return try {
				createErrorReportArchive()
			} catch (e: IOException) {
				publishProgress(e)
				null
			}
		}

		override fun onProgressUpdate(vararg values: IOException?) {
			Timber.e(values[0], "Sending error report failed")
			view?.showError(R.string.screen_settings_error_report_failed)
		}

		override fun onPostExecute(attachment: File?) {
			attachment?.let { sendErrorReport(it) }
			view?.showProgress(ProgressModel.COMPLETED)
		}
	}

	private inner class UploadLogsTask : AsyncTask<Void?, Exception?, Boolean>() {
		override fun doInBackground(vararg params: Void?): Boolean {
			return try {
				val file = createCombinedLogsTxt()
				uploadLogsViaHttp(file)
				true
			} catch (e: Exception) {
				publishProgress(e)
				false
			}
		}

		override fun onProgressUpdate(vararg values: Exception?) {
			val e = values.firstOrNull()
			Timber.e(e, "Upload logs failed")
			val reason = when (e) {
				is java.net.UnknownHostException -> "无法解析服务器主机名"
				is java.net.ConnectException -> "无法连接服务器"
				is java.net.SocketTimeoutException -> "连接或读取超时"
				is javax.net.ssl.SSLHandshakeException, is javax.net.ssl.SSLException -> "TLS/证书错误"
				else -> e?.message ?: "未知错误"
			}
			android.widget.Toast.makeText(context(), "上传失败：$reason", android.widget.Toast.LENGTH_LONG).show()
		}

		override fun onPostExecute(result: Boolean) {
			if (result) {
				Toast.makeText(context(), "日志上传成功", Toast.LENGTH_SHORT).show()
			}
			view?.showProgress(ProgressModel.COMPLETED)
		}
	}

	@Throws(IOException::class)
	private fun uploadLogsViaHttp(file: File) {
		val urlStr = sharedPreferencesHandler.logUploadUrl()
		require(urlStr.isNotEmpty()) { "Log upload URL is empty" }
		val user = sharedPreferencesHandler.logUploadUser()
		val pw = sharedPreferencesHandler.logUploadPassword()

		val boundary = "----CryptomatorBoundary${System.currentTimeMillis()}"
		val lineEnd = "\r\n"
		val twoHyphens = "--"
		val deviceId = android.provider.Settings.Secure.getString(context().contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
		val uploadFileName = "logs-$deviceId.txt"

		val targetUrl = buildUrlWithFilename(urlStr, uploadFileName)
		val url = java.net.URL(targetUrl)
		val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
			// Use PUT to upload raw text file to the resource URL
			requestMethod = "PUT"
			setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
			if (user.isNotEmpty()) {
				val basic = android.util.Base64.encodeToString("$user:$pw".toByteArray(), android.util.Base64.NO_WRAP)
				setRequestProperty("Authorization", "Basic $basic")
			}
			doInput = true
			doOutput = true
			useCaches = false
			connectTimeout = 20000
			readTimeout = 60000
		}

		conn.outputStream.use { os ->
			java.io.FileInputStream(file).use { fis ->
				val buffer = ByteArray(8192)
				while (true) {
					val len = fis.read(buffer)
					if (len <= 0) break
					os.write(buffer, 0, len)
				}
			}
			os.flush()
		}

		val code = conn.responseCode
		if (code !in 200..299) {
			val respMsg = try { conn.responseMessage } catch (_: Exception) { null }
			val detail = buildString {
				append("HTTP ").append(code)
				respMsg?.let { append(' ').append(it) }
			}
			throw IOException(detail)
		}
	}

	@Throws(IOException::class)
	private fun createCombinedLogsTxt(): File {
		val logsDir = File(activity().cacheDir, "logs")
		if (!logsDir.exists() && !logsDir.mkdirs()) {
			throw IOException("Failed to create logs directory")
		}
		val deviceId = android.provider.Settings.Secure.getString(context().contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
		val outFile = File(logsDir, "logs-$deviceId.txt")
		if (outFile.exists()) outFile.delete()

		java.io.FileOutputStream(outFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
			Logfiles.existingLogfiles(activity()).forEach { logfile ->
				writer.appendLine("===== ${logfile.name} =====")
				try {
					logfile.forEachLine(Charsets.UTF_8) { line -> writer.appendLine(line) }
				} catch (e: Exception) {
					writer.appendLine("<failed to read ${logfile.name}: ${e.message}>")
				}
				writer.appendLine()
			}
		}
		return outFile
	}

	@Throws(IOException::class)
	private fun createErrorReportArchive(): File {
		val logfileArchive = prepareLogfileArchive()
		createZipArchive(logfileArchive, Logfiles.logfiles(context()))
		return logfileArchive
	}

	@Throws(IOException::class)
	private fun prepareLogfileArchive(): File {
		val logsDir = File(activity().cacheDir, "logs")
		if (!logsDir.exists() && !logsDir.mkdirs()) {
			throw IOException("Failed to create logs directory")
		}
		val logfileArchive = File(logsDir, "logs.zip")
		deleteIfExists(logfileArchive)
		return logfileArchive
	}

	@Throws(IOException::class)
	private fun createZipArchive(target: File, entries: Iterable<File>) {
		ZipOutputStream(FileOutputStream(target)).use { logs ->
			Logfiles.existingLogfiles(activity()).forEach { logfile ->
				addLogfile(logs, logfile)
			}
		}
	}

	@Throws(IOException::class)
	private fun addLogfile(logs: ZipOutputStream, logfile: File) {
		val entry = ZipEntry(logfile.name)
		entry.time = logfile.lastModified()
		logs.putNextEntry(entry)
		FileInputStream(logfile).use { inputStream ->
			val buffer = ByteArray(4096)
			var count = 0
			while (count != EOF) {
				logs.write(buffer, 0, count)
				count = inputStream.read(buffer)
			}
		}
	}

	private fun deleteIfExists(file: File) {
		if (file.exists()) {
			// noinspection ResultOfMethodCallIgnored
			file.delete()
		}
	}

	private fun buildUrlWithFilename(urlStr: String, fileName: String): String {
		val q = urlStr.indexOf('?')
		val base = if (q >= 0) urlStr.substring(0, q) else urlStr
		val query = if (q >= 0) urlStr.substring(q) else ""
		val lower = base.lowercase()
		return when {
			lower.endsWith("/$fileName".lowercase()) || lower.endsWith(fileName.lowercase()) -> base + query
			base.endsWith("/") -> base + fileName + query
			else -> "$base/$fileName$query"
		}
	}

	companion object {

		private const val EOF = -1
	}

	init {
		unsubscribeOnDestroy(updateCheckUseCase, updateUseCase)
	}
}
