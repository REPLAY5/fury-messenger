package network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.rustore.sdk.appupdate.listener.InstallStateUpdateListener
import ru.rustore.sdk.appupdate.manager.RuStoreAppUpdateManager
import ru.rustore.sdk.appupdate.manager.factory.RuStoreAppUpdateManagerFactory
import ru.rustore.sdk.appupdate.model.AppUpdateInfo
import ru.rustore.sdk.appupdate.model.AppUpdateOptions
import ru.rustore.sdk.appupdate.model.AppUpdateType
import ru.rustore.sdk.appupdate.model.InstallStatus
import ru.rustore.sdk.appupdate.model.UpdateAvailability

class UpdateManagerImpl(
    context: Context,
    scope: CoroutineScope
) : UpdateManager {

    // Менеджер для фоновых проверок и слушателей (привязан к Service/Application context)
    private val ruStoreAppUpdateManager: RuStoreAppUpdateManager = RuStoreAppUpdateManagerFactory.create(context)

    // Кэшируем информацию об обновлении, чтобы использовать её при старте скачивания из Activity
    private var cachedAppUpdateInfo: AppUpdateInfo? = null

    // 1. Готово к установке (Скачано)
    private val _updateReady = MutableStateFlow(false)
    override val updateReady = _updateReady.asStateFlow()

    // 2. Доступно на сервере (Показать кнопку "Обновить")
    private val _updateAvailable = MutableStateFlow(false)
    override val updateAvailable = _updateAvailable.asStateFlow()

    // 3. Прогресс загрузки (0.0 .. 1.0). Если > 0, значит идет скачивание
    private val _updateProgress = MutableStateFlow(0f)
    override val updateProgress = _updateProgress.asStateFlow()

    // Слушатель прогресса (работает в фоне)
    private val installStateUpdateListener = InstallStateUpdateListener { state ->
        when (state.installStatus) {
            InstallStatus.DOWNLOADING -> {
                val bytes = state.bytesDownloaded
                val total = state.totalBytesToDownload

                if (total > 0) {
                    val progress = bytes.toFloat() / total.toFloat()
                    _updateProgress.value = progress + 0.001f
                }

                _updateAvailable.value = true
                _updateReady.value = false
            }

            InstallStatus.DOWNLOADED -> {
                Log.d("RuStoreUpdate", "Download completed. Ready to install.")
                _updateProgress.value = 0f // Сбрасываем прогресс
                _updateAvailable.value = true
                if(!_updateReady.value){
                    _updateReady.value = true
                    performInstall()
                }
            }

            InstallStatus.FAILED -> {
                Log.e("RuStoreUpdate", "Download failed code: ${state.installErrorCode}")
                _updateProgress.value = 0f
                // Если упало, снова показываем кнопку "Обновить", чтобы юзер мог повторить
                _updateAvailable.value = true
            }

            else -> {
                // UNKNOWN, PENDING и т.д.
            }
        }
    }

    init {
        // Регистрируем слушатель сразу при инициализации сервиса
        ruStoreAppUpdateManager.registerListener(installStateUpdateListener)

    }

    /**
     * Точка входа. Вызывается из NetworkService периодически или при старте.
     */
    override fun checkForUpdate() {
        ruStoreAppUpdateManager.getAppUpdateInfo()
            .addOnSuccessListener { appUpdateInfo ->
                val availability = appUpdateInfo.updateAvailability
                val installStatus = appUpdateInfo.installStatus

                Log.d("RuStoreUpdate", "Check result -> Availability: $availability, Status: $installStatus")

                // Сохраняем инфо для дальнейшего использования
                cachedAppUpdateInfo = appUpdateInfo

                when {
                    // 1. Уже скачано -> Можно ставить
                    installStatus == InstallStatus.DOWNLOADED -> {
                        _updateProgress.value = 0f
                        _updateAvailable.value = true
                        _updateReady.value = true
                    }

                    // 2. В процессе скачивания (восстановили состояние после перезапуска)
                    installStatus == InstallStatus.DOWNLOADING -> {
                        _updateAvailable.value = true
                        _updateReady.value = false
                        // Прогресс подхватится слушателем чуть позже
                    }

                    // 3. Обновление доступно, но не начато
                    availability == UpdateAvailability.UPDATE_AVAILABLE -> {
                        _updateReady.value = false
                        _updateProgress.value = 0f
                        _updateAvailable.value = true // Сигнал UI показать виджет
                    }

                    else -> {
                        // Обновлений нет
                        _updateAvailable.value = false
                        _updateReady.value = false
                        _updateProgress.value = 0f
                    }
                }
            }
            .addOnFailureListener { throwable ->
                Log.e("RuStoreUpdate", "Check info error", throwable)
            }
    }

    /**
     * Вызывается из Activity (через ViewModel), когда пользователь нажал кнопку в виджете.
     */
    override fun startUpdateFlow(activity: android.app.Activity) {
        if(_updateReady.value){
            performInstall()
            return
        }

        Log.d("RuStoreUpdate", "Starting update flow from Activity...")

        // Создаем менеджер, привязанный к UI
        val uiManager = RuStoreAppUpdateManagerFactory.create(activity)

        // Ставим фейковый прогресс для UI
        _updateProgress.value = 0.001f

        uiManager.getAppUpdateInfo()
            .addOnSuccessListener { appUpdateInfo ->
                if (appUpdateInfo.updateAvailability == UpdateAvailability.UPDATE_AVAILABLE) {

                    val options = AppUpdateOptions.Builder()
                        .appUpdateType(AppUpdateType.FLEXIBLE)
                        .build()

                    uiManager.startUpdateFlow(appUpdateInfo, options)
                        .addOnSuccessListener { resultCode ->
                            Log.d("RuStoreUpdate", "StartFlow success: $resultCode")
                            if(resultCode==0){
                                _updateProgress.value = 0f //показываем кнопку если уже закачено
                            }
                        }
                        .addOnFailureListener { throwable ->
                            Log.e("RuStoreUpdate", "StartFlow error", throwable)
                            resetUIState()
                        }
                } else {
                    Log.e("RuStoreUpdate", "Update no longer available")
                    resetUIState()
                }
            }
            .addOnFailureListener { throwable ->
                Log.e("RuStoreUpdate", "Failed to re-fetch update info in UI", throwable)
                resetUIState()
            }
    }

    private fun resetUIState() {
        _updateProgress.value = 0f
        _updateAvailable.value = true
    }

    override fun stop() {
        try {
            ruStoreAppUpdateManager.unregisterListener(installStateUpdateListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun performInstall() {
        if (_updateReady.value) {
            Log.d("RuStoreUpdate", "Executing completeUpdate (Install)...")
            ruStoreAppUpdateManager.completeUpdate(
                AppUpdateOptions.Builder()
                    .appUpdateType(AppUpdateType.FLEXIBLE)
                    .build()
            ).addOnFailureListener { throwable ->
                Log.e("RuStoreUpdate", "Complete update error", throwable)
            }
        }
    }
}