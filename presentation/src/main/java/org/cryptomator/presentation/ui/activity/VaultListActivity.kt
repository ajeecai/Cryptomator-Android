package org.cryptomator.presentation.ui.activity

import android.content.Intent
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.Fragment
import org.cryptomator.domain.Vault
import org.cryptomator.generator.Activity
import org.cryptomator.generator.InjectIntent
import org.cryptomator.presentation.CryptomatorApp
import org.cryptomator.presentation.R
import org.cryptomator.presentation.databinding.ActivityLayoutObscureAwareBinding
import org.cryptomator.presentation.intent.Intents.browseFilesIntent
import org.cryptomator.presentation.intent.Intents.settingsIntent
import org.cryptomator.presentation.intent.VaultListIntent
import org.cryptomator.presentation.model.CloudFolderModel
import org.cryptomator.presentation.model.ProgressModel
import org.cryptomator.presentation.model.VaultModel
import org.cryptomator.presentation.presenter.VaultListPresenter
import org.cryptomator.presentation.service.OpenWritableFileNotification
import org.cryptomator.presentation.ui.activity.view.VaultListView
import org.cryptomator.presentation.ui.bottomsheet.AddVaultBottomSheet
import org.cryptomator.presentation.ui.bottomsheet.SettingsVaultBottomSheet
import org.cryptomator.presentation.ui.callback.VaultListCallback
import org.cryptomator.presentation.ui.dialog.AskForLockScreenDialog
import org.cryptomator.presentation.ui.dialog.BetaConfirmationDialog
import org.cryptomator.presentation.ui.dialog.CBCPasswordVaultsMigrationDialog
import org.cryptomator.presentation.ui.dialog.UpdateAppAvailableDialog
import org.cryptomator.presentation.ui.dialog.UpdateAppDialog
import org.cryptomator.presentation.ui.dialog.VaultDeleteConfirmationDialog
import org.cryptomator.presentation.ui.dialog.VaultRenameDialog
import org.cryptomator.presentation.ui.fragment.VaultListFragment
import org.cryptomator.presentation.ui.layout.ObscuredAwareCoordinatorLayout.Listener
import org.cryptomator.presentation.util.BiometricAuthenticationMigration
import javax.inject.Inject

@Activity
class VaultListActivity : BaseActivity<ActivityLayoutObscureAwareBinding>(ActivityLayoutObscureAwareBinding::inflate), //
	VaultListView, //
	VaultListCallback, //
	AskForLockScreenDialog.Callback, //
	UpdateAppAvailableDialog.Callback, //
	UpdateAppDialog.Callback, //
	BetaConfirmationDialog.Callback, //
	CBCPasswordVaultsMigrationDialog.Callback, //
	BiometricAuthenticationMigration.Callback {

	@Inject
	lateinit var vaultListPresenter: VaultListPresenter

	@InjectIntent
	lateinit var vaultListIntent: VaultListIntent

	override fun onCreate(savedInstanceState: Bundle?) {
		installSplashScreen()
		super.onCreate(savedInstanceState)
	}

	override fun onWindowFocusChanged(hasFocus: Boolean) {
		super.onWindowFocusChanged(hasFocus)
		vaultListPresenter.onWindowFocusChanged(hasFocus)
	}

	override fun setupView() {
		setupToolbar()
		vaultListPresenter.prepareView()
		binding.activityRootView.setOnFilteredTouchEventForSecurityListener(object : Listener {
			override fun onFilteredTouchEventForSecurity() {
				vaultListPresenter.onFilteredTouchEventForSecurity()
			}
		})

		if (stopEditFilePressed() && sharedPreferencesHandler.keepUnlockedWhileEditing()) {
			hideNotification()
			unSuspendLock()
		}
	}

	private fun stopEditFilePressed(): Boolean {
		return vaultListIntent.stopEditFileNotification() != null && vaultListIntent.stopEditFileNotification()
	}

	private fun hideNotification() {
		OpenWritableFileNotification(context(), Uri.EMPTY).hide()
	}

	private fun unSuspendLock() {
		val cryptomatorApp = activity().application as CryptomatorApp
		cryptomatorApp.unSuspendLock()
	}

	override fun createFragment(): Fragment = VaultListFragment()

	override fun snackbarView(): View = vaultListFragment().rootView()

	override fun getCustomMenuResource(): Int = R.menu.menu_vault_list

	override fun onPrepareOptionsMenu(menu: Menu): Boolean {
		// Show send log option only when Debug mode is enabled
		val item = menu.findItem(R.id.action_upload_logs)
		item?.isVisible = sharedPreferencesHandler.debugMode()
		return super.onPrepareOptionsMenu(menu)
	}

	override fun onMenuItemSelected(itemId: Int): Boolean = when (itemId) {
		R.id.action_settings -> {
			vaultListPresenter.startIntent(settingsIntent())
			true
		}
		R.id.action_upload_logs -> {
			uploadLogsNowFromMain()
			true
		}
		else -> super.onMenuItemSelected(itemId)
	}

	private fun uploadLogsNowFromMain() {
		val prefs = org.cryptomator.util.SharedPreferencesHandler(applicationContext)
		val urlStr = prefs.logUploadUrl()
		if (urlStr.isEmpty()) {
			android.widget.Toast.makeText(this, "请先在设置里配置上传URL", android.widget.Toast.LENGTH_LONG).show()
			return
		}

		// show in-progress spinner on UI
		showProgress(ProgressModel.GENERIC)

		Thread {
			val user = prefs.logUploadUser()
			val pw = prefs.logUploadPassword()
			val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
			val uploadFileName = "logs-$deviceId.txt"

			try {
				// build combined logs
				val logsDir = java.io.File(cacheDir, "logs")
				if (!logsDir.exists()) logsDir.mkdirs()
				val outFile = java.io.File(logsDir, uploadFileName)
				if (outFile.exists()) outFile.delete()
				java.io.FileOutputStream(outFile, true).bufferedWriter(Charsets.UTF_8).use { writer ->
					org.cryptomator.presentation.logging.Logfiles.existingLogfiles(this).forEach { logfile ->
						writer.appendLine("===== ${logfile.name} =====")
						try {
							logfile.forEachLine(Charsets.UTF_8) { line: String -> writer.appendLine(line) }
						} catch (e: Exception) {
							writer.appendLine("<failed to read ${logfile.name}: ${e.message}>")
						}
						writer.appendLine()
					}
				}

				// build target URL with filename
				val targetUrl = buildUrlWithFilename(urlStr, uploadFileName)

				val url = java.net.URL(targetUrl)
				val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
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
					java.io.FileInputStream(outFile).use { fis ->
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
				if (code in 200..299) {
					runOnUiThread { android.widget.Toast.makeText(this, "日志上传成功", android.widget.Toast.LENGTH_SHORT).show() }
				} else {
					val msg = try { conn.responseMessage } catch (_: Exception) { null }
					val reason = buildString {
						append("HTTP ").append(code)
						msg?.let { append(' ').append(it) }
					}
					runOnUiThread { android.widget.Toast.makeText(this, "上传失败：$reason", android.widget.Toast.LENGTH_LONG).show() }
				}
			} catch (e: java.net.UnknownHostException) {
				runOnUiThread { android.widget.Toast.makeText(this, "上传失败：无法解析服务器主机名", android.widget.Toast.LENGTH_LONG).show() }
			} catch (e: java.net.ConnectException) {
				runOnUiThread { android.widget.Toast.makeText(this, "上传失败：无法连接服务器", android.widget.Toast.LENGTH_LONG).show() }
			} catch (e: java.net.SocketTimeoutException) {
				runOnUiThread { android.widget.Toast.makeText(this, "上传失败：连接或读取超时", android.widget.Toast.LENGTH_LONG).show() }
			} catch (e: javax.net.ssl.SSLException) {
				runOnUiThread { android.widget.Toast.makeText(this, "上传失败：TLS/证书错误", android.widget.Toast.LENGTH_LONG).show() }
			} catch (e: Exception) {
				runOnUiThread { android.widget.Toast.makeText(this, "上传失败：${e.message ?: "未知错误"}", android.widget.Toast.LENGTH_LONG).show() }
			} finally {
				runOnUiThread { showProgress(ProgressModel.COMPLETED) }
			}
		}.start()
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

	override fun isVaultLocked(vaultModel: VaultModel): Boolean {
		return vaultListFragment().isVaultLocked(vaultModel)
	}

	private fun setupToolbar() {
		binding.mtToolbar.toolbar.title = getString(R.string.app_name).uppercase()
		setSupportActionBar(binding.mtToolbar.toolbar)
	}

	override fun showAddVaultBottomSheet() {
		showDialog(AddVaultBottomSheet())
	}

	override fun showRenameDialog(vaultModel: VaultModel) {
		showDialog(VaultRenameDialog.newInstance(vaultModel))
	}

	override fun rowMoved(fromPosition: Int, toPosition: Int) {
		vaultListFragment().rowMoved(fromPosition, toPosition)
	}

	override fun vaultMoved(vaults: List<VaultModel>) {
		vaultListFragment().vaultMoved(vaults)
	}

	override fun migrateCBCEncryptedPasswordVaults(vaults: List<VaultModel>) {
		val biometricAuthenticationMigration = BiometricAuthenticationMigration(this, context(), sharedPreferencesHandler.useConfirmationInFaceUnlockBiometricAuthentication())
		biometricAuthenticationMigration.migrateVaultsPassword(vaultListFragment(), vaults)
	}

	override fun showVaultSettingsDialog(vaultModel: VaultModel) {
		val vaultSettingDialog = //
			SettingsVaultBottomSheet.newInstance(vaultModel)
		vaultSettingDialog.show(supportFragmentManager, "VaultSettings")
	}

	override fun renderVaultList(vaultModelCollection: List<VaultModel>) {
		vaultListFragment().showVaults(vaultModelCollection)
	}

	override fun showVaultCreationHint() {
		vaultListFragment().showVaultCreationHint()
	}

	override fun hideVaultCreationHint() {
		vaultListFragment().hideVaultCreationHint()
	}

	override fun deleteVaultFromAdapter(vaultId: Long) {
		vaultListFragment().deleteVaultFromAdapter(vaultId)
	}

	override fun addOrUpdateVault(vault: VaultModel) {
		vaultListFragment().addOrUpdateVault(vault)
	}

	override fun navigateToVaultContent(vault: VaultModel, decryptedRoot: CloudFolderModel) {
		vaultListPresenter.startIntent(browseFilesIntent().withTitle(vault.name).withFolder(decryptedRoot))
	}

	override fun renameVault(vaultModel: VaultModel) {
		vaultListFragment().addOrUpdateVault(vaultModel)
	}

	override fun onAddExistingVault() {
		vaultListPresenter.onAddExistingVault()
	}

	override fun onCreateVault() {
		vaultListPresenter.onCreateVault()
	}

	override fun onDeleteVaultClick(vaultModel: VaultModel) {
		VaultDeleteConfirmationDialog.newInstance(vaultModel) //
			.show(supportFragmentManager, "VaultDeleteConfirmationDialog")
	}

	override fun onRenameVaultClick(vaultModel: VaultModel) {
		vaultListPresenter.onRenameVaultClicked(vaultModel)
	}

	override fun onLockVaultClick(vaultModel: VaultModel) {
		vaultListPresenter.onVaultLockClicked(vaultModel)
	}

	override fun onChangePasswordClick(vaultModel: VaultModel) {
		vaultListPresenter.onChangePasswordClicked(vaultModel)
	}

	override fun onRenameClick(vaultModel: VaultModel, newVaultName: String) {
		vaultListPresenter.renameVault(vaultModel, newVaultName)
	}

	override fun onDeleteConfirmedClick(vaultModel: VaultModel) {
		vaultListPresenter.deleteVault(vaultModel)
	}

	override fun onAskForLockScreenFinished(setScreenLock: Boolean) {
		vaultListPresenter.onAskForLockScreenFinished(setScreenLock)
	}

	private fun vaultListFragment(): VaultListFragment = //
		getCurrentFragment(R.id.fragment_container) as VaultListFragment

	override fun onUpdateAppDialogLoaded() {
		showProgress(ProgressModel.GENERIC)
	}

	override fun installUpdate() {
		vaultListPresenter.installUpdate()
	}

	override fun cancelUpdateClicked() {
		closeDialog()
	}

	override fun showUpdateWebsite() {
		val url = "https://cryptomator.org/android/"
		val intent = Intent(Intent.ACTION_VIEW)
		intent.data = Uri.parse(url)
		startActivity(intent)
	}

	override fun onAskForBetaConfirmationFinished() {
		sharedPreferencesHandler.setBetaScreenDialogAlreadyShown(true)
	}

	override fun onCBCPasswordVaultsMigrationClicked(cbcVaults: List<Vault>) {
		vaultListPresenter.cBCPasswordVaultsMigrationClicked(cbcVaults)
	}

	override fun onCBCPasswordVaultsMigrationRejected(cbcVaults: List<Vault>) {
		vaultListPresenter.cBCPasswordVaultsMigrationRejected(cbcVaults)
	}

	override fun onBiometricAuthenticationMigrationFinished(vaults: List<VaultModel>) {
		vaultListPresenter.biometricAuthenticationMigrationFinished(vaults)
	}

	override fun onBiometricAuthenticationFailed(vaults: List<VaultModel>) {
		vaultListPresenter.biometricAuthenticationFailed(vaults)
	}

	override fun onBiometricKeyInvalidated(vaults: List<VaultModel>) {
		vaultListPresenter.biometricKeyInvalidated(vaults)
	}

}
