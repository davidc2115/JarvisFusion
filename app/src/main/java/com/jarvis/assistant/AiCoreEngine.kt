package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Portage Jarvis2/ai/aicore/AiCoreEngine.kt (fusion Phase 4g, "RETIRE TOUT
 * IA LOCAL DE NEWJARVIS, POUR GREFFER SIMPLEMENT CELLE DE JARVIS2") --
 * remplace l'ancien GeminiNanoController (ML Kit GenAI Prompt API) par le
 * moteur AICore de Jarvis2, premier maillon de la chaine [AiEngineManager].
 *
 * Preferred engine on devices that ship Google's AICore system service
 * with Gemini Nano (Pixel 8+, and recent Xiaomi/POCO flagships). Where
 * available it's faster and lighter than the bundled llama.cpp-based
 * fallbacks because the model is shared at the OS level instead of
 * packaged per-app.
 *
 * IMPORTANT -- le SDK client (groupe `com.google.ai.edge.aicore`) est
 * encore etiquete experimental par Google. [AiEngineManager] traite un
 * echec d'initialisation de ce moteur comme "indisponible" et bascule
 * automatiquement sur [SelectableLlmEngine] puis [SmolVlmEngine].
 */
class AiCoreEngine(private val context: Context) : LocalAiEngine {

    private companion object {
        /** Au-dela de ce delai, on considere AICore indisponible et on passe au moteur suivant. */
        const val AICORE_PROBE_TIMEOUT_MS = 4_000L
    }

    private var ready = false
    private var lastError: String? = null
    private var session: AiCoreSession? = null

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // BUG RÉEL CORRIGÉ (signalement utilisateur : "l'accès au smartphone en local est
            // extrêmement long, alors que sur Jarvis2 ça répondait presque instantanément") --
            // sur un appareil SANS AICore (l'immense majorité, ce module reste réservé à une
            // poignée de flagships), le vrai appel generateContent() ci-dessous pouvait rester
            // bloqué plusieurs secondes (attente d'une connexion à un service système absent)
            // avant d'échouer, et ce à CHAQUE fois que le process de l'app redémarrait (le
            // cache `current`/`session` d'AiEngineManager ne survit pas à un kill du process
            // par Android) -- donc potentiellement à chaque interaction vocale, pas une seule
            // fois. Borné maintenant dans le temps : au-delà de ce délai, on abandonne cette
            // tentative et on passe directement au moteur suivant de la chaîne (GGUF optionnel
            // puis SmolVLM2), exactement comme si AICore avait échoué proprement.
            val ok = withTimeoutOrNull(AICORE_PROBE_TIMEOUT_MS) {
                val s = AiCoreSessionFactory.tryCreate(context) ?: return@withTimeoutOrNull false
                // Test d'inference minimal, une seule fois au demarrage : la simple
                // construction du client reussit meme sur des appareils qui n'ont
                // pas reellement le module Gemini Nano installe -- sans ce test,
                // AiEngineManager selectionnerait AICore comme actif alors qu'il
                // echouerait a chaque vrai message.
                s.generate("", emptyList(), "Bonjour")
                session = s
                true
            }
            if (ok != true) {
                throw IllegalStateException(
                    if (ok == null) "AICore n'a pas répondu dans le délai imparti (probablement absent de cet appareil)."
                    else "AICore indisponible sur cet appareil (device non supporté ou module non installé)."
                )
            }
            ready = true
        }.onFailure {
            lastError = it.message
            session?.close()
            session = null
            ready = false
        }
    }

    override fun info() = EngineInfo(
        id = "aicore-gemini-nano",
        displayName = "Gemini Nano (AICore, sur puce)",
        isFullyLocal = true,
        isReady = ready,
        notes = lastError ?: "Modèle système partagé, aucun téléchargement à gérer par l'app.",
    )

    override suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String> =
        withContext(Dispatchers.Default) {
            val s = session ?: return@withContext Result.failure(IllegalStateException("AICore non initialisé"))
            runCatching { s.generate(systemPrompt, history, prompt) }
        }

    override fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): Flow<String> = flow {
        val s = session ?: throw IllegalStateException("AICore non initialisé")
        emit(s.generate(systemPrompt, history, prompt))
    }

    override fun release() {
        session?.close()
        session = null
        ready = false
    }
}

/** Thin seam around the actual AICore SDK types so the rest of the file stays readable. */
internal interface AiCoreSession {
    suspend fun generate(systemPrompt: String, history: List<Turn>, prompt: String): String
    fun close()
}

internal object AiCoreSessionFactory {
    /**
     * Returns null (never throws) when AICore/Gemini Nano is not available
     * on this device or Android version, so [AiCoreEngine.prepare] can
     * cleanly report "unavailable" and let [AiEngineManager] fall back to
     * the bundled model instead of crashing the app.
     */
    fun tryCreate(context: Context): AiCoreSession? {
        // Verification synchrone, sans la moindre I/O : le SDK AICore lui-meme declare
        // minSdk 31 (voir <uses-sdk tools:overrideLibrary> du manifest) -- en dessous, la
        // construction echouerait de toute facon, mais potentiellement apres un delai
        // reseau/binder au lieu d'un echec instantane. Ecarte donc la tres grande majorite
        // des appareils encore sous cette version SANS jamais toucher au SDK.
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        return try {
            RealAiCoreSession(context)
        } catch (t: Throwable) {
            null
        }
    }
}

/** Real bridge to `com.google.ai.edge.aicore.GenerativeModel`. */
internal class RealAiCoreSession(appContext: Context) : AiCoreSession {

    private val generationConfig = com.google.ai.edge.aicore.GenerationConfig.Builder().apply {
        context = appContext
        temperature = 0.3f
        topK = 16
        maxOutputTokens = 768
    }.build()

    private val model = com.google.ai.edge.aicore.GenerativeModel(generationConfig)

    override suspend fun generate(systemPrompt: String, history: List<Turn>, prompt: String): String {
        val transcript = buildString {
            appendLine(systemPrompt.trim())
            history.takeLast(8).forEach { appendLine("${it.role}: ${it.text}") }
            append(prompt)
        }
        val response = model.generateContent(transcript)
        return response.text ?: ""
    }

    override fun close() {
        // GenerativeModel currently has no explicit close(); reserved for
        // when the SDK adds session/resource lifecycle management.
    }
}
