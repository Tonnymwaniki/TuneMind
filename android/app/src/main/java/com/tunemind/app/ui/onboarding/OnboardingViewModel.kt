package com.tunemind.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OnboardingStep {
    WELCOME,
    AUTH_LOGIN,
    MICROPHONE,
    NOTIFICATION,
    BATTERY,
    COMPLETE
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val authSessionManager = TuneMindAuthSessionManager.getInstance(application)

    // Always start at the Welcome / Splash screen on every app launch
    private val _currentStep = MutableStateFlow(OnboardingStep.WELCOME)
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    private fun evaluateRemainingPermissionStep(app: Application): OnboardingStep {
        return when {
            !PermissionUtils.hasMicrophonePermission(app) -> OnboardingStep.MICROPHONE
            !PermissionUtils.hasNotificationListenerPermission(app) -> OnboardingStep.NOTIFICATION
            !PermissionUtils.isIgnoringBatteryOptimizations(app) -> OnboardingStep.BATTERY
            else -> {
                authSessionManager.markStartFlowCompleted()
                OnboardingStep.COMPLETE
            }
        }
    }

    fun proceedFromWelcomeToAuth() {
        _currentStep.value = OnboardingStep.AUTH_LOGIN
    }

    fun proceedFromAuthToPermissions() {
        authSessionManager.markStartFlowCompleted()
        val app = getApplication<Application>()
        _currentStep.value = evaluateRemainingPermissionStep(app)
    }

    fun checkPermissions() {
        val current = _currentStep.value
        // Do not auto-skip the Welcome or Auth Login screens before the user interacts with them
        if (current == OnboardingStep.WELCOME || current == OnboardingStep.AUTH_LOGIN) {
            authSessionManager.refreshSessionState()
            return
        }
        val app = getApplication<Application>()
        _currentStep.value = evaluateRemainingPermissionStep(app)
    }

    fun skipCurrentPermissionStep() {
        val app = getApplication<Application>()
        when (_currentStep.value) {
            OnboardingStep.MICROPHONE -> {
                if (!PermissionUtils.hasNotificationListenerPermission(app)) {
                    _currentStep.value = OnboardingStep.NOTIFICATION
                } else if (!PermissionUtils.isIgnoringBatteryOptimizations(app)) {
                    _currentStep.value = OnboardingStep.BATTERY
                } else {
                    authSessionManager.markStartFlowCompleted()
                    _currentStep.value = OnboardingStep.COMPLETE
                }
            }
            OnboardingStep.NOTIFICATION -> {
                if (!PermissionUtils.isIgnoringBatteryOptimizations(app)) {
                    _currentStep.value = OnboardingStep.BATTERY
                } else {
                    authSessionManager.markStartFlowCompleted()
                    _currentStep.value = OnboardingStep.COMPLETE
                }
            }
            OnboardingStep.BATTERY -> {
                authSessionManager.markStartFlowCompleted()
                _currentStep.value = OnboardingStep.COMPLETE
            }
            else -> checkPermissions()
        }
    }

    fun resetToWelcome() {
        _currentStep.value = OnboardingStep.WELCOME
    }
}
