package com.tunemind.app.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.tunemind.app.util.PermissionUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class OnboardingStep {
    WELCOME,
    MICROPHONE,
    NOTIFICATION,
    BATTERY,
    COMPLETE
}

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val _currentStep = MutableStateFlow(OnboardingStep.WELCOME)
    val currentStep: StateFlow<OnboardingStep> = _currentStep.asStateFlow()

    fun checkPermissions() {
        val context = getApplication<Application>()
        if (!PermissionUtils.hasMicrophonePermission(context)) {
            _currentStep.value = OnboardingStep.MICROPHONE
        } else if (!PermissionUtils.hasNotificationListenerPermission(context)) {
            _currentStep.value = OnboardingStep.NOTIFICATION
        } else if (!PermissionUtils.isIgnoringBatteryOptimizations(context)) {
            _currentStep.value = OnboardingStep.BATTERY
        } else {
            _currentStep.value = OnboardingStep.COMPLETE
        }
    }

    fun nextStep() {
        checkPermissions() // Re-evaluate based on current state
    }
}
