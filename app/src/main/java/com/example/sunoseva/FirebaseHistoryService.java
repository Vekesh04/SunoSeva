package com.example.sunoseva;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Saves question/response history into Firebase so the app has a real backend.
 */
public class FirebaseHistoryService {

    private static final String TAG = "FirebaseHistoryService";
    private static final String COLLECTION_NAME = "conversation_history";

    private final Context context;
    private final FirebaseFirestore firestore;
    private final FirebaseStorage storage;
    private final FirebaseAuth auth;
    private final Gson gson;
    private final List<HistoryRecord> pendingRecords;
    private boolean authInProgress = false;

    public FirebaseHistoryService(Context context) {
        this.context = context.getApplicationContext();
        this.firestore = FirebaseFirestore.getInstance();
        this.storage = FirebaseStorage.getInstance();
        this.auth = FirebaseAuth.getInstance();
        this.gson = new Gson();
        this.pendingRecords = new ArrayList<>();
        ensureAnonymousSession();
    }

    private void ensureAnonymousSession() {
        if (auth.getCurrentUser() != null || authInProgress) {
            return;
        }

        authInProgress = true;
        auth.signInAnonymously()
                .addOnSuccessListener(result -> {
                    authInProgress = false;
                    Log.d(TAG, "Firebase anonymous session ready");
                    flushPendingRecords();
                })
                .addOnFailureListener(error -> {
                    authInProgress = false;
                    Log.w(TAG, "Anonymous sign-in failed, trying pending writes without auth session", error);
                    flushPendingRecords();
                });
    }

    public void saveInteraction(String query,
                                String response,
                                String languageCode,
                                String source,
                                String status) {
        if (query == null || query.trim().isEmpty()) {
            return;
        }

        HistoryRecord record = new HistoryRecord(query, response, languageCode, source, status);
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser == null) {
            pendingRecords.add(record);
            ensureAnonymousSession();
            return;
        }

        dispatchSave(record, currentUser);
    }

    private void flushPendingRecords() {
        if (pendingRecords.isEmpty()) {
            return;
        }

        FirebaseUser currentUser = auth.getCurrentUser();
        List<HistoryRecord> snapshot = new ArrayList<>(pendingRecords);
        pendingRecords.clear();
        for (HistoryRecord record : snapshot) {
            dispatchSave(record, currentUser);
        }
    }

    private void dispatchSave(HistoryRecord record, FirebaseUser currentUser) {
        String documentId = firestore.collection(COLLECTION_NAME).document().getId();
        Map<String, Object> payload = buildPayload(documentId, record, currentUser);

        firestore.collection(COLLECTION_NAME)
                .document(documentId)
                .set(payload)
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "Saved conversation history: " + documentId);
                    uploadHistoryBackup(documentId, payload);
                })
                .addOnFailureListener(error ->
                        Log.e(TAG, "Failed to save conversation history", error));
    }

    private Map<String, Object> buildPayload(String documentId,
                                             HistoryRecord record,
                                             FirebaseUser currentUser) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("id", documentId);
        payload.put("query", record.query);
        payload.put("response", record.response != null ? record.response : "");
        payload.put("languageCode", record.languageCode != null ? record.languageCode : "en-IN");
        payload.put("source", record.source != null ? record.source : "unknown");
        payload.put("status", record.status != null ? record.status : "success");
        payload.put("createdAt", FieldValue.serverTimestamp());
        payload.put("clientTimestamp", System.currentTimeMillis());
        payload.put("appName", context.getPackageName());
        payload.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL);
        payload.put("firebaseUserId", currentUser != null ? currentUser.getUid() : "anonymous-pending");

        return payload;
    }

    private void uploadHistoryBackup(String documentId, Map<String, Object> payload) {
        try {
            String folder = new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date());
            StorageReference fileReference = storage.getReference()
                    .child(COLLECTION_NAME)
                    .child(auth.getCurrentUser().getUid())
                    .child(folder)
                    .child(documentId + ".json");

            byte[] content = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
            StorageMetadata metadata = new StorageMetadata.Builder()
                    .setContentType("application/json")
                    .build();

            fileReference.putBytes(content, metadata)
                    .addOnSuccessListener(taskSnapshot ->
                            Log.d(TAG, "Uploaded history backup: " + documentId))
                    .addOnFailureListener(error ->
                            Log.w(TAG, "History backup upload skipped", error));
        } catch (Exception error) {
            Log.w(TAG, "Could not prepare storage backup", error);
        }
    }

    private static class HistoryRecord {
        final String query;
        final String response;
        final String languageCode;
        final String source;
        final String status;

        HistoryRecord(String query, String response, String languageCode, String source, String status) {
            this.query = query;
            this.response = response;
            this.languageCode = languageCode;
            this.source = source;
            this.status = status;
        }
    }
}
