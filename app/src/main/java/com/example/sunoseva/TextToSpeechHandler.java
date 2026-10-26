package com.example.sunoseva;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;

/**
 * TextToSpeechHandler - Manages Android Text-to-Speech Engine
 * 
 * Purpose:
 * - Initialize and manage TextToSpeech engine
 * - Configure voice for regional languages
 * - Handle speech output callbacks
 * - Provide clean interface for MainActivity
 * 
 * Why separate class?
 * - Clean separation from UI logic
 * - Proper lifecycle management
 * - Handle TTS initialization asynchronously
 * - Easier language matching logic
 * 
 * Key Feature: Uses Android's built-in TTS
 * - Works with Google TTS engine
 * - Supports many Indian languages
 * - No internet required for most languages
 */
public class TextToSpeechHandler implements TextToSpeech.OnInitListener {

    private static final String TAG = "TextToSpeechHandler";

    // Android's TextToSpeech engine
    private TextToSpeech textToSpeech;
    
    // Callback interface
    private TTSCallback callback;
    
    // Current language locale
    private Locale currentLocale = new Locale("te", "IN");
    
    // Track initialization status
    private boolean isInitialized = false;
    
    // Last spoken text (for replay)
    private String lastSpokenText = "";
    
    // Application context
    private Context context;

    /**
     * Callback interface for TTS events
     * 
     * Why interface?
     * - Decouples TTSHandler from MainActivity
     * - Activity implements this to receive events
     */
    public interface TTSCallback {
        void onTTSInitialized(boolean success);
        void onSpeechStart();
        void onSpeechDone();
        void onSpeechError(String error);
    }

    /**
     * Constructor
     * 
     * @param context Application context
     * @param callback Callback for TTS events
     */
    public TextToSpeechHandler(Context context, TTSCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        initializeTTS();
    }

    /**
     * Initialize TextToSpeech engine
     * 
     * Note: TTS initialization is ASYNCHRONOUS
     * - Cannot speak until onInit callback received
     * - Must handle initialization failure gracefully
     */
    private void initializeTTS() {
        Log.d(TAG, "Initializing TextToSpeech...");
        textToSpeech = new TextToSpeech(context, this);
    }

    /**
     * Called when TTS engine is initialized
     * 
     * This is the OnInitListener callback
     * Handle success/failure here
     */
    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS initialized successfully");

            Locale appliedLocale = applyBestAvailableLocale(currentLocale);
            if (appliedLocale != null) {
                currentLocale = appliedLocale;
            } else {
                Log.w(TAG, "No preferred TTS locale available during initialization");
            }
            
            // Configure speech parameters
            configureSpeechParams();
            
            // Set up progress listener
            setupProgressListener();
            
            isInitialized = true;
            
            if (callback != null) {
                callback.onTTSInitialized(true);
            }
        } else {
            Log.e(TAG, "TTS initialization failed");
            isInitialized = false;
            
            if (callback != null) {
                callback.onTTSInitialized(false);
            }
        }
    }

    /**
     * Configure speech parameters
     * 
     * Why slow and clear?
     * - Target users may be less literate
     * - Government info should be clearly understood
     * - Regional languages often need slower pace
     */
    private void configureSpeechParams() {
        // Speech rate: 0.0 to 2.0, 1.0 is normal
        // Using 0.9 for slightly slower, clearer speech
        textToSpeech.setSpeechRate(0.9f);
        
        // Pitch: 0.0 to 2.0, 1.0 is normal
        // Using 1.0 for natural sound
        textToSpeech.setPitch(1.0f);
    }

    /**
     * Set up listener for speech progress
     * 
     * Why needed?
     * - Know when speech starts/ends
     * - Handle errors during speech
     * - Enable UI updates during speech
     */
    private void setupProgressListener() {
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                Log.d(TAG, "Speech started: " + utteranceId);
                if (callback != null) {
                    callback.onSpeechStart();
                }
            }

            @Override
            public void onDone(String utteranceId) {
                Log.d(TAG, "Speech completed: " + utteranceId);
                if (callback != null) {
                    callback.onSpeechDone();
                }
            }

            @Override
            public void onError(String utteranceId) {
                Log.e(TAG, "Speech error: " + utteranceId);
                if (callback != null) {
                    callback.onSpeechError("Speech playback error");
                }
            }

            // API 21+ error callback with error code
            @Override
            public void onError(String utteranceId, int errorCode) {
                Log.e(TAG, "Speech error " + errorCode + ": " + utteranceId);
                if (callback != null) {
                    callback.onSpeechError("Speech error: " + errorCode);
                }
            }
        });
    }

    /**
     * Set language for speech output
     * 
     * @param languageCode BCP-47 code (e.g., "hi-IN", "te-IN")
     * @return true if language is supported
     */
    public boolean setLanguage(String languageCode) {
        if (textToSpeech == null || !isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot set language");
            return false;
        }

        // Parse language code (format: xx-YY)
        Locale locale = parseLocale(languageCode);

        Locale appliedLocale = applyBestAvailableLocale(locale);
        boolean exactOrCloseMatch = appliedLocale != null
                && appliedLocale.getLanguage().equals(locale.getLanguage());

        if (appliedLocale == null) {
            Log.w(TAG, "Language not supported, using default voice: " + languageCode);
            return false;
        }

        currentLocale = appliedLocale;
        Log.d(TAG, "Language set to: " + languageCode + " using locale " + appliedLocale);
        return exactOrCloseMatch;
    }

    private Locale applyBestAvailableLocale(Locale targetLocale) {
        int fullLocaleResult = textToSpeech.setLanguage(targetLocale);
        if (fullLocaleResult != TextToSpeech.LANG_MISSING_DATA
                && fullLocaleResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return targetLocale;
        }

        Locale languageOnlyLocale = new Locale(targetLocale.getLanguage());
        int languageOnlyResult = textToSpeech.setLanguage(languageOnlyLocale);
        if (languageOnlyResult != TextToSpeech.LANG_MISSING_DATA
                && languageOnlyResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return languageOnlyLocale;
        }

        Locale defaultLocale = Locale.getDefault();
        int defaultResult = textToSpeech.setLanguage(defaultLocale);
        if (defaultResult != TextToSpeech.LANG_MISSING_DATA
                && defaultResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return defaultLocale;
        }

        Locale englishLocale = Locale.US;
        int englishResult = textToSpeech.setLanguage(englishLocale);
        if (englishResult != TextToSpeech.LANG_MISSING_DATA
                && englishResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            return englishLocale;
        }

        return null;
    }

    /**
     * Parse BCP-47 language code to Locale
     */
    private Locale parseLocale(String languageCode) {
        if (languageCode == null || languageCode.isEmpty()) {
            return new Locale("te", "IN"); // Default to Telugu
        }

        String[] parts = languageCode.split("-");
        if (parts.length >= 2) {
            return new Locale(parts[0], parts[1]);
        } else {
            return new Locale(parts[0], "IN");
        }
    }

    /**
     * Speak the given text
     * 
     * @param text Text to speak
     * @param queueMode QUEUE_FLUSH (stop current) or QUEUE_ADD (add to queue)
     */
    public void speak(String text, int queueMode) {
        if (textToSpeech == null || !isInitialized) {
            Log.w(TAG, "TTS not initialized, cannot speak");
            if (callback != null) {
                callback.onSpeechError("TTS not ready");
            }
            return;
        }

        if (text == null || text.isEmpty()) {
            Log.w(TAG, "Empty text, nothing to speak");
            return;
        }

        // Store for replay
        lastSpokenText = text;

        // Generate unique utterance ID for tracking
        String utteranceId = UUID.randomUUID().toString();

        Log.d(TAG, "Speaking: " + text.substring(0, Math.min(50, text.length())) + "...");
        
        // Use newer speak method (API 21+)
        textToSpeech.speak(text, queueMode, null, utteranceId);
    }

    /**
     * Convenience method - speak with QUEUE_FLUSH
     */
    public void speak(String text) {
        speak(text, TextToSpeech.QUEUE_FLUSH);
    }

    /**
     * Replay the last spoken text
     * 
     * Why needed?
     * - Users may miss or want to hear again
     * - Accessibility feature
     */
    public void replay() {
        if (lastSpokenText != null && !lastSpokenText.isEmpty()) {
            Log.d(TAG, "Replaying last speech");
            speak(lastSpokenText);
        } else {
            Log.w(TAG, "No previous speech to replay");
        }
    }

    /**
     * Stop current speech immediately
     */
    public void stop() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            Log.d(TAG, "Speech stopped");
        }
    }

    /**
     * Check if TTS is currently speaking
     */
    public boolean isSpeaking() {
        return textToSpeech != null && textToSpeech.isSpeaking();
    }

    /**
     * Check if TTS is initialized
     */
    public boolean isReady() {
        return isInitialized;
    }

    /**
     * Check if language is supported
     */
    public boolean isLanguageSupported(String languageCode) {
        if (textToSpeech == null || !isInitialized) {
            return false;
        }
        
        Locale locale = parseLocale(languageCode);
        int result = textToSpeech.isLanguageAvailable(locale);
        
        return result != TextToSpeech.LANG_MISSING_DATA && 
               result != TextToSpeech.LANG_NOT_SUPPORTED;
    }

    /**
     * Get last spoken text
     */
    public String getLastSpokenText() {
        return lastSpokenText;
    }

    /**
     * Clean up resources
     * 
     * MUST be called when activity is destroyed
     * Prevents memory leaks and releases audio focus
     */
    public void destroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
            Log.d(TAG, "TextToSpeech destroyed");
        }
        isInitialized = false;
    }
}
