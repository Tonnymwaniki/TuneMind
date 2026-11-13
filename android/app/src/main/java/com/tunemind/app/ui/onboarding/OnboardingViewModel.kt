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

    // Always start with the clean branded Splash Screen (WELCOME) on cold app launch
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

    /**
     * Called automatically after the 1.6-second branded Splash Screen finishes:
     * - If the user has already logged in before (Google, Email, or Spotify) or completed onboarding,
     *   auto-login and route straight into the main app (or any remaining permission if not yet completed).
     * - If this is a first-time user who has never logged in, route to AUTH_LOGIN.
     */
    fun onSplashFinishedAutoRoute() {
        authSessionManager.refreshSessionState()
        val session = authSessionManager.sessionFlow.value
        val app = getApplication<Application>()
        val isReturningUser = session.isLoggedIn || session.isSpotifyConnected || session.hasCompletedStartFlow

        if (isReturningUser) {
            if (session.hasCompletedStartFlow && PermissionUtils.hasMicrophonePermission(app)) {
                _currentStep.value = OnboardingStep.COMPLETE
            } else {
                _currentStep.value = evaluateRemainingPermissionStep(app)
            }
        } else {
            _currentStep.value = OnboardingStep.AUTH_LOGIN
        }
    }

    fun proceedFromWelcomeToAuth() {
        onSplashFinishedAutoRoute()
    }

    fun proceedFromAuthToPermissions() {
        authSessionManager.markStartFlowCompleted()
        val app = getApplication<Application>()
        _currentStep.value = evaluateRemainingPermissionStep(app)
    }

    fun checkPermissions() {
        val current = _currentStep.value
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
