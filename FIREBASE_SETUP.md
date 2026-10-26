# Firebase setup

1. Create your own Firebase project and register an Android app using `com.example.sunoseva`.
2. Download your `google-services.json` into `app/` (this file is ignored).
3. Enable Anonymous Authentication, Firestore, and Cloud Storage as needed.
4. Review the included `firestore.rules` and `storage.rules` before deploying them to your own project.
5. In Android Studio, sync Gradle and run on an Android 8.0+ device.

The code stores interaction history using Firebase services. Use a development project and test data during evaluation.

The prepared rules restrict history to its authenticated owner. Storage backups are scoped to `conversation_history/{uid}/...`. Validate these rules in the Firebase Emulator Suite before production use.
