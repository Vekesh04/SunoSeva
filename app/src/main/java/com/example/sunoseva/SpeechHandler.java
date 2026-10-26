package com.example.sunoseva;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.util.ArrayList;
import java.util.Locale;

/**
 * SpeechHandler - Manages Android Speech Recognition
 * 
 * Purpose:
 * - Initialize and manage SpeechRecognizer
 * - Configure language for regional speech (Hindi, Telugu, Tamil, etc.)
 * - Handle all recognition callbacks
 * - Provide clean interface for MainActivity
 * 
 * Why separate class?
 * - Clean separation from UI logic
 * - Proper lifecycle management
 * - Reusable component
 * - Easier to test and maintain
 * 
 * Key Feature: Uses Android's built-in SpeechRecognizer
 * - No external SDK dependencies
 * - Works offline for some languages
 * - Supports Indian languages natively
 */
public class SpeechHandler {

    private static final String TAG = "SpeechHandler";

    // Android's speech recognizer instance
    private SpeechRecognizer speechRecognizer;
    
    // Intent to configure speech recognition
    private Intent recognizerIntent;
    
    // Callback interface for communication with activity
    private SpeechCallback callback;
    
    // Current language code (e.g., "hi-IN", "te-IN")
    private String currentLanguage = "hi-IN";
    
    // Track if currently listening
    private boolean isListening = false;
    
    // Application context
    private Context context;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private boolean pendingClientRetry = false;

    // Constants for speech recognition timeouts (Increased values)
    private static final long SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 5000;
    private static final long SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 8000;
    private static final long SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 3000;

    /**
     * Callback interface
     * 
     * Why interface?
     * - Decouples SpeechHandler from MainActivity
     * - Activity implements this to receive events
     * - Clean communication pattern
     */
    public interface SpeechCallback {
        void onReadyForSpeech();
        void onBeginningOfSpeech();
        void onEndOfSpeech();
        void onResults(String recognizedText);
        void onPartialResults(String partialText);
        void onError(int errorCode, String errorMessage);
    }

    /**
     * Constructor
     * 
     * @param context Application context (not activity - to prevent leaks)
     * @param callback Callback for speech events
     */
    public SpeechHandler(Context context, SpeechCallback callback) {
        // Keep activity context for better SpeechRecognizer stability on some devices.
        this.context = context;
        this.callback = callback;
        initializeSpeechRecognizer();
    }

    /**
     * Initialize the speech recognizer
     * 
     * Why in separate method?
     * - Can be called again after destroy
     * - Clear initialization flow
     */
    private void initializeSpeechRecognizer() {
        // Check if speech recognition is available on device
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available on this device");
            if (callback != null) {
                callback.onError(-1, "Speech recognition not available");
            }
            return;
        }

        // Create speech recognizer
        if (speechRecognizer != null) {
            speechRecognizer.destroy(); // Destroy existing instance before creating a new one
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        
        // Set up recognition listener
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            
            /**
             * Called when recognizer is ready
             * UI should show "Listening..." state
             */
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "Ready for speech");
                isListening = true;
                pendingClientRetry = false;
                if (callback != null) {
                    callback.onReadyForSpeech();
                }
            }

            /**
             * Called when user starts speaking
             * Good for visual feedback
             */
            @Override
            public void onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking");
                if (callback != null) {
                    callback.onBeginningOfSpeech();
                }
            }

            /**
             * RMS (volume) changed - can be used for visual feedback
             * Not critical for our use case
             */
            @Override
            public void onRmsChanged(float rmsdB) {
                // Could be used for microphone animation
                // Not implementing to keep it simple
            }

            /**
             * Buffer received - not used in our implementation
             */
            @Override
            public void onBufferReceived(byte[] buffer) {
                // Not needed for our use case
            }

            /**
             * Called when user stops speaking
             * UI should show "Processing..." state
             */
            @Override
            public void onEndOfSpeech() {
                Log.d(TAG, "User stopped speaking");
                isListening = false;
                if (callback != null) {
                    callback.onEndOfSpeech();
                }
            }

            /**
             * Error occurred during recognition
             * Must handle gracefully to not leave user stuck
             */
            @Override
            public void onError(int error) {
                isListening = false;
                String errorMessage = getErrorMessage(error);
                Log.e(TAG, "Speech recognition error: " + errorMessage + " (Code: " + error + ")");
                
                if (error == SpeechRecognizer.ERROR_CLIENT) {
                    Log.w(TAG, "SpeechRecognizer ERROR_CLIENT detected. Attempting to re-initialize SpeechRecognizer.");
                    destroy(); // Destroy the current instance
                    initializeSpeechRecognizer(); // Re-initialize a new instance
                    // Surface a retry message and try one automatic restart.
                    if (callback != null) {
                        callback.onError(error, "Voice service reset. Retrying...");
                    }
                    if (!pendingClientRetry) {
                        pendingClientRetry = true;
                        retryHandler.postDelayed(() -> {
                            if (speechRecognizer != null && !isListening) {
                                startListening();
                            }
                        }, 450);
                    }
                } else {
                    if (callback != null) {
                        callback.onError(error, errorMessage);
                    }
                }
            }

            /**
             * Final recognition results received
             * This is the main result we care about
             */
            @Override
            public void onResults(Bundle results) {
                isListening = false;
                
                ArrayList<String> matches = results.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                
                if (matches != null && !matches.isEmpty()) {
                    // Get the most confident result (first one)
                    String recognizedText = matches.get(0);
                    Log.d(TAG, "Recognized: " + recognizedText);
                    
                    if (callback != null) {
                        callback.onResults(recognizedText);
                    }
                } else {
                    Log.d(TAG, "No results received");
                    if (callback != null) {
                        callback.onError(SpeechRecognizer.ERROR_NO_MATCH, "No speech recognized");
                    }
                }
            }

            /**
             * Partial results while user is still speaking
             * Good for showing real-time transcription
             */
            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> matches = partialResults.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION);
                
                if (matches != null && !matches.isEmpty()) {
                    String partialText = matches.get(0);
                    Log.d(TAG, "Partial: " + partialText);
                    
                    if (callback != null) {
                        callback.onPartialResults(partialText);
                    }
                }
            }

            /**
             * Recognition event - not critical for our use case
             */
            @Override
            public void onEvent(int eventType, Bundle params) {
                Log.d(TAG, "Speech event: " + eventType);
            }
        });

        // Configure recognition intent
        setupRecognizerIntent();
    }

    /**
     * Configure the speech recognition intent
     * 
     * This is where we set:
     * - Language for recognition
     * - Recognition mode (free form vs command)
     * - Partial results preference
     */
    private void setupRecognizerIntent() {
        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        
        // Use free-form speech model (not web search or commands)
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        
        // Set the language explicitly
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        
        // REMOVED: recognizerIntent.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true);
        // This flag can sometimes be too restrictive and cause server errors if the server struggles with the strict preference.
        
        // Enable partial results for real-time feedback
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        
        // Set max results to 1 (we only need the best match)
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        
        // Prompt shown to user (optional, device dependent)
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, "बोलिए... / Speak now...");

        // Adjust speech input timeouts for better recognition (Increased values)
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                SPEECH_INPUT_MINIMUM_LENGTH_MILLIS);

        Log.d(TAG, "Recognition intent configured for language: " + currentLanguage);
    }

    /**
     * Set the language for speech recognition
     * 
     * @param languageCode BCP-47 language code (e.g., "hi-IN", "te-IN")
     * 
     * Why BCP-47 codes?
     * - Standard format Android uses
     * - Includes region variant (IN for India)
     * - Helps select correct accent model
     */
    public void setLanguage(String languageCode) {
        this.currentLanguage = languageCode;
        setupRecognizerIntent(); // Recreate intent with new language
        Log.d(TAG, "Language set to: " + languageCode);
    }

    /**
     * Start listening for speech
     * 
     * Call this when user presses mic button
     */
    public void startListening() {
        if (speechRecognizer == null) {
            Log.e(TAG, "SpeechRecognizer is null, reinitializing");
            initializeSpeechRecognizer();
        }

        if (!isListening) {
            Log.d(TAG, "Starting to listen...");
            try {
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
            } catch (SecurityException e) {
                Log.e(TAG, "Missing microphone permission: " + e.getMessage());
                if (callback != null) {
                    callback.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, "Microphone permission missing");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start listening: " + e.getMessage());
                if (callback != null) {
                    callback.onError(SpeechRecognizer.ERROR_CLIENT, "Speech service unavailable. Please try again.");
                }
            }
        } else {
            Log.d(TAG, "Already listening, restarting recognizer");
            try {
                speechRecognizer.cancel();
                isListening = false;
                retryHandler.postDelayed(this::startListening, 250);
            } catch (Exception e) {
                Log.e(TAG, "Failed to restart listening: " + e.getMessage());
            }
        }
    }

    /**
     * Stop listening
     * 
     * Call this when user presses stop or times out
     */
    public void stopListening() {
        if (speechRecognizer != null && isListening) {
            Log.d(TAG, "Stopping listening");
            speechRecognizer.stopListening();
            isListening = false;
        }
    }

    /**
     * Cancel recognition
     * 
     * More abrupt than stopListening - discards partial results
     */
    public void cancel() {
        if (speechRecognizer != null) {
            Log.d(TAG, "Cancelling recognition");
            speechRecognizer.cancel();
            isListening = false;
        }
    }

    /**
     * Check if currently listening
     */
    public boolean isListening() {
        return isListening;
    }

    /**
     * Get current language
     */
    public String getCurrentLanguage() {
        return currentLanguage;
    }

    /**
     * Clean up resources
     * 
     * MUST be called when activity is destroyed
     * Prevents memory leaks
     */
    public void destroy() {
        if (speechRecognizer != null) {
            speechRecognizer.cancel();
            speechRecognizer.destroy();
            speechRecognizer = null;
            Log.d(TAG, "SpeechRecognizer destroyed");
        }
        isListening = false;
    }

    /**
     * Convert error code to human-readable message
     * 
     * Why needed?
     * - Error codes are integers
     * - Users need understandable messages
     * - Helps with debugging
     */
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case SpeechRecognizer.ERROR_AUDIO:
                return "Audio recording error";
            case SpeechRecognizer.ERROR_CLIENT:
                return "Speech service error";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Insufficient permissions";
            case SpeechRecognizer.ERROR_NETWORK:
                return "Speech network error";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Network timeout";
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "No speech match found";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Recognizer is busy";
            case SpeechRecognizer.ERROR_SERVER:
                return "Server error";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "No speech input";
            default:
                return "Unknown error: " + errorCode;
        }
    }

    /**
     * Check if speech recognition is available
     * 
     * Static method - can be called before creating instance
     */
    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }
}
