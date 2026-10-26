package com.example.sunoseva;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utils - Helper utility class for SunoSeva
 * 
 * Purpose:
 * - Language detection from text
 * - Input validation and sanitization
 * - Network connectivity checks
 * - Text processing utilities
 * 
 * Why separate class?
 * - Clean separation of concerns
 * - Reusable across activities
 * - Easier unit testing
 */
public class Utils {

    private static final String TAG = "SunoSevaUtils";

    // Minimum valid input length (filters noise)
    private static final int MIN_INPUT_LENGTH = 2;
    // Maximum input length to send to API
    private static final int MAX_INPUT_LENGTH = 500;

    // Unicode ranges for Indian language detection
    private static final Map<String, Pattern> LANGUAGE_PATTERNS = new HashMap<>();

    static {
        // Devanagari script (Hindi, Marathi, Sanskrit)
        LANGUAGE_PATTERNS.put("hi-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("mr-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("ne-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("sa-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("kok-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("doi-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("mai-IN", Pattern.compile("[\\u0900-\\u097F]"));
        LANGUAGE_PATTERNS.put("brx-IN", Pattern.compile("[\\u0900-\\u097F]"));
        // Telugu script
        LANGUAGE_PATTERNS.put("te-IN", Pattern.compile("[\\u0C00-\\u0C7F]"));
        // Tamil script
        LANGUAGE_PATTERNS.put("ta-IN", Pattern.compile("[\\u0B80-\\u0BFF]"));
        // Bengali script
        LANGUAGE_PATTERNS.put("bn-IN", Pattern.compile("[\\u0980-\\u09FF]"));
        LANGUAGE_PATTERNS.put("as-IN", Pattern.compile("[\\u0980-\\u09FF]"));
        // Gujarati script
        LANGUAGE_PATTERNS.put("gu-IN", Pattern.compile("[\\u0A80-\\u0AFF]"));
        // Kannada script
        LANGUAGE_PATTERNS.put("kn-IN", Pattern.compile("[\\u0C80-\\u0CFF]"));
        // Malayalam script
        LANGUAGE_PATTERNS.put("ml-IN", Pattern.compile("[\\u0D00-\\u0D7F]"));
        // Odia script
        LANGUAGE_PATTERNS.put("or-IN", Pattern.compile("[\\u0B00-\\u0B7F]"));
        // Punjabi (Gurmukhi) script
        LANGUAGE_PATTERNS.put("pa-IN", Pattern.compile("[\\u0A00-\\u0A7F]"));
        // Arabic-based scripts used by Urdu, Kashmiri, Sindhi
        LANGUAGE_PATTERNS.put("ur-IN", Pattern.compile("[\\u0600-\\u06FF]"));
        LANGUAGE_PATTERNS.put("sd-IN", Pattern.compile("[\\u0600-\\u06FF]"));
        LANGUAGE_PATTERNS.put("ks-IN", Pattern.compile("[\\u0600-\\u06FF]"));
    }

    /**
     * Check if device has active internet connection
     * 
     * Why needed?
     * - Gemini API requires internet
     * - Better UX to check before making API call
     * - Show appropriate error message
     */
    public static boolean isNetworkAvailable(Context context) {
        if (context == null) return false;

        ConnectivityManager connectivityManager = 
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager == null) return false;

        NetworkCapabilities capabilities = 
                connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
        
        if (capabilities != null) {
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                   capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                   capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        }

        return false;
    }

    /**
     * Validate and sanitize user input before sending to API
     * 
     * Why needed?
     * - Filter out noise from speech recognition
     * - Prevent empty or too short queries
     * - Limit input length for API efficiency
     */
    public static ValidationResult validateInput(String input) {
        if (input == null) {
            return new ValidationResult(false, "", "Input is null");
        }

        // Trim whitespace
        String cleaned = input.trim();

        // Check if empty
        if (cleaned.isEmpty()) {
            return new ValidationResult(false, "", "Empty input");
        }

        // Check minimum length (filters noise like "um", "uh")
        if (cleaned.length() < MIN_INPUT_LENGTH) {
            return new ValidationResult(false, cleaned, "Input too short");
        }

        // Truncate if too long
        if (cleaned.length() > MAX_INPUT_LENGTH) {
            cleaned = cleaned.substring(0, MAX_INPUT_LENGTH);
            Log.d(TAG, "Input truncated to " + MAX_INPUT_LENGTH + " characters");
        }

        // Remove any special characters that might cause issues
        cleaned = cleaned.replaceAll("[\\p{Cntrl}]", "");

        return new ValidationResult(true, cleaned, "Valid");
    }

    /**
     * Detect language from text based on script
     * 
     * Why needed?
     * - Match TTS language with input language
     * - Handle transliterated text appropriately
     * - Improve response quality
     */
    public static String detectLanguage(String text) {
        return detectLanguage(text, null);
    }

    /**
     * Detect language from text while honoring the user's selected language
     * when multiple languages share the same script.
     */
    public static String detectLanguage(String text, String preferredLanguageCode) {
        if (text == null || text.isEmpty()) {
            return preferredLanguageCode != null && !preferredLanguageCode.isEmpty()
                    ? preferredLanguageCode
                    : "en-IN";
        }

        // Count characters matching each language pattern
        Map<String, Integer> scores = new HashMap<>();

        for (Map.Entry<String, Pattern> entry : LANGUAGE_PATTERNS.entrySet()) {
            int count = 0;
            for (char c : text.toCharArray()) {
                if (entry.getValue().matcher(String.valueOf(c)).matches()) {
                    count++;
                }
            }
            scores.put(entry.getKey(), count);
        }

        // Find language with highest score
        String detectedLang = "en-IN";
        int maxScore = 0;

        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > maxScore) {
                maxScore = entry.getValue();
                detectedLang = entry.getKey();
            }
        }

        // If no Indian script detected, default to English (likely transliterated)
        if (maxScore == 0) {
            detectedLang = "en-IN";
        }

        if (preferredLanguageCode != null && !preferredLanguageCode.isEmpty()) {
            Integer preferredScore = scores.get(preferredLanguageCode);
            if (preferredScore != null && preferredScore > 0 && preferredScore == maxScore) {
                detectedLang = preferredLanguageCode;
            }
        }

        Log.d(TAG, "Detected language: " + detectedLang + " (score: " + maxScore
                + ", preferred: " + preferredLanguageCode + ")");
        return detectedLang;
    }

    /**
     * Clean API response for better TTS output
     * 
     * Why needed?
     * - Remove markdown symbols (**, ##, etc.)
     * - Remove URLs (TTS reads them character by character)
     * - Clean up formatting issues
     */
    public static String cleanResponseForSpeech(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }

        String cleaned = response;

        // Remove markdown bold/italic
        cleaned = cleaned.replaceAll("\\*\\*", "");
        cleaned = cleaned.replaceAll("\\*", "");
        cleaned = cleaned.replaceAll("__", "");
        cleaned = cleaned.replaceAll("_", " ");

        // Remove markdown headers
        cleaned = cleaned.replaceAll("#{1,6}\\s*", "");

        // Remove markdown links, keep text
        cleaned = cleaned.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");

        // Remove URLs
        cleaned = cleaned.replaceAll("https?://\\S+", "");

        // Remove bullet points
        cleaned = cleaned.replaceAll("^[•\\-*]\\s*", "");
        cleaned = cleaned.replaceAll("\\n[•\\-*]\\s*", "\n");

        // Remove numbered lists markers
        cleaned = cleaned.replaceAll("^\\d+\\.\\s*", "");
        cleaned = cleaned.replaceAll("\\n\\d+\\.\\s*", "\n");

        // Remove multiple spaces
        cleaned = cleaned.replaceAll("\\s+", " ");

        // Remove multiple newlines
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");

        return cleaned.trim();
    }

    /**
     * Clean API response for display in UI
     * 
     * Why needed?
     * - Less aggressive than speech cleaning
     * - Preserve some formatting for readability
     */
    public static String cleanResponseForDisplay(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }

        String cleaned = response;

        // Remove markdown bold/italic but keep structure
        cleaned = cleaned.replaceAll("\\*\\*", "");
        cleaned = cleaned.replaceAll("__", "");

        // Remove markdown headers, keep text
        cleaned = cleaned.replaceAll("#{1,6}\\s*", "");

        // Clean up excessive whitespace
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        cleaned = cleaned.replaceAll("\\s+$", "");

        return cleaned.trim();
    }

    /**
     * Get human-readable language name from code
     */
    public static String getLanguageName(String languageCode) {
        switch (languageCode) {
            case "hi-IN": return "हिंदी";
            case "te-IN": return "తెలుగు";
            case "ta-IN": return "தமிழ்";
            case "bn-IN": return "বাংলা";
            case "as-IN": return "অসমীয়া";
            case "mr-IN": return "मराठी";
            case "gu-IN": return "ગુજરાતી";
            case "kn-IN": return "ಕನ್ನಡ";
            case "ml-IN": return "മലയാളം";
            case "or-IN": return "ଓଡ଼ିଆ";
            case "pa-IN": return "ਪੰਜਾਬੀ";
            case "ne-IN": return "नेपाली";
            case "ur-IN": return "اردو";
            case "sa-IN": return "संस्कृतम्";
            case "kok-IN": return "कोंकणी";
            case "doi-IN": return "डोगरी";
            case "mai-IN": return "मैथिली";
            case "brx-IN": return "बोडो";
            case "sd-IN": return "سنڌي";
            case "ks-IN": return "कॉशुर";
            case "en-IN": return "English";
            default: return "English";
        }
    }

    /**
     * Validation result wrapper class
     * 
     * Why a class?
     * - Return both validity status and cleaned text
     * - Include reason for failure (debugging)
     */
    public static class ValidationResult {
        public final boolean isValid;
        public final String cleanedText;
        public final String message;

        public ValidationResult(boolean isValid, String cleanedText, String message) {
            this.isValid = isValid;
            this.cleanedText = cleanedText;
            this.message = message;
        }
    }
}
