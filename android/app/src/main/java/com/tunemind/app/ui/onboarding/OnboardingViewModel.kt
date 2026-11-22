package com.tunemind.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tunemind.app.data.auth.TuneMindAuthSessionManager
import com.tunemind.app.data.supabase.SupabaseIntelligenceSyncManager
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    private var alwaysShowAuthFirstOnLaunch: Boolean = false
    private var authCompletedForThisLaunch: Boolean = false

    private val _currentStep = MutableStateFlow(OnboardingStep.AUTH_LOGIN)
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    fun hasPassedLaunchGate(alwaysShowAuthFirst: Boolean): Boolean {
        return !alwaysShowAuthFirst || authCompletedForThisLaunch
    }

    fun configureLaunchGate(alwaysShowAuthFirst: Boolean) {
        alwaysShowAuthFirstOnLaunch = alwaysShowAuthFirst
        if (alwaysShowAuthFirst && !authCompletedForThisLaunch) {
            _currentStep.value = OnboardingStep.AUTH_LOGIN
        } else {
            _currentStep.value = evaluateInitialStep()
        }
    }

    private fun evaluateInitialStep(): OnboardingStep {
        val session = authSessionManager.sessionFlow.value
        val app = getApplication<Application>()
        val hasExplicitAccount = session.primaryProvider == "GOOGLE" || session.primaryProvider == "EMAIL"
        if (alwaysShowAuthFirstOnLaunch && !authCompletedForThisLaunch) {
            return OnboardingStep.AUTH_LOGIN
        }
        if (!session.isLoggedIn || !hasExplicitAccount || !session.hasCompletedAccountOnboarding) {
            return OnboardingStep.AUTH_LOGIN
        }
        if (session.hasCompletedStartFlow) {
            return OnboardingStep.COMPLETE
        }
        return evaluateRemainingPermissionStep(app)
    }

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
     * Routes cleanly: first-time or signed-out users start at AUTH_LOGIN;
     * returning signed-in users advance directly to COMPLETE.
     */
    fun onSplashFinishedAutoRoute() {
        _currentStep.value = evaluateInitialStep()
    }

    fun proceedFromWelcomeToAuth() {
        onSplashFinishedAutoRoute()
    }

    fun proceedFromAuthToPermissions() {
        authCompletedForThisLaunch = true
        authSessionManager.markAccountOnboardingCompleted()
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                SupabaseIntelligenceSyncManager.restoreUserAccountSnapshotFromCloud(app, force = true)
            }
        }
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
        _currentStep.value = evaluateInitialStep()
    }
}
