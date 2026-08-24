package me.avinas.tempo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import me.avinas.tempo.ui.navigation.AppNavigation
import me.avinas.tempo.ui.onboarding.BatteryOptimizationScreen
import me.avinas.tempo.ui.onboarding.HowItWorksScreen
import me.avinas.tempo.ui.onboarding.OnboardingViewModel
import me.avinas.tempo.ui.onboarding.PrivacyExplainerScreen
import me.avinas.tempo.ui.onboarding.WelcomeScreen
import me.avinas.tempo.ui.permissions.PermissionScreen
import me.avinas.tempo.ui.theme.TempoTheme
import me.avinas.tempo.utils.OemBackgroundHelper
import me.avinas.tempo.worker.ServiceHealthWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    @javax.inject.Inject
    lateinit var walkthroughController: me.avinas.tempo.ui.components.WalkthroughController
    
    private val navigationTrigger = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        checkNavigationIntent(intent)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            TempoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TempoApp(
                        walkthroughController = walkthroughController,
                        onSetupComplete = {
                            // Schedule the health worker after setup is complete
                            ServiceHealthWorker.schedule(this)
                        },
                        navigationTrigger = navigationTrigger.value
                    )
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        checkNavigationIntent(intent)
    }
    
    private fun checkNavigationIntent(intent: Intent?) {
        if (intent?.hasExtra("navigate_to") == true) {
            val dest = intent.getStringExtra("navigate_to")
            if (dest != null) {
                navigationTrigger.value = dest
                intent.removeExtra("navigate_to")
            }
        }
    }
}

enum class OnboardingStep {
    WELCOME, HOW_IT_WORKS, PRIVACY, PERMISSION, BATTERY, RESTORE, COMPLETED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TempoApp(
    walkthroughController: me.avinas.tempo.ui.components.WalkthroughController,
    onSetupComplete: () -> Unit,
    navigationTrigger: String? = null,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    // Wait for onboarding status to be loaded before deciding the initial step
    val initialStep = remember(uiState.isLoading, uiState.isOnboardingCompleted) {
        when {
            uiState.isLoading -> null
            uiState.isOnboardingCompleted -> OnboardingStep.COMPLETED
            else -> OnboardingStep.WELCOME
        }
    }
    
    var currentStep by remember(initialStep) { 
        mutableStateOf(initialStep ?: OnboardingStep.WELCOME) 
    }

    // If onboarding is already completed in DataStore, jump to COMPLETED
    LaunchedEffect(uiState.isOnboardingCompleted) {
        if (uiState.isOnboardingCompleted) {
            currentStep = OnboardingStep.COMPLETED
            onSetupComplete()
        }
    }
    
    // Show nothing while loading to prevent welcome screen flash
    if (uiState.isLoading) {
        return
    }

    // Xiaomi guidance popup state
    val isXiaomiDevice = remember { OemBackgroundHelper.isXiaomiDevice() }
    var showXiaomiGuidance by remember { mutableStateOf(false) }
    var xiaomiGuidanceDismissed by remember { mutableStateOf(false) }
    var localNavigationTrigger by remember { mutableStateOf<String?>(null) }

    // Show Xiaomi guidance popup after onboarding completes for first-time Xiaomi users
    LaunchedEffect(uiState.isOnboardingCompleted, uiState.xiaomiGuidanceShown) {
        if (uiState.isOnboardingCompleted && isXiaomiDevice && !uiState.xiaomiGuidanceShown && !xiaomiGuidanceDismissed) {
            showXiaomiGuidance = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentStep) {
            OnboardingStep.WELCOME -> {
                WelcomeScreen(
                    onGetStarted = { currentStep = OnboardingStep.HOW_IT_WORKS },
                    onSkip = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    }
                )
            }
            OnboardingStep.HOW_IT_WORKS -> {
                HowItWorksScreen(
                    onNext = { currentStep = OnboardingStep.PRIVACY },
                    onSkip = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    }
                )
            }
            OnboardingStep.PRIVACY -> {
                PrivacyExplainerScreen(
                    onNext = { currentStep = OnboardingStep.PERMISSION },
                    onSkip = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    }
                )
            }
            OnboardingStep.PERMISSION -> {
                PermissionScreen(
                    onPermissionGranted = { currentStep = OnboardingStep.BATTERY },
                    onSkip = { currentStep = OnboardingStep.BATTERY }
                )
            }
            OnboardingStep.BATTERY -> {
                BatteryOptimizationScreen(
                    onOptimize = {
                        currentStep = OnboardingStep.RESTORE
                    },
                    onSkip = {
                        currentStep = OnboardingStep.RESTORE
                    }
                )
            }
            OnboardingStep.RESTORE -> {
                me.avinas.tempo.ui.onboarding.RestoreScreen(
                    onFinish = {
                        viewModel.completeOnboarding()
                        currentStep = OnboardingStep.COMPLETED
                    },
                    onBack = {
                        currentStep = OnboardingStep.BATTERY
                    }
                )
            }
            OnboardingStep.COMPLETED -> {
                AppNavigation(
                    walkthroughController = walkthroughController,
                    onResetToOnboarding = {
                        currentStep = OnboardingStep.WELCOME
                    },
                    navigationTrigger = localNavigationTrigger ?: navigationTrigger
                )
                // Clear local trigger after passing it
                LaunchedEffect(localNavigationTrigger) {
                    if (localNavigationTrigger != null) {
                        localNavigationTrigger = null
                    }
                }
            }
        }

        // Xiaomi first-time guidance popup
        if (showXiaomiGuidance) {
            me.avinas.tempo.ui.components.XiaomiGuidancePopup(
                onDismiss = {
                    showXiaomiGuidance = false
                    xiaomiGuidanceDismissed = true
                    viewModel.markXiaomiGuidanceShown()
                },
                onConfigure = {
                    showXiaomiGuidance = false
                    xiaomiGuidanceDismissed = true
                    viewModel.markXiaomiGuidanceShown()
                    localNavigationTrigger = "background_protection"
                }
            )
        }
    }
}
