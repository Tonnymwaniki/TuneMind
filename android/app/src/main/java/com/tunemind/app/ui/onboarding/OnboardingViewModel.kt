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

    private val _currentStep = MutableStateFlow(evaluateInitialStep())
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    private var alwaysShowAuthFirstOnLaunch: Boolean = false
    private var authCompletedForThisLaunch: Boolean = false

    fun configureLaunchGate(alwaysShowAuthFirst: Boolean) {
        alwaysShowAuthFirstOnLaunch = alwaysShowAuthFirst
        if (alwaysShowAuthFirst && !authCompletedForThisLaunch) {
            _currentStep.value = OnboardingStep.AUTH_LOGIN
        } else {
            _currentStep.value = evaluateInitialStep()
        }
    }

    private fun evaluateInitialStep(): OnboardingStep {
        authSessionManager.refreshSessionState()
        val session = authSessionManager.sessionFlow.value
        val app = getApplication<Application>()
        if (alwaysShowAuthFirstOnLaunch && !authCompletedForThisLaunch) {
            return OnboardingStep.AUTH_LOGIN
        }
        if (!session.hasCompletedAccountOnboarding) {
            return OnboardingStep.AUTH_LOGIN
        }
        val isReturningUser = session.isLoggedIn || session.hasCompletedStartFlow
        return if (isReturningUser) {
            if (session.hasCompletedStartFlow && PermissionUtils.hasMicrophonePermission(app)) {
                OnboardingStep.COMPLETE
            } else {
                evaluateRemainingPermissionStep(app)
            }
        } else {
            OnboardingStep.AUTH_LOGIN
        }
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
     * Routes cleanly: users who haven't completed/skipped the welcome sign-in start at AUTH_LOGIN;
     * returning signed-in users advance directly to remaining permissions or COMPLETE.
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
        _currentStep.value = evaluateInitialStep()
    }
}
