package com.example.sunoseva;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
// Removed unused imports for Map, HashMap, Arrays

/**
 * MainActivity - Main entry point for SunoSeva
 * 
 * This activity handles:
 * - UI initialization and user interactions
 * - Permission management (microphone)
 * - Coordinating Speech-to-Text, Gemini API, and Text-to-Speech
 * - State management across the voice interaction flow
 * 
 * Voice Interaction Flow:
 * 1. User presses mic button
 * 2. SpeechHandler listens and converts speech to text
 * 3. Text is validated and sent to GeminiService (which now also handles app intent recognition)
 * 4. Response is displayed and spoken via TextToSpeechHandler, or an app is launched.
 * 
 * Why all handlers as separate classes?
 * - Clean separation of concerns
 * - MainActivity only handles UI and coordination
 * - Easier to test individual components
 * - Better maintainability
 */
public class MainActivity extends AppCompatActivity 
        implements SpeechHandler.SpeechCallback, TextToSpeechHandler.TTSCallback {

    private static final String TAG = "MainActivity";

    // ==================== Handler Instances ====================
    private SpeechHandler speechHandler;
    private TextToSpeechHandler ttsHandler;
    private GeminiService geminiService;
    private FirebaseHistoryService firebaseHistoryService;

    // ==================== UI Components ====================
    private FloatingActionButton micButton;
    private FloatingActionButton replayButton;
    private FloatingActionButton stopButton;
    private Spinner languageSpinner;
    private TextView statusText;
    private TextView recognizedText;
    private TextView responseText;
    private ImageView statusIcon;
    private MaterialCardView statusCard;
    private MaterialCardView responseCard;
    private View loadingOverlay;

    // ==================== State Variables ====================
    private AppState currentState = AppState.IDLE;
    private String selectedLanguageCode = "te-IN";  // Default: Telugu
    private String lastResponse = "";
    private String lastQueryLanguageCode = "en-IN";
    private String lastPartialSpeech = "";
    private int consecutiveSpeechFailures = 0;
    private boolean isManualInputDialogShowing = false;

    // ==================== Threading ====================
    // Handler for UI updates from background threads
    private Handler mainHandler;
    // Executor for API calls (don't block UI thread)
    private ExecutorService executorService;

    // ==================== Animation ====================
    private ObjectAnimator pulseAnimator;

    // ==================== Permission Launcher ====================
    private ActivityResultLauncher<String> requestPermissionLauncher;

    // Removed appLaunchCommands map

    /**
     * App states for managing UI and logic flow
     * 
     * Why enum?
     * - Clear state machine
     * - Prevents invalid state combinations
     * - Easy to add new states if needed
     */
    private enum AppState {
        IDLE,           // Waiting for user to press mic
        LISTENING,      // Recording user's speech
        PROCESSING,     // Sending to Gemini API
        SPEAKING,       // TTS reading response
        ERROR           // Error state, can retry
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize threading utilities
        mainHandler = new Handler(Looper.getMainLooper());
        executorService = Executors.newSingleThreadExecutor();

        // Setup permission launcher
        setupPermissionLauncher();

        // Initialize UI
        initializeUI();

        // Setup language spinner
        setupLanguageSpinner();

        // Initialize handlers
        initializeHandlers();

        // Removed call to setupAppLaunchCommands()

        // Check and request permissions
        checkAndRequestPermissions();

        Log.d(TAG, "MainActivity created");
    }

    /**
     * Initialize all UI component references
     */
    private void initializeUI() {
        // Buttons
        micButton = findViewById(R.id.micButton);
        replayButton = findViewById(R.id.replayButton);
        stopButton = findViewById(R.id.stopButton);

        // Spinner
        languageSpinner = findViewById(R.id.languageSpinner);

        // Text views
        statusText = findViewById(R.id.statusText);
        recognizedText = findViewById(R.id.recognizedText);
        responseText = findViewById(R.id.responseText);

        // Image views
        statusIcon = findViewById(R.id.statusIcon);

        // Cards
        statusCard = findViewById(R.id.statusCard);
        responseCard = findViewById(R.id.responseCard);

        // Loading overlay
        loadingOverlay = findViewById(R.id.loadingOverlay);

        // Setup click listeners
        setupClickListeners();
    }

    /**
     * Setup click listeners for buttons
     */
    private void setupClickListeners() {
        // Main microphone button
        micButton.setOnClickListener(v -> {
            if (currentState == AppState.IDLE || currentState == AppState.ERROR) {
                startListening();
            } else if (currentState == AppState.LISTENING) {
                stopListening();
            }
        });

        // Replay button - replay last response
        replayButton.setOnClickListener(v -> {
            if (ttsHandler != null && !lastResponse.isEmpty()) {
                setState(AppState.SPEAKING);
                ttsHandler.setLanguage(lastQueryLanguageCode);
                ttsHandler.speak(lastResponse);
            }
        });

        // Stop button - stop current operation
        stopButton.setOnClickListener(v -> {
            stopCurrentOperation();
        });
    }

    /**
     * Setup language selection spinner
     */
    private void setupLanguageSpinner() {
        String[] languages = getResources().getStringArray(R.array.supported_languages);
        String[] languageCodes = getResources().getStringArray(R.array.language_codes);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                languages
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageSpinner.setAdapter(adapter);

        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLanguageCode = languageCodes[position];
                Log.d(TAG, "Language selected: " + selectedLanguageCode);
                
                // Update handlers with new language
                if (speechHandler != null) {
                    speechHandler.setLanguage(selectedLanguageCode);
                }
                if (ttsHandler != null) {
                    ttsHandler.setLanguage(selectedLanguageCode);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep default
            }
        });
    }

    /**
     * Initialize all handler classes
     */
    private void initializeHandlers() {
        // Initialize Speech Handler
        speechHandler = new SpeechHandler(this, this);
        speechHandler.setLanguage(selectedLanguageCode);

        // Initialize TTS Handler
        ttsHandler = new TextToSpeechHandler(this, this);

        // Initialize Gemini Service with provider keys from BuildConfig
        geminiService = new GeminiService(
                BuildConfig.OPENAI_API_KEY,
                BuildConfig.OPENROUTER_API_KEY,
                BuildConfig.GROQ_API_KEY
        );
        firebaseHistoryService = new FirebaseHistoryService(this);

        Log.d(TAG, "All handlers initialized");
    }

    // Removed setupAppLaunchCommands() method

    // ==================== Permission Handling ====================

    /**
     * Setup permission launcher for Android 6.0+ runtime permissions
     */
    private void setupPermissionLauncher() {
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        Log.d(TAG, "Microphone permission granted");
                        Toast.makeText(this, "మైక్రోఫోన్ సిద్ధంగా ఉంది", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.w(TAG, "Microphone permission denied");
                        showPermissionDeniedDialog();
                    }
                }
        );
    }

    /**
     * Check if permission is granted, request if not
     */
    private void checkAndRequestPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    /**
     * Show dialog when permission is denied
     */
    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("మైక్రోఫోన్ అనుమతి అవసరం")
                .setMessage("ఈ యాప్ మీ గొంతు వినడానికి మైక్రోఫోన్ ఉపయోగిస్తుంది. దయచేసి సెట్టింగ్స్‌లో అనుమతి ఇవ్వండి.")
                .setPositiveButton("సరే", (dialog, which) -> dialog.dismiss())
                .setCancelable(false)
                .show();
    }

    // ==================== Voice Interaction Flow ====================

    /**
     * Start listening for user's voice
     */
    private void startListening() {
        // Check permission first
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions();
            return;
        }

        // Check if speech recognition is available
        if (!SpeechHandler.isAvailable(this)) {
            showError(getString(R.string.error_speech_not_available));
            return;
        }

        Log.d(TAG, "Starting voice input");
        setState(AppState.LISTENING);
        speechHandler.startListening();
    }

    /**
     * Stop listening (user cancelled or timeout)
     */
    private void stopListening() {
        Log.d(TAG, "Stopping voice input");
        if (speechHandler != null) {
            speechHandler.stopListening();
        }
        setState(AppState.IDLE);
    }

    /**
     * Stop any current operation
     */
    private void stopCurrentOperation() {
        Log.d(TAG, "Stopping current operation");
        
        if (speechHandler != null) {
            speechHandler.cancel();
        }
        if (ttsHandler != null) {
            ttsHandler.stop();
        }
        
        setState(AppState.IDLE);
    }

    /**
     * Process recognized text - send to Gemini and handle response (conversational or app launch)
     */
    private void processQuery(String query) {
        Log.d(TAG, "Processing query with Gemini for NLP: " + query);

        // Validate input for Gemini
        Utils.ValidationResult validation = Utils.validateInput(query);
        if (!validation.isValid) {
            showError(getString(R.string.error_empty_input));
            return;
        }

        if (geminiService == null) {
            String detectedLanguage = Utils.detectLanguage(validation.cleanedText, selectedLanguageCode);
            handleGeminiResponse(validation.cleanedText,
                    buildOfflineFallbackResponse(validation.cleanedText, detectedLanguage),
                    detectedLanguage,
                    "offline_fallback");
            return;
        }

        final String queryLanguageCode = Utils.detectLanguage(validation.cleanedText, selectedLanguageCode);
        lastQueryLanguageCode = queryLanguageCode;
        consecutiveSpeechFailures = 0;

        if (!Utils.isNetworkAvailable(this)) {
            handleGeminiResponse(validation.cleanedText,
                    buildOfflineFallbackResponse(validation.cleanedText, queryLanguageCode),
                    queryLanguageCode,
                    "offline_fallback");
            return;
        }

        if (!geminiService.isApiKeyConfigured()) {
            handleGeminiResponse(validation.cleanedText,
                    buildOfflineFallbackResponse(validation.cleanedText, queryLanguageCode),
                    queryLanguageCode,
                    "offline_fallback");
            return;
        }

        setState(AppState.PROCESSING);

        // Show recognized text
        mainHandler.post(() -> {
            recognizedText.setText(validation.cleanedText);
            recognizedText.setVisibility(View.VISIBLE);
        });

        // Send to Gemini API on background thread
        executorService.execute(() -> {
            GeminiService.GeminiResponse response =
                    geminiService.query(validation.cleanedText, queryLanguageCode);
            
            mainHandler.post(() -> {
                if (response.success) {
                    if (response.isLaunchAppIntent() && shouldAllowAppLaunch(validation.cleanedText)) {
                        // Gemini detected an app launch intent
                        Log.d(TAG, "Gemini detected LAUNCH_APP intent for: " + response.appPackageName);
                        
                        String appDisplayName = response.appPackageName; // Default to package name
                        // Simple mapping for display purposes - you can expand this
                        if ("nic.goi.uidai.aadhaar.client".equals(response.appPackageName)) {
                            appDisplayName = "Aadhar application";
                        } else if ("com.indianoil.b2c.android".equals(response.appPackageName)) {
                            appDisplayName = "gas booking application";
                        } else if ("com.application.zomato".equals(response.appPackageName)) {
                            appDisplayName = "Zomato";
                        }

                        String speechConfirmation = Utils.cleanResponseForSpeech("Opening " + appDisplayName + ".");
                        String displayConfirmation = Utils.cleanResponseForDisplay("Opening " + appDisplayName + "...");

                        responseText.setText(displayConfirmation);
                        responseCard.setVisibility(View.VISIBLE);
                        replayButton.setVisibility(View.GONE); // No replay for app launch
                        saveHistory(validation.cleanedText, displayConfirmation, queryLanguageCode,
                                "app_launch", "success");
                        
                        setState(AppState.SPEAKING); // Indicate we are speaking the response
                        ttsHandler.speak(speechConfirmation);

                        launchApp(response.appPackageName);

                    } else if (response.isLaunchAppIntent()) {
                        // User did not explicitly ask to open an app. Keep guidance mode only.
                        String safeResponse = "I will explain the steps instead of opening an app. " + response.text;
                        handleGeminiResponse(validation.cleanedText, safeResponse, queryLanguageCode, "live_ai");
                    } else {
                        // Regular conversational response from Gemini
                        handleGeminiResponse(validation.cleanedText, response.text, queryLanguageCode, "live_ai");
                    }
                } else {
                    String fallback = buildOfflineFallbackResponse(validation.cleanedText, queryLanguageCode);
                    handleGeminiResponse(validation.cleanedText, fallback, queryLanguageCode, "offline_fallback");
                }
            });
        });
    }

    private String buildOfflineFallbackResponse(String query, String languageCode) {
        String q = query != null ? query.toLowerCase() : "";
        boolean gasBooking = q.contains("gas") || q.contains("cylinder") || q.contains("గ్యాస్") || q.contains("सिलेंडर");
        boolean aadhaarDownload = q.contains("aadhaar") || q.contains("aadhar") || q.contains("ఆధార్") || q.contains("आधार");
        boolean rationCard = q.contains("ration") || q.contains("ration card")
                || q.contains("రేషన్") || q.contains("రేషన్ కార్డ్")
                || q.contains("राशन") || q.contains("राशन कार्ड");
        boolean panCard = q.contains("pan") || q.contains("pan card") || q.contains("పాన్") || q.contains("पैन");
        boolean voterId = q.contains("voter") || q.contains("epic") || q.contains("ఓటర్") || q.contains("मतदाता");
        boolean passport = q.contains("passport") || q.contains("పాస్పోర్ట్") || q.contains("पासपोर्ट");
        boolean drivingLicense = q.contains("driving license") || q.contains("driving licence")
                || q.contains("license") || q.contains("licence")
                || q.contains("డ్రైవింగ్ లైసెన్స్") || q.contains("लाइसेंस");
        boolean pmKisan = q.contains("pm kisan") || q.contains("kisan") || q.contains("పీఎం కిసాన్") || q.contains("किसान");
        boolean ayushman = q.contains("ayushman") || q.contains("health card")
                || q.contains("ఆయుష్మాన్") || q.contains("आयुष्मान");
        String topic = extractFallbackTopic(query);

        if ("te-IN".equals(languageCode)) {
            if (gasBooking) {
                return "గ్యాస్ బుకింగ్ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: మీ గ్యాస్ కంపెనీ యాప్ లేదా వెబ్‌సైట్ ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: మొబైల్ నంబర్‌తో లాగిన్ అవ్వండి.\n"
                        + "స్టెప్ 3: కస్టమర్ ఐడి లేదా ఎల్పీజీ ఐడి కన్ఫర్మ్ చేయండి.\n"
                        + "స్టెప్ 4: బుక్ సిలిండర్ పై క్లిక్ చేయండి.\n"
                        + "స్టెప్ 5: చెల్లింపు చేసి బుకింగ్ నంబర్ సేవ్ చేసుకోండి.";
            }
            if (aadhaarDownload) {
                return "ఆధార్ డౌన్‌లోడ్ స్టెప్స్:\n"
                        + "స్టెప్ 1: uidai.gov.in కి వెళ్ళండి.\n"
                        + "స్టెప్ 2: My Aadhaar లో Download Aadhaar ఎంచుకోండి.\n"
                        + "స్టెప్ 3: ఆధార్ నంబర్ లేదా VID ఇవ్వండి.\n"
                        + "స్టెప్ 4: OTP వెరిఫై చేయండి.\n"
                        + "స్టెప్ 5: PDF డౌన్‌లోడ్ చేసుకోండి.\n"
                        + "పాస్‌వర్డ్: పేరులో మొదటి 4 అక్షరాలు పెద్ద అక్షరాల్లో మరియు పుట్టిన సంవత్సరం.";
            }
            if (rationCard) {
                return "రేషన్ కార్డ్ సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: మీ రాష్ట్ర పౌర సరఫరాల అధికారిక వెబ్‌సైట్ లేదా MeeSeva సేవను ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: రేషన్ కార్డ్ లాగిన్ లేదా అప్లికేషన్ సేవను ఎంచుకోండి.\n"
                        + "స్టెప్ 3: ఆధార్ నంబర్, మొబైల్ నంబర్ లేదా అప్లికేషన్ నంబర్ నమోదు చేయండి.\n"
                        + "స్టెప్ 4: OTP లేదా అవసరమైన ధృవీకరణ పూర్తి చేయండి.\n"
                        + "స్టెప్ 5: డౌన్‌లోడ్, స్టేటస్ లేదా ప్రింట్ ఆప్షన్ ఎంచుకుని సేవ్ చేసుకోండి.";
            }
            if (panCard) {
                return "పాన్ కార్డ్ సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: incometax.gov.in లేదా NSDL/UTIITSL అధికారిక సైట్ ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: కొత్త PAN లేదా PAN సరిదిద్దే సేవ ఎంచుకోండి.\n"
                        + "స్టెప్ 3: పేరు, జనన తేదీ, ఆధార్ వివరాలు నమోదు చేయండి.\n"
                        + "స్టెప్ 4: OTP లేదా డాక్యుమెంట్ వెరిఫికేషన్ పూర్తి చేయండి.\n"
                        + "స్టెప్ 5: అప్లికేషన్ నంబర్ సేవ్ చేసి స్టేటస్ చూడండి.";
            }
            if (voterId) {
                return "ఓటర్ ఐడి సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: voters.eci.gov.in లేదా Voter Helpline యాప్ ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: కొత్త నమోదు, సరిదిద్దడం లేదా డౌన్‌లోడ్ సేవ ఎంచుకోండి.\n"
                        + "స్టెప్ 3: మొబైల్ నంబర్, EPIC నంబర్ లేదా వ్యక్తిగత వివరాలు నమోదు చేయండి.\n"
                        + "స్టెప్ 4: అవసరమైన డాక్యుమెంట్లు అప్‌లోడ్ చేసి వెరిఫికేషన్ పూర్తి చేయండి.\n"
                        + "స్టెప్ 5: రిఫరెన్స్ నంబర్‌తో స్టేటస్ చెక్ చేయండి.";
            }
            if (passport) {
                return "పాస్పోర్ట్ సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: passportindia.gov.in ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: రిజిస్టర్ లేదా లాగిన్ అవ్వండి.\n"
                        + "స్టెప్ 3: కొత్త పాస్పోర్ట్ లేదా రీన్యువల్ సేవ ఎంచుకోండి.\n"
                        + "స్టెప్ 4: ఫారం నింపి ఫీజు చెల్లించి అపాయింట్మెంట్ బుక్ చేయండి.\n"
                        + "స్టెప్ 5: అపాయింట్మెంట్ రోజు డాక్యుమెంట్లతో పాస్‌పోర్ట్ సేవా కేంద్రానికి వెళ్లండి.";
            }
            if (drivingLicense) {
                return "డ్రైవింగ్ లైసెన్స్ సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: parivahan.gov.in ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: మీ రాష్ట్రాన్ని ఎంచుకుని లెర్నర్స్ లేదా డ్రైవింగ్ లైసెన్స్ సేవ తీసుకోండి.\n"
                        + "స్టెప్ 3: ఫారం నింపి డాక్యుమెంట్లు అప్‌లోడ్ చేయండి.\n"
                        + "స్టెప్ 4: ఫీజు చెల్లించి టెస్ట్ లేదా స్లాట్ బుక్ చేయండి.\n"
                        + "స్టెప్ 5: టెస్ట్ పూర్తయ్యాక స్టేటస్ డౌన్‌లోడ్ ఆప్షన్ చూడండి.";
            }
            if (pmKisan) {
                return "పీఎం కిసాన్ సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: pmkisan.gov.in ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: Farmer Corner లో New Registration లేదా Beneficiary Status ఎంచుకోండి.\n"
                        + "స్టెప్ 3: ఆధార్ నంబర్, మొబైల్ నంబర్ మరియు భూమి వివరాలు నమోదు చేయండి.\n"
                        + "స్టెప్ 4: OTP లేదా రికార్డు వెరిఫికేషన్ పూర్తి చేయండి.\n"
                        + "స్టెప్ 5: స్టేటస్, ఇన్‌స్టాల్‌మెంట్ లేదా దిద్దుబాటు సమాచారం చూడండి.";
            }
            if (ayushman) {
                return "ఆయుష్మాన్ సేవ కోసం స్టెప్స్:\n"
                        + "స్టెప్ 1: beneficiary.nha.gov.in ఓపెన్ చేయండి.\n"
                        + "స్టెప్ 2: మొబైల్ నంబర్‌తో లాగిన్ అవ్వండి.\n"
                        + "స్టెప్ 3: కుటుంబ వివరాలు లేదా కార్డ్ స్టేటస్ సెర్చ్ చేయండి.\n"
                        + "స్టెప్ 4: eKYC లేదా అవసరమైన వెరిఫికేషన్ పూర్తి చేయండి.\n"
                        + "స్టెప్ 5: కార్డ్ డౌన్‌లోడ్ లేదా ఆస్పత్రి వివరాలు చూడండి.";
            }
            return "మీ ప్రశ్నకు స్టెప్ బై స్టెప్ సహాయం:\n"
                    + "స్టెప్ 1: " + topic + " కోసం అధికారిక ప్రభుత్వ వెబ్‌సైట్ లేదా యాప్ ఓపెన్ చేయండి.\n"
                    + "స్టెప్ 2: మీ మొబైల్ నంబర్, ఐడి లేదా సేవకు సంబంధించిన వివరాలతో లాగిన్ అవ్వండి.\n"
                    + "స్టెప్ 3: అవసరమైన సేవను ఎంచుకుని వివరాలు నమోదు చేసి ధృవీకరణ పూర్తి చేయండి.\n"
                    + "స్టెప్ 4: చివరగా స్టేటస్, రసీదు లేదా డౌన్‌లోడ్ ఆప్షన్ చూసి సేవ్ చేసుకోండి.";
        }

        if ("hi-IN".equals(languageCode)) {
            if (gasBooking) {
                return "गैस बुकिंग के स्टेप्स:\n"
                        + "स्टेप 1: अपनी गैस कंपनी की ऐप या वेबसाइट खोलें.\n"
                        + "स्टेप 2: रजिस्टर्ड मोबाइल नंबर से लॉगिन करें.\n"
                        + "स्टेप 3: कस्टमर आईडी या एलपीजी आईडी जांचें.\n"
                        + "स्टेप 4: बुक सिलेंडर पर टैप करें.\n"
                        + "स्टेप 5: पेमेंट पूरा करें और बुकिंग नंबर सेव करें.";
            }
            if (aadhaarDownload) {
                return "आधार डाउनलोड के स्टेप्स:\n"
                        + "स्टेप 1: uidai.gov.in पर जाएँ.\n"
                        + "स्टेप 2: My Aadhaar में Download Aadhaar चुनें.\n"
                        + "स्टेप 3: आधार नंबर या VID दर्ज करें.\n"
                        + "स्टेप 4: OTP सत्यापित करें.\n"
                        + "स्टेप 5: PDF डाउनलोड करें.\n"
                        + "पासवर्ड: आपके नाम के पहले 4 अक्षर बड़े अक्षरों में और जन्म वर्ष.";
            }
            if (rationCard) {
                return "राशन कार्ड सेवा के स्टेप्स:\n"
                        + "स्टेप 1: अपने राज्य की सिविल सप्लाई की आधिकारिक वेबसाइट या सेवा पोर्टल खोलें.\n"
                        + "स्टेप 2: राशन कार्ड लॉगिन, डाउनलोड या स्टेटस सेवा चुनें.\n"
                        + "स्टेप 3: आधार नंबर, मोबाइल नंबर या आवेदन नंबर दर्ज करें.\n"
                        + "स्टेप 4: OTP या जरूरी सत्यापन पूरा करें.\n"
                        + "स्टेप 5: डाउनलोड, स्टेटस या प्रिंट विकल्प चुनकर सेव करें.";
            }
            if (panCard) {
                return "पैन कार्ड सेवा के स्टेप्स:\n"
                        + "स्टेप 1: incometax.gov.in या NSDL/UTIITSL की आधिकारिक साइट खोलें.\n"
                        + "स्टेप 2: नया पैन या सुधार सेवा चुनें.\n"
                        + "स्टेप 3: नाम, जन्मतिथि और आधार विवरण भरें.\n"
                        + "स्टेप 4: OTP या दस्तावेज़ सत्यापन पूरा करें.\n"
                        + "स्टेप 5: आवेदन नंबर सेव करें और स्टेटस देखें.";
            }
            if (voterId) {
                return "वोटर आईडी सेवा के स्टेप्स:\n"
                        + "स्टेप 1: voters.eci.gov.in या Voter Helpline ऐप खोलें.\n"
                        + "स्टेप 2: नया पंजीकरण, सुधार या डाउनलोड सेवा चुनें.\n"
                        + "स्टेप 3: मोबाइल नंबर, EPIC नंबर या व्यक्तिगत विवरण दर्ज करें.\n"
                        + "स्टेप 4: दस्तावेज़ अपलोड करके सत्यापन पूरा करें.\n"
                        + "स्टेप 5: रेफरेंस नंबर से स्टेटस जांचें.";
            }
            if (passport) {
                return "पासपोर्ट सेवा के स्टेप्स:\n"
                        + "स्टेप 1: passportindia.gov.in खोलें.\n"
                        + "स्टेप 2: रजिस्टर करें या लॉगिन करें.\n"
                        + "स्टेप 3: नया पासपोर्ट या रिन्यूअल सेवा चुनें.\n"
                        + "स्टेप 4: फॉर्म भरें, फीस जमा करें और अपॉइंटमेंट बुक करें.\n"
                        + "स्टेप 5: अपॉइंटमेंट के दिन दस्तावेज़ लेकर पासपोर्ट सेवा केंद्र जाएँ.";
            }
            if (drivingLicense) {
                return "ड्राइविंग लाइसेंस सेवा के स्टेप्स:\n"
                        + "स्टेप 1: parivahan.gov.in खोलें.\n"
                        + "स्टेप 2: अपना राज्य चुनें और लर्नर या ड्राइविंग लाइसेंस सेवा लें.\n"
                        + "स्टेप 3: फॉर्म भरें और दस्तावेज़ अपलोड करें.\n"
                        + "स्टेप 4: फीस जमा करें और टेस्ट या स्लॉट बुक करें.\n"
                        + "स्टेप 5: टेस्ट के बाद स्टेटस या डाउनलोड विकल्प देखें.";
            }
            if (pmKisan) {
                return "पीएम किसान सेवा के स्टेप्स:\n"
                        + "स्टेप 1: pmkisan.gov.in खोलें.\n"
                        + "स्टेप 2: Farmer Corner में New Registration या Beneficiary Status चुनें.\n"
                        + "स्टेप 3: आधार नंबर, मोबाइल नंबर और भूमि विवरण दर्ज करें.\n"
                        + "स्टेप 4: OTP या रिकॉर्ड सत्यापन पूरा करें.\n"
                        + "स्टेप 5: स्टेटस, किस्त या सुधार जानकारी देखें.";
            }
            if (ayushman) {
                return "आयुष्मान सेवा के स्टेप्स:\n"
                        + "स्टेप 1: beneficiary.nha.gov.in खोलें.\n"
                        + "स्टेप 2: मोबाइल नंबर से लॉगिन करें.\n"
                        + "स्टेप 3: परिवार विवरण या कार्ड स्टेटस खोजें.\n"
                        + "स्टेप 4: eKYC या जरूरी सत्यापन पूरा करें.\n"
                        + "स्टेप 5: कार्ड डाउनलोड या अस्पताल विवरण देखें.";
            }
            return "आपके सवाल के लिए स्टेप बाय स्टेप मार्गदर्शन:\n"
                    + "स्टेप 1: " + topic + " के लिए आधिकारिक सरकारी वेबसाइट या ऐप खोलें.\n"
                    + "स्टेप 2: मोबाइल नंबर, आईडी या संबंधित विवरण से लॉगिन करें.\n"
                    + "स्टेप 3: सही सेवा चुनें, जरूरी जानकारी भरें और सत्यापन पूरा करें.\n"
                    + "स्टेप 4: अंत में स्टेटस, रसीद या डाउनलोड विकल्प देखकर सेव करें.";
        }

        if (gasBooking) {
            return "Gas booking steps:\n"
                    + "Step 1: Open your gas provider app or website.\n"
                    + "Step 2: Login with your registered mobile number.\n"
                    + "Step 3: Verify customer ID or LPG ID.\n"
                    + "Step 4: Tap Book Cylinder.\n"
                    + "Step 5: Complete payment and save booking reference number.";
        }
        if (aadhaarDownload) {
            return "Aadhaar download steps:\n"
                    + "Step 1: Go to uidai.gov.in.\n"
                    + "Step 2: Open My Aadhaar and choose Download Aadhaar.\n"
                    + "Step 3: Enter Aadhaar number or VID.\n"
                    + "Step 4: Verify OTP.\n"
                    + "Step 5: Download PDF.\n"
                    + "Password: first 4 letters of your name in uppercase plus birth year.";
        }
        if (rationCard) {
            return "Ration card service steps:\n"
                    + "Step 1: Open your state civil supplies official website or service portal.\n"
                    + "Step 2: Choose the ration card login, download, or status service.\n"
                    + "Step 3: Enter Aadhaar number, mobile number, or application number.\n"
                    + "Step 4: Complete OTP or the required verification.\n"
                    + "Step 5: Use the download, status, or print option and save it.";
        }
        if (panCard) {
            return "PAN card service steps:\n"
                    + "Step 1: Open incometax.gov.in or the official NSDL or UTIITSL site.\n"
                    + "Step 2: Choose new PAN or PAN correction service.\n"
                    + "Step 3: Fill name, date of birth, and Aadhaar details.\n"
                    + "Step 4: Complete OTP or document verification.\n"
                    + "Step 5: Save the application number and track the status.";
        }
        if (voterId) {
            return "Voter ID service steps:\n"
                    + "Step 1: Open voters.eci.gov.in or the Voter Helpline app.\n"
                    + "Step 2: Choose new registration, correction, or download service.\n"
                    + "Step 3: Enter your mobile number, EPIC number, or personal details.\n"
                    + "Step 4: Upload required documents and complete verification.\n"
                    + "Step 5: Check status using the reference number.";
        }
        if (passport) {
            return "Passport service steps:\n"
                    + "Step 1: Open passportindia.gov.in.\n"
                    + "Step 2: Register or sign in.\n"
                    + "Step 3: Choose new passport or renewal service.\n"
                    + "Step 4: Fill the form, pay the fee, and book an appointment.\n"
                    + "Step 5: Visit the Passport Seva Kendra with documents on the appointment day.";
        }
        if (drivingLicense) {
            return "Driving license service steps:\n"
                    + "Step 1: Open parivahan.gov.in.\n"
                    + "Step 2: Select your state and choose learner or driving license service.\n"
                    + "Step 3: Fill the form and upload the documents.\n"
                    + "Step 4: Pay the fee and book the test or slot.\n"
                    + "Step 5: After the test, check the status or download option.";
        }
        if (pmKisan) {
            return "PM Kisan service steps:\n"
                    + "Step 1: Open pmkisan.gov.in.\n"
                    + "Step 2: In Farmer Corner choose New Registration or Beneficiary Status.\n"
                    + "Step 3: Enter Aadhaar number, mobile number, and land details.\n"
                    + "Step 4: Complete OTP or record verification.\n"
                    + "Step 5: Check installment, status, or correction details.";
        }
        if (ayushman) {
            return "Ayushman service steps:\n"
                    + "Step 1: Open beneficiary.nha.gov.in.\n"
                    + "Step 2: Sign in with your mobile number.\n"
                    + "Step 3: Search family details or card status.\n"
                    + "Step 4: Complete eKYC or required verification.\n"
                    + "Step 5: View card download or hospital details.";
        }
        return "Step-by-step help for your request:\n"
                + "Step 1: Open the official government website or app for " + topic + ".\n"
                + "Step 2: Sign in with your mobile number, ID, or the service details.\n"
                + "Step 3: Select the correct service, fill in the details, and complete verification.\n"
                + "Step 4: Check the final status, receipt, or download option and save it.";
    }

    private String extractFallbackTopic(String query) {
        if (query == null) {
            return "your service";
        }
        String cleaned = query.trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return "your service";
        }
        if (cleaned.length() <= 48) {
            return cleaned;
        }
        return cleaned.substring(0, 48).trim();
    }

    private boolean shouldAllowAppLaunch(String userQuery) {
        if (userQuery == null) return false;
        String q = userQuery.toLowerCase();
        boolean hasLaunchVerb = q.contains("open ")
                || q.contains("launch ")
                || q.contains("start ")
                || q.contains("open app")
                || q.contains("open the app")
                || q.contains("అప్ ఓపెన్")
                || q.contains("తెరువు")
                || q.contains("చేయి ఓపెన్")
                || q.contains("खोलो")
                || q.contains("चालू");
        boolean hasHowToIntent = q.contains("how to")
                || q.contains("steps")
                || q.contains("process")
                || q.contains("procedure")
                || q.contains("guide")
                || q.contains("teach")
                || q.contains("ఎలా")
                || q.contains("స్టెప్స్")
                || q.contains("प्रक्रिया")
                || q.contains("कैसे");
        return hasLaunchVerb && !hasHowToIntent;
    }

    /**
     * Launches an external application given its package name.
     * If the app is not found, it tries to open it in the Play Store.
     * @param packageName The package name of the app to launch.
     */
    private void launchApp(String packageName) {
        Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(this, "Opening " + packageName, Toast.LENGTH_SHORT).show();
            setState(AppState.IDLE); // Go back to idle after launching app
        } else {
            // App not found, direct user to Play Store
            Toast.makeText(this, "App not found. Trying to open in Play Store.", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName)));
            } catch (android.content.ActivityNotFoundException anfe) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName)));
            }
            setState(AppState.ERROR); // Set to error if app not found
        }
    }

    /**
     * Handle successful Gemini response (conversational)
     */
    private void handleGeminiResponse(String query,
                                      String response,
                                      String responseLanguageCode,
                                      String responseSource) {
        Log.d(TAG, "Received Gemini conversational response");

        // Clean response for display
        String displayText = Utils.cleanResponseForDisplay(response);
        
        // Clean response for speech
        String speechText = Utils.cleanResponseForSpeech(response);
        
        lastResponse = speechText;

        // Update UI
        responseText.setText(displayText);
        responseCard.setVisibility(View.VISIBLE);
        replayButton.setVisibility(View.VISIBLE);
        saveHistory(query, displayText, responseLanguageCode, responseSource, "success");

        ttsHandler.setLanguage(responseLanguageCode);

        // Speak the response
        setState(AppState.SPEAKING);
        ttsHandler.speak(speechText);
    }

    private void saveHistory(String query,
                             String response,
                             String languageCode,
                             String source,
                             String status) {
        if (firebaseHistoryService == null) {
            return;
        }
        firebaseHistoryService.saveInteraction(query, response, languageCode, source, status);
    }

    /**
     * Show error message to user
     */
    private void showError(String message) {
        Log.e(TAG, "Error: " + message);
        setState(AppState.ERROR);
        
        statusText.setText(message);
        statusIcon.setImageResource(R.drawable.ic_error);
        
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ==================== State Management ====================

    /**
     * Update app state and UI accordingly
     */
    private void setState(AppState newState) {
        Log.d(TAG, "State change: " + currentState + " -> " + newState);
        currentState = newState;
        updateUI();
    }

    /**
     * Update UI based on current state
     */
    private void updateUI() {
        // Stop any existing animations
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }

        switch (currentState) {
            case IDLE:
                statusText.setText(R.string.status_ready);
                statusIcon.setImageResource(R.drawable.ic_mic_ready);
                micButton.setEnabled(true);
                stopButton.setVisibility(View.GONE);
                loadingOverlay.setVisibility(View.GONE);
                recognizedText.setVisibility(View.GONE);
                break;

            case LISTENING:
                statusText.setText(R.string.status_listening);
                statusIcon.setImageResource(R.drawable.ic_mic_listening);
                micButton.setEnabled(true);
                stopButton.setVisibility(View.VISIBLE);
                loadingOverlay.setVisibility(View.GONE);
                startPulseAnimation();
                break;

            case PROCESSING:
                statusText.setText(R.string.status_processing);
                statusIcon.setImageResource(R.drawable.ic_processing);
                micButton.setEnabled(false);
                stopButton.setVisibility(View.VISIBLE);
                loadingOverlay.setVisibility(View.VISIBLE);
                break;

            case SPEAKING:
                statusText.setText(R.string.status_speaking);
                statusIcon.setImageResource(R.drawable.ic_speaking);
                micButton.setEnabled(false);
                stopButton.setVisibility(View.VISIBLE);
                loadingOverlay.setVisibility(View.GONE);
                break;

            case ERROR:
                // Status text set in showError()
                micButton.setEnabled(true);
                stopButton.setVisibility(View.GONE);
                loadingOverlay.setVisibility(View.GONE);
                break;
        }
    }

    /**
     * Start pulse animation for listening state
     */
    private void startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofFloat(statusIcon, "alpha", 1f, 0.5f, 1f);
        pulseAnimator.setDuration(1000);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.start();
    }

    // ==================== SpeechHandler Callbacks ====================

    @Override
    public void onReadyForSpeech() {
        Log.d(TAG, "Ready for speech");
        mainHandler.post(() -> setState(AppState.LISTENING));
    }

    @Override
    public void onBeginningOfSpeech() {
        Log.d(TAG, "User began speaking");
    }

    @Override
    public void onEndOfSpeech() {
        Log.d(TAG, "User stopped speaking");
    }

    @Override
    public void onResults(String recognizedText) {
        Log.d(TAG, "Speech recognized: " + recognizedText);
        lastPartialSpeech = "";
        consecutiveSpeechFailures = 0;
        mainHandler.post(() -> processQuery(recognizedText));
    }

    @Override
    public void onPartialResults(String partialText) {
        // Show partial results for feedback
        lastPartialSpeech = partialText != null ? partialText.trim() : "";
        mainHandler.post(() -> {
            recognizedText.setText(partialText);
            recognizedText.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onError(int errorCode, String errorMessage) {
        Log.e(TAG, "Speech error: " + errorMessage);
        mainHandler.post(() -> {
            if (shouldSuppressSpeechRetryMessage(errorCode, errorMessage)) {
                return;
            }

            // If we captured useful partial speech, continue with it instead of failing.
            if (shouldUsePartialSpeechFallback(errorCode)) {
                String fallbackText = lastPartialSpeech;
                lastPartialSpeech = "";
                processQuery(fallbackText);
                return;
            }

            // Retry once automatically before showing "no speech" error.
            if (shouldRetrySpeechCapture(errorCode)) {
                consecutiveSpeechFailures++;
                setState(AppState.LISTENING);
                if (speechHandler != null) {
                    speechHandler.startListening();
                }
                return;
            }

            // After one failed capture, switch to manual input instead of repeating "no speech detected".
            if (shouldOpenManualInputDirectly(errorCode)) {
                consecutiveSpeechFailures++;
                setState(AppState.IDLE);
                statusText.setText(R.string.status_ready);
                showManualInputFallbackDialog();
                return;
            }

            consecutiveSpeechFailures++;
            showError(mapSpeechErrorMessage(errorCode, errorMessage));

            // Offer typed fallback after repeated speech failures.
            if (consecutiveSpeechFailures >= 2) {
                showManualInputFallbackDialog();
            }
        });
    }

    private boolean shouldSuppressSpeechRetryMessage(int errorCode, String errorMessage) {
        if (errorCode != SpeechRecognizer.ERROR_CLIENT || errorMessage == null) {
            return false;
        }
        return errorMessage.toLowerCase().contains("retrying");
    }

    private boolean shouldUsePartialSpeechFallback(int errorCode) {
        if (lastPartialSpeech == null || lastPartialSpeech.length() < 3) {
            return false;
        }
        return errorCode == SpeechRecognizer.ERROR_NO_MATCH
                || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || errorCode == SpeechRecognizer.ERROR_NETWORK
                || errorCode == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                || errorCode == SpeechRecognizer.ERROR_SERVER;
    }

    private boolean shouldRetrySpeechCapture(int errorCode) {
        if (consecutiveSpeechFailures > 0) {
            return false;
        }
        return errorCode == SpeechRecognizer.ERROR_NO_MATCH
                || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                || errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                || errorCode == SpeechRecognizer.ERROR_CLIENT;
    }

    private boolean shouldOpenManualInputDirectly(int errorCode) {
        if (consecutiveSpeechFailures <= 0) {
            return false;
        }
        return errorCode == SpeechRecognizer.ERROR_NO_MATCH
                || errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
    }

    private void showManualInputFallbackDialog() {
        if (isFinishing() || isDestroyed() || isManualInputDialogShowing) {
            return;
        }
        isManualInputDialogShowing = true;

        String dialogLanguageCode = lastQueryLanguageCode != null ? lastQueryLanguageCode : selectedLanguageCode;
        EditText input = new EditText(this);
        input.setHint(getManualInputHint(dialogLanguageCode));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(2);

        new AlertDialog.Builder(this)
                .setTitle(getManualInputTitle(dialogLanguageCode))
                .setMessage(getManualInputMessage(dialogLanguageCode))
                .setView(input)
                .setPositiveButton(getManualInputPositive(dialogLanguageCode), (dialog, which) -> {
                    isManualInputDialogShowing = false;
                    String typed = input.getText() != null ? input.getText().toString().trim() : "";
                    if (!typed.isEmpty()) {
                        processQuery(typed);
                    }
                })
                .setNegativeButton(getManualInputNegative(dialogLanguageCode), (dialog, which) -> isManualInputDialogShowing = false)
                .setOnDismissListener(dialog -> isManualInputDialogShowing = false)
                .show();
    }

    private String getManualInputTitle(String languageCode) {
        if ("te-IN".equals(languageCode)) return "స్పీచ్ సమస్య గుర్తించబడింది";
        if ("hi-IN".equals(languageCode)) return "स्पीच समस्या मिली";
        return "Speech issue detected";
    }

    private String getManualInputMessage(String languageCode) {
        if ("te-IN".equals(languageCode)) {
            return "వాయిస్ గుర్తింపు ఇప్పుడు స్థిరంగా లేదు. మీ ప్రశ్నను టైప్ చేసి కొనసాగించండి.";
        }
        if ("hi-IN".equals(languageCode)) {
            return "अभी वॉइस पहचान स्थिर नहीं है। अपना सवाल टाइप करके जारी रखें।";
        }
        return "Voice recognition is unstable right now. Type your question and continue.";
    }

    private String getManualInputHint(String languageCode) {
        if ("te-IN".equals(languageCode)) return "మీ ప్రశ్నను ఇక్కడ టైప్ చేయండి";
        if ("hi-IN".equals(languageCode)) return "अपना सवाल यहाँ टाइप करें";
        return "Type your question here";
    }

    private String getManualInputPositive(String languageCode) {
        if ("te-IN".equals(languageCode)) return "అడగండి";
        if ("hi-IN".equals(languageCode)) return "पूछें";
        return "Ask";
    }

    private String getManualInputNegative(String languageCode) {
        if ("te-IN".equals(languageCode)) return "రద్దు";
        if ("hi-IN".equals(languageCode)) return "रद्द करें";
        return "Cancel";
    }

    private String mapSpeechErrorMessage(int errorCode, String fallbackMessage) {
        if (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            return getString(R.string.status_no_speech);
        }
        if (errorCode == SpeechRecognizer.ERROR_NETWORK ||
                errorCode == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                errorCode == SpeechRecognizer.ERROR_SERVER) {
            return getString(R.string.error_speech_network);
        }
        if (errorCode == SpeechRecognizer.ERROR_CLIENT) {
            return getString(R.string.error_speech_client);
        }
        if (errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            return getString(R.string.error_speech_busy);
        }
        return fallbackMessage != null && !fallbackMessage.trim().isEmpty()
                ? fallbackMessage
                : getString(R.string.status_error);
    }

    // ==================== TTS Callbacks ====================

    @Override
    public void onTTSInitialized(boolean success) {
        if (success) {
            Log.d(TAG, "TTS initialized successfully");
            ttsHandler.setLanguage(selectedLanguageCode);
        } else {
            Log.e(TAG, "TTS initialization failed");
            mainHandler.post(() -> 
                Toast.makeText(this, R.string.error_tts_not_available, Toast.LENGTH_LONG).show()
            );
        }
    }

    @Override
    public void onSpeechStart() {
        Log.d(TAG, "TTS started speaking");
    }

    @Override
    public void onSpeechDone() {
        Log.d(TAG, "TTS finished speaking");
        mainHandler.post(() -> setState(AppState.IDLE));
    }

    @Override
    public void onSpeechError(String error) {
        Log.e(TAG, "TTS error: " + error);
        mainHandler.post(() -> setState(AppState.IDLE));
    }

    // ==================== Lifecycle Management ====================

    @Override
    protected void onPause() {
        super.onPause();
        // Stop listening when app goes to background
        if (speechHandler != null) {
            speechHandler.cancel();
        }
        if (ttsHandler != null) {
            ttsHandler.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Clean up all handlers
        if (speechHandler != null) {
            speechHandler.destroy();
        }
        if (ttsHandler != null) {
            ttsHandler.destroy();
        }
        
        // Shutdown executor
        if (executorService != null) {
            executorService.shutdown();
        }
        
        // Cancel animations
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }

        Log.d(TAG, "MainActivity destroyed");
    }
}
