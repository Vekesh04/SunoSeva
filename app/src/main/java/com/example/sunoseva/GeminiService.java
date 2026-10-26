package com.example.sunoseva;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * GeminiService - Handles communication with OpenRouter API
 * 
 * Purpose:
 * - Create API requests with proper formatting (OpenAI-compatible)
 * - Handle secure API key usage
 * - Parse API responses
 * - Handle errors gracefully
 * 
 * Why separate class?
 * - Clean separation of network logic
 * - All API-related code in one place
 * - Easier to modify if API changes
 * - Security: API key handling isolated
 * 
 * API Used: OpenRouter (DeepSeek Chat - Free)
 * - Excellent multilingual support (Telugu, Hindi, Tamil, etc.)
 * - Free tier with generous limits
 * - OpenAI-compatible API format
 */
public class GeminiService {

    private static final String TAG = "GeminiService";
    private enum ApiProvider {
        OPENAI,
        OPENROUTER,
        GROQ
    }

    private static final String OPENAI_API_URL =
            "https://api.openai.com/v1/chat/completions";
    // OpenRouter API endpoint - using OpenAI-compatible format
    private static final String OPENROUTER_API_URL = 
            "https://openrouter.ai/api/v1/chat/completions";
    private static final String GROQ_API_URL =
            "https://api.groq.com/openai/v1/chat/completions";

    private static final String OPENAI_MODEL_NAME = "gpt-4o-mini";
    // Model to use - Google Gemma 3 27B (free, supports 140+ languages including Telugu, Hindi, Tamil)
    private static final String MODEL_NAME = "google/gemma-3-27b-it:free";
    private static final String GROQ_MODEL_NAME = "llama-3.3-70b-versatile";

    // JSON media type
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // OkHttp client for network requests
    private OkHttpClient httpClient;

    // Gson for JSON serialization/deserialization
    private Gson gson;

    // Provider keys - loaded from BuildConfig (set via local.properties)
    private final String openAiApiKey;
    private final String openRouterApiKey;
    private final String groqApiKey;

    // System prompt that guides the AI's behavior and instructs for structured output
    private static final String SYSTEM_PROMPT = 
        "You are SunoSeva (సునోసేవ), a helpful government assistance AI for Indian citizens. " +
        "Your role is to help people understand government schemes, services, and procedures. " +
        "However, you also have the ability to launch specific applications if the user explicitly requests it. " +
        "\n\n" +
        "Your response MUST ALWAYS be a JSON object with the following structure:\n" +
        "{\n" +
        "  \"response\": \"[Your conversational reply here, in the user's language]\",\n" +
        "  \"command\": {\n" +
        "    \"intent\": \"LAUNCH_APP\",\n" +
        "    \"appPackageName\": \"[Android package name of the app to launch]\"\n" +
        "  }\n" +
        "}\n\n" +
        "RULES FOR 'command' FIELD:\n" +
        "1. If the user explicitly asks to 'open', 'launch', 'start' an application, then include the 'command' field.\n" +
        "2. ONLY include the 'command' field if you are certain the user wants to launch an app. Otherwise, omit it.\n" +
        "3. For 'appPackageName', use the exact Android package name. If you don't know it, set it to 'UNKNOWN'.\n" +
        "   - For Aadhar: 'nic.goi.uidai.aadhaar.client'\n" +
        "   - For Gas Booking (example, Indian Oil): 'com.indianoil.b2c.android'\n" +
        "   - For Zomato: 'com.application.zomato'\n" +
        "   - Add more specific mappings if you know them.\n" +
        "4. For other queries not related to app launch, DO NOT include the 'command' field. Only provide a 'response'.\n" +
        "5. For queries asking how/process/steps (e.g., gas booking, Aadhaar updates), give clear step-by-step instructions and NEVER trigger app launch command unless explicitly asked to open app.\n" +
        "\n" +
        "RULES FOR 'response' FIELD:\n" +
        "1. RESPOND IN THE SAME LANGUAGE as the user's query (Telugu, Hindi, Tamil, etc.)\n" +
        "2. Use SIMPLE language that common citizens can understand\n" +
        "3. Be BRIEF and to the point - avoid long explanations\n" +
        "4. Focus ONLY on government-related topics like:\n" +
        "   - Government schemes (PM Kisan, Ayushman Bharat, etc.)\n" +
        "   - Document procedures (Aadhaar, PAN, Voter ID, Ration Card)\n" +
        "   - Government services (Passport, Driving License)\n" +
        "   - Pension and welfare schemes\n" +
        "   - Education scholarships\n" +
        "   - Housing schemes (PMAY)\n" +
        "   - Employment schemes (MGNREGA)\n" +
        "5. If user asks for a process, structure it as Step 1, Step 2, Step 3... with short actionable instructions.\n" +
        "6. If the query is NOT about government services AND NOT an app launch command, politely redirect in the SAME language as user input.\n" +
        "7. If you need more information, ASK clarifying questions\n" +
        "8. Provide ACCURATE information - if unsure, say so\n" +
        "9. Include helpline numbers or websites when relevant\n" +
        "10. NEVER provide medical, legal, or financial advice\n" +
        "11. Be respectful and patient - users may be illiterate\n" +
        "\n" +
        "Remember: Your responses will be spoken aloud via text-to-speech, " +
        "so keep sentences short and clear. Avoid special characters, asterisks, bullet points, and formatting within the 'response' text.";

    /**
     * Constructor
     * 
     * @param apiKey OpenRouter API key from BuildConfig
     */
    public GeminiService(String openAiApiKey, String openRouterApiKey, String groqApiKey) {
        this.openAiApiKey = openAiApiKey != null ? openAiApiKey.trim() : "";
        this.openRouterApiKey = openRouterApiKey != null ? openRouterApiKey.trim() : "";
        this.groqApiKey = groqApiKey != null ? groqApiKey.trim() : "";
        
        // Configure OkHttp client with timeouts
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)  // Connection timeout
                .readTimeout(60, TimeUnit.SECONDS)     // Read timeout (API can be slow)
                .writeTimeout(30, TimeUnit.SECONDS)    // Write timeout
                .build();
        
        gson = new Gson();
        
        Log.d(TAG, "GeminiService initialized");
    }

    /**
     * Check whether API key appears configured with a real value.
     */
    public boolean isApiKeyConfigured() {
        return isConfiguredValue(openAiApiKey)
                || isConfiguredValue(openRouterApiKey)
                || isConfiguredValue(groqApiKey);
    }

    private boolean isConfiguredValue(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        return !trimmed.isEmpty()
                && !trimmed.equals("YOUR_API_KEY_HERE")
                && !trimmed.equals("YOUR_OPENAI_API_KEY_HERE")
                && !trimmed.equals("YOUR_OPENROUTER_API_KEY_HERE")
                && !trimmed.equals("YOUR_GROQ_API_KEY_HERE");
    }

    private boolean hasOpenAiKey() {
        return isConfiguredValue(openAiApiKey);
    }

    private boolean hasOpenRouterKey() {
        return isConfiguredValue(openRouterApiKey);
    }

    private boolean hasGroqKey() {
        return isConfiguredValue(groqApiKey);
    }

    private List<ApiProvider> getProviderOrder() {
        List<ApiProvider> providers = new ArrayList<>();
        if (hasOpenAiKey()) {
            providers.add(ApiProvider.OPENAI);
        }
        if (hasGroqKey()) {
            providers.add(ApiProvider.GROQ);
        }
        if (hasOpenRouterKey()) {
            providers.add(ApiProvider.OPENROUTER);
        }
        return providers;
    }

    private String getApiUrl(ApiProvider provider) {
        switch (provider) {
            case OPENAI:
                return OPENAI_API_URL;
            case GROQ:
                return GROQ_API_URL;
            case OPENROUTER:
            default:
                return OPENROUTER_API_URL;
        }
    }

    private String getModelName(ApiProvider provider) {
        switch (provider) {
            case OPENAI:
                return OPENAI_MODEL_NAME;
            case GROQ:
                return GROQ_MODEL_NAME;
            case OPENROUTER:
            default:
                return MODEL_NAME;
        }
    }

    private String getApiKey(ApiProvider provider) {
        switch (provider) {
            case OPENAI:
                return openAiApiKey;
            case GROQ:
                return groqApiKey;
            case OPENROUTER:
            default:
                return openRouterApiKey;
        }
    }

    private String getProviderName(ApiProvider provider) {
        switch (provider) {
            case OPENAI:
                return "OpenAI";
            case GROQ:
                return "Groq";
            case OPENROUTER:
            default:
                return "OpenRouter";
        }
    }

    private boolean shouldTryAlternateProvider(int code) {
        return code == 401 || code == 402 || code == 403 || code == 404;
    }

    /**
     * Send query to OpenRouter and get response
     * 
     * This method runs SYNCHRONOUSLY - must be called from background thread!
     * 
     * @param userQuery User's question (can be in any language)
     * @return GeminiResponse object with response or error
     */
    public GeminiResponse query(String userQuery, String userLanguageCode) {
        List<ApiProvider> providers = getProviderOrder();
        Log.d(TAG, "Sending query using " + providers.size() + " configured provider(s): " + userQuery);

        // Validate API key
        if (!isApiKeyConfigured()) {
            Log.e(TAG, "API key is missing or invalid");
            return new GeminiResponse(false, "", "API key not configured", null, null);
        }

        // Validate query
        if (userQuery == null || userQuery.trim().isEmpty()) {
            return new GeminiResponse(false, "", "Empty query", null, null);
        }

        GeminiResponse lastFailure = null;
        for (int i = 0; i < providers.size(); i++) {
            ApiProvider provider = providers.get(i);
            GeminiResponse attemptResult = queryWithProvider(provider, userQuery, userLanguageCode);
            if (attemptResult.success) {
                return attemptResult;
            }
            lastFailure = attemptResult;
            if (!shouldTryAlternateProviderError(attemptResult.error) || i == providers.size() - 1) {
                break;
            }
            Log.w(TAG, "Switching provider after " + getProviderName(provider) + " failure: " + attemptResult.error);
        }
        return lastFailure != null
                ? lastFailure
                : new GeminiResponse(false, "", "Unknown API error", null, null);
    }

    private GeminiResponse queryWithProvider(ApiProvider provider, String userQuery, String userLanguageCode) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                // Create request body (OpenAI-compatible format)
                OpenRouterRequest openRouterRequest = createRequest(provider, userQuery, userLanguageCode);
                String requestJson = gson.toJson(openRouterRequest);

                Log.d(TAG, "Request JSON: " + requestJson.substring(0, Math.min(300, requestJson.length())));

                // Create HTTP request
                RequestBody body = RequestBody.create(requestJson, JSON);
                Request.Builder requestBuilder = new Request.Builder()
                        .url(getApiUrl(provider))
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("Authorization", "Bearer " + getApiKey(provider));

                if (provider == ApiProvider.OPENROUTER) {
                    requestBuilder
                            .addHeader("HTTP-Referer", "https://sunoseva.app")
                            .addHeader("X-Title", "SunoSeva");
                }

                Request request = requestBuilder.build();

                // Execute request
                Response response = httpClient.newCall(request).execute();

                // Read response body
                String responseBody = response.body() != null ? response.body().string() : "";

                Log.d(TAG, "Response code: " + response.code());
                Log.d(TAG, "Response body: " + responseBody.substring(0, Math.min(500, responseBody.length())));

                if (response.isSuccessful()) {
                    return parseResponse(responseBody, userLanguageCode);
                }

                if (attempt < 2 && isRetryableStatusCode(response.code())) {
                    Log.w(TAG, "Transient API error " + response.code() + ", retrying...");
                    safeRetryDelay();
                    continue;
                }
                return handleErrorResponse(provider, response.code(), responseBody);
            } catch (IOException e) {
                Log.e(TAG, "Network error with " + getProviderName(provider) + " (attempt " + attempt + "): " + e.getMessage());
                if (attempt < 2) {
                    safeRetryDelay();
                    continue;
                }
                return new GeminiResponse(false, "", "Network error: " + e.getMessage(), null, null);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error: " + e.getMessage());
                return new GeminiResponse(false, "", "Error: " + e.getMessage(), null, null);
            }
        }
        return new GeminiResponse(false, "", "Unknown API error", null, null);
    }

    private boolean shouldTryAlternateProviderError(String errorText) {
        if (errorText == null) {
            return false;
        }
        String lower = errorText.toLowerCase();
        return lower.contains("api key")
                || lower.contains("credits")
                || lower.contains("access denied")
                || lower.contains("model not found");
    }

    private boolean isRetryableStatusCode(int code) {
        return code == 429 || code == 500 || code == 502 || code == 503 || code == 504;
    }

    private void safeRetryDelay() {
        try {
            Thread.sleep(850);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Create OpenRouter API request object (OpenAI-compatible format)
     */
    private OpenRouterRequest createRequest(ApiProvider provider, String userQuery, String userLanguageCode) {
        OpenRouterRequest request = new OpenRouterRequest();
        
        request.model = getModelName(provider);
        request.messages = new ArrayList<>();
        
        // Add system message
        ChatMessage systemMessage = new ChatMessage();
        systemMessage.role = "system";
        systemMessage.content = SYSTEM_PROMPT;
        request.messages.add(systemMessage);

        ChatMessage languageControlMessage = new ChatMessage();
        languageControlMessage.role = "system";
        languageControlMessage.content =
                "IMPORTANT LANGUAGE RULE: User language is " + getLanguageDisplayName(userLanguageCode) +
                " (" + userLanguageCode + "). You MUST return the 'response' field strictly in this same language only.";
        request.messages.add(languageControlMessage);
        
        // Add user message
        ChatMessage userMessage = new ChatMessage();
        userMessage.role = "user";
        userMessage.content = userQuery;
        request.messages.add(userMessage);
        
        // Configure generation parameters
        request.temperature = 0.7f;       // Balanced creativity
        request.maxTokens = 500;          // Keep responses concise
        request.topP = 0.95f;             // Nucleus sampling
        
        return request;
    }

    /**
     * Parse successful API response (OpenAI-compatible format)
     */
    private GeminiResponse parseResponse(String responseBody, String userLanguageCode) {
        String conversationalResponse = "";
        String intent = null;
        String appPackageName = null;

        try {
            OpenRouterResponse apiResponse = gson.fromJson(responseBody, OpenRouterResponse.class);
            
            if (apiResponse != null && apiResponse.choices != null && 
                !apiResponse.choices.isEmpty()) {
                
                Choice choice = apiResponse.choices.get(0);
                
                if (choice.message != null && choice.message.content != null) {
                    String rawText = choice.message.content;
                    Log.d(TAG, "Raw Gemini response content: " + rawText.substring(0, Math.min(500, rawText.length())));
                    String extractedJsonText = extractJsonObject(rawText);

                    // Attempt to parse the structured JSON from Gemini's response
                    try {
                        GeminiStructuredResponse structuredResponse = extractedJsonText != null
                                ? gson.fromJson(extractedJsonText, GeminiStructuredResponse.class)
                                : gson.fromJson(rawText, GeminiStructuredResponse.class);
                        if (structuredResponse != null) {
                            conversationalResponse = structuredResponse.responseText != null ? structuredResponse.responseText : "";
                            if (structuredResponse.command != null) {
                                intent = structuredResponse.command.intent;
                                appPackageName = structuredResponse.command.appPackageName;
                            }
                        }
                    } catch (Exception jsonEx) {
                        Log.w(TAG, "Could not parse structured JSON from Gemini, falling back to raw text: " + jsonEx.getMessage());
                        // If structured parsing fails, assume the whole response is conversational
                        conversationalResponse = rawText;
                    }
                    
                    // Clean up response for TTS (remove markdown, asterisks, etc.)
                    conversationalResponse = cleanResponseForTTS(conversationalResponse, userLanguageCode);
                    
                    Log.d(TAG, "Extracted response: " + conversationalResponse.substring(0, Math.min(100, conversationalResponse.length())));
                    if (intent != null) {
                        Log.d(TAG, "Detected intent: " + intent + ", App: " + appPackageName);
                    }

                    return new GeminiResponse(true, conversationalResponse, null, intent, appPackageName);
                }
            }
            
            Log.w(TAG, "Response structure unexpected or empty");
            return new GeminiResponse(false, "", "Invalid response format", null, null);
            
        } catch (Exception e) {
            Log.e(TAG, "Parse error: " + e.getMessage());
            return new GeminiResponse(false, "", "Failed to parse response", null, null);
        }
    }

    /**
     * Extract the first JSON object from model text (handles markdown fenced responses).
     */
    private String extractJsonObject(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        Pattern fencedJsonPattern = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*\\})\\s*```");
        Matcher fencedJsonMatch = fencedJsonPattern.matcher(trimmed);
        if (fencedJsonMatch.find()) {
            return fencedJsonMatch.group(1);
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return trimmed.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }

    /**
     * Clean response text for TTS (remove markdown formatting and invisible characters)
     */
    private String cleanResponseForTTS(String text, String userLanguageCode) {
        if (text == null) return "";
        
        // Remove zero-width characters (critical for Telugu/Hindi response issues)
        text = text.replaceAll("[\\u200B-\\u200D\\uFEFF\\u2060\\u00AD]", "");
        
        // Remove control characters except newlines
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        
        // Remove markdown bold/italic
        text = text.replaceAll("\\*\\*", "");
        text = text.replaceAll("\\*", "");
        text = text.replaceAll("__", "");
        text = text.replaceAll("_", ""); // Changed from " " to ""
        
        // Remove markdown headers
        text = text.replaceAll("#+\\s*", "");
        
        // Remove bullet points
        text = text.replaceAll("^[\\-•]\\s*", "");
        text = text.replaceAll("\\n[\\-•]\\s*", "\n");
        
        // Remove numbered lists formatting
        text = text.replaceAll("\\n\\d+\\.\\s*", "\n");
        
        // Remove URLs
        text = text.replaceAll("https?://[\\S]+", "");
        
        // Remove code blocks
        text = text.replaceAll("```[^`]*```", "");
        text = text.replaceAll("`[^`]*`", "");
        
        // Remove extra whitespace and normalize
        text = text.replaceAll("\\s+", " ");
        text = text.trim();
        
        // Check if response is mostly empty/invalid
        String testContent = text.replaceAll("[\\s\\p{Punct}]", "");
        if (testContent.length() < 10) {
            Log.w(TAG, "Response too short or invalid: " + text);
            return getLanguageFallbackMessage(userLanguageCode);
        }
        
        return text;
    }

    private String getLanguageDisplayName(String languageCode) {
        if (languageCode == null) return "English";
        switch (languageCode) {
            case "te-IN": return "Telugu";
            case "hi-IN": return "Hindi";
            case "ta-IN": return "Tamil";
            case "bn-IN": return "Bengali";
            case "as-IN": return "Assamese";
            case "mr-IN": return "Marathi";
            case "gu-IN": return "Gujarati";
            case "kn-IN": return "Kannada";
            case "ml-IN": return "Malayalam";
            case "or-IN": return "Odia";
            case "pa-IN": return "Punjabi";
            case "ne-IN": return "Nepali";
            case "ur-IN": return "Urdu";
            case "sa-IN": return "Sanskrit";
            case "kok-IN": return "Konkani";
            case "doi-IN": return "Dogri";
            case "mai-IN": return "Maithili";
            case "brx-IN": return "Bodo";
            case "sd-IN": return "Sindhi";
            case "ks-IN": return "Kashmiri";
            case "en-IN":
            default: return "English";
        }
    }

    private String getLanguageFallbackMessage(String languageCode) {
        if (languageCode == null) {
            return "Sorry, I could not get a proper response. Please try again.";
        }
        switch (languageCode) {
            case "te-IN":
                return "క్షమించండి, సరైన సమాధానం పొందలేకపోయాను. దయచేసి మళ్ళీ ప్రయత్నించండి.";
            case "hi-IN":
                return "माफ़ कीजिए, सही जवाब नहीं मिल पाया। कृपया फिर से कोशिश करें।";
            case "ta-IN":
                return "மன்னிக்கவும், சரியான பதில் கிடைக்கவில்லை. தயவுசெய்து மீண்டும் முயற்சிக்கவும்.";
            case "bn-IN":
            case "as-IN":
                return "দুঃখিত, সঠিক উত্তর পাওয়া যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।";
            case "mr-IN":
                return "माफ करा, योग्य उत्तर मिळाले नाही. कृपया पुन्हा प्रयत्न करा.";
            case "gu-IN":
                return "માફ કરશો, યોગ્ય જવાબ મળી શક્યો નથી. કૃપા કરીને ફરી પ્રયાસ કરો.";
            case "kn-IN":
                return "ಕ್ಷಮಿಸಿ, ಸರಿಯಾದ ಉತ್ತರ ಸಿಗಲಿಲ್ಲ. ದಯವಿಟ್ಟು ಮತ್ತೆ ಪ್ರಯತ್ನಿಸಿ.";
            case "ml-IN":
                return "ക്ഷമിക്കണം, ശരിയായ മറുപടി ലഭിച്ചില്ല. ദയവായി വീണ്ടും ശ്രമിക്കുക.";
            case "or-IN":
                return "କ୍ଷମା କରନ୍ତୁ, ଠିକ ଉତ୍ତର ମିଳିଲା ନାହିଁ। ଦୟାକରି ପୁଣି ଚେଷ୍ଟା କରନ୍ତୁ।";
            case "pa-IN":
                return "ਮਾਫ ਕਰਨਾ, ਠੀਕ ਜਵਾਬ ਨਹੀਂ ਮਿਲਿਆ। ਕਿਰਪਾ ਕਰਕੇ ਫਿਰ ਕੋਸ਼ਿਸ਼ ਕਰੋ।";
            case "ne-IN":
            case "sa-IN":
            case "kok-IN":
            case "doi-IN":
            case "mai-IN":
            case "brx-IN":
                return "माफ गर्नुहोस्, सही जवाफ प्राप्त भएन। कृपया फेरि प्रयास गर्नुहोस्।";
            case "ur-IN":
            case "sd-IN":
            case "ks-IN":
                return "معذرت، درست جواب نہیں مل سکا۔ براہ کرم دوبارہ کوشش کریں۔";
            default:
                return "Sorry, I could not get a proper response. Please try again.";
        }
    }

    /**
     * Handle error responses from API
     */
    private GeminiResponse handleErrorResponse(ApiProvider provider, int code, String responseBody) {
        String errorMessage;
        String lowerBody = responseBody != null ? responseBody.toLowerCase() : "";
        String providerName = getProviderName(provider);
        
        switch (code) {
            case 400:
                errorMessage = "Invalid request";
                break;
            case 401:
                if (lowerBody.contains("expired_api_key")) {
                    errorMessage = providerName + " API key expired. Update key in local.properties.";
                } else {
                    errorMessage = providerName + " API key invalid. Update key in local.properties.";
                }
                break;
            case 402:
                errorMessage = "Insufficient credits. Please add credits to your " + providerName + " account.";
                break;
            case 403:
                errorMessage = "Access denied. Please check your " + providerName + " API key permissions.";
                break;
            case 404:
                errorMessage = "Model not found. Please try again.";
                break;
            case 429:
                errorMessage = "Rate limit exceeded. Please wait a moment and try again.";
                break;
            case 500:
            case 502:
            case 503:
                errorMessage = "Server error. Please try again later.";
                break;
            default:
                errorMessage = "API error (code: " + code + ")";
        }
        
        Log.e(TAG, "API error: " + errorMessage + " | Response: " + responseBody);
        return new GeminiResponse(false, "", errorMessage, null, null);
    }

    // ==================== Data Classes for JSON (OpenAI-compatible) ====================

    /**
     * Response wrapper for MainActivity with intent information
     */
    public static class GeminiResponse {
        public final boolean success;
        public final String text;
        public final String error;
        public final String intent;
        public final String appPackageName;

        public GeminiResponse(boolean success, String text, String error, String intent, String appPackageName) {
            this.success = success;
            this.text = text;
            this.error = error;
            this.intent = intent;
            this.appPackageName = appPackageName;
        }

        public boolean isLaunchAppIntent() {
            if (!"LAUNCH_APP".equals(intent) || appPackageName == null) {
                return false;
            }
            String normalized = appPackageName.trim();
            if (normalized.isEmpty() || "UNKNOWN".equalsIgnoreCase(normalized)) {
                return false;
            }
            // Basic android package format guard (prevents opening Play Store for junk values).
            return normalized.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$");
        }
    }

    // Request structure classes (OpenAI-compatible)
    private static class OpenRouterRequest {
        String model;
        List<ChatMessage> messages;
        
        @SerializedName("temperature")
        Float temperature;
        
        @SerializedName("max_tokens")
        Integer maxTokens;
        
        @SerializedName("top_p")
        Float topP;
    }

    private static class ChatMessage {
        String role;      // "system", "user", or "assistant"
        String content;
    }

    // Response structure classes (OpenAI-compatible)
    private static class OpenRouterResponse {
        String id;
        String model;
        List<Choice> choices;
        Usage usage;
    }

    private static class Choice {
        ChatMessage message;
        
        @SerializedName("finish_reason")
        String finishReason;
        
        int index;
    }

    private static class Usage {
        @SerializedName("prompt_tokens")
        int promptTokens;
        
        @SerializedName("completion_tokens")
        int completionTokens;
        
        @SerializedName("total_tokens")
        int totalTokens;
    }

    // New: Structured response from Gemini for intent recognition
    private static class GeminiStructuredResponse {
        @SerializedName("response")
        String responseText;

        Command command;
    }

    private static class Command {
        String intent; // e.g., "LAUNCH_APP"
        @SerializedName("appPackageName")
        String appPackageName; // e.g., "com.application.zomato"
    }
}
