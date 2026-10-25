package com.example.auth

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AuthUser(val displayName: String, val email: String, val photoUrl: String?)

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SigningIn(val previous: AuthUser? = null) : AuthState
    data class SignedIn(val user: AuthUser) : AuthState
    data class Error(val message: String) : AuthState
}

/** Google sign-in through Credential Manager, exchanged for a Firebase Auth session. */
object AuthManager {
    private const val TAG = "AuthManager"
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private fun auth(context: Context): FirebaseAuth? = try {
        if (FirebaseApp.getApps(context).isEmpty()) FirebaseApp.initializeApp(context)
        FirebaseAuth.getInstance()
    } catch (e: Exception) {
        Log.e(TAG, "Firebase Auth unavailable", e)
        null
    }

    fun getCurrentUser(context: Context): FirebaseUser? = auth(context)?.currentUser

    /** Reads the persisted Firebase session; call once at startup. */
    fun refreshState(context: Context) {
        val user = getCurrentUser(context)?.toAuthUser()
        _state.value = if (user != null) AuthState.SignedIn(user) else AuthState.SignedOut
    }

    suspend fun signInWithGoogle(context: Context) {
        val firebaseAuth = auth(context)
        if (firebaseAuth == null) {
            _state.value = AuthState.Error("تعذر تهيئة تسجيل الدخول. تحقق من إعداد Firebase.")
            return
        }
        _state.value = AuthState.SigningIn()
        try {
            val option = GetSignInWithGoogleOption.Builder(
                context.getString(R.string.default_web_client_id)
            )
                .build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(context).getCredential(context, request)
            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                _state.value = AuthState.Error("نوع بيانات الدخول غير مدعوم.")
                return
            }
            val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val user = firebaseAuth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null))
                .await().user
            _state.value = user?.toAuthUser()?.let { AuthState.SignedIn(it) }
                ?: AuthState.Error("تعذر إكمال تسجيل الدخول.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            _state.value = AuthState.SignedOut
        } catch (e: NoCredentialException) {
            _state.value = AuthState.Error("لا يوجد حساب Google على هذا الجهاز. أضف حساباً ثم أعد المحاولة.")
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential request failed", e)
            _state.value = AuthState.Error("تعذر تسجيل الدخول بحساب Google. حاول مرة أخرى.")
        } catch (e: GoogleIdTokenParsingException) {
            Log.e(TAG, "Invalid Google ID token", e)
            _state.value = AuthState.Error("رد Google غير صالح. حاول مرة أخرى.")
        } catch (e: Exception) {
            Log.e(TAG, "Sign-in failed", e)
            _state.value = AuthState.Error("تعذر تسجيل الدخول. تأكد من الاتصال بالإنترنت.")
        }
    }

    suspend fun signOut(context: Context) {
        auth(context)?.signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear credential state", e)
        }
        _state.value = AuthState.SignedOut
    }

    fun clearError() {
        if (_state.value is AuthState.Error) _state.value = AuthState.SignedOut
    }

    private fun FirebaseUser.toAuthUser() = AuthUser(
        displayName = displayName?.takeIf { it.isNotBlank() } ?: email.orEmpty().substringBefore('@'),
        email = email.orEmpty(),
        photoUrl = photoUrl?.toString()
    )

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
