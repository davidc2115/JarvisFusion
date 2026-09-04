package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

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

    private var ready = false
    private var lastError: String? = null
    private var session: AiCoreSession? = null

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val s = AiCoreSessionFactory.tryCreate(context)
                ?: throw IllegalStateException("AICore indisponible sur cet appareil (device non supporté ou module non installé).")
            // Test d'inference minimal, une seule fois au demarrage : la simple
            // construction du client reussit meme sur des appareils qui n'ont
            // pas reellement le module Gemini Nano installe -- sans ce test,
            // AiEngineManager selectionnerait AICore comme actif alors qu'il
            // echouerait a chaque vrai message.
            s.generate("", emptyList(), "Bonjour")
            session = s
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
