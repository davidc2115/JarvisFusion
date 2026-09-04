package com.jarvis.assistant

/**
 * Portage direct de Jarvis2/ai/LocalAiEngine.kt (fusion Phase 4g,
 * "RETIRE TOUT IA LOCAL DE NEWJARVIS, POUR GREFFER SIMPLEMENT CELLE DE
 * JARVIS2") -- contrat commun a tout moteur de generation on-device.
 */

/** One exchange in the running conversation, used as context for the model. */
data class Turn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/** Capability flags an engine can report so the UI/router can adapt. */
data class EngineInfo(
    val id: String,
    val displayName: String,
    val isFullyLocal: Boolean,
    val isReady: Boolean,
    val notes: String = "",
)

/**
 * Common contract for any on-device generation backend used by
 * [AiEngineManager]. Every implementation must run entirely on-device --
 * the cloud cascade lives entirely in ApiClient/Provider, separate from
 * this local chain.
 */
interface LocalAiEngine {

    suspend fun prepare(): Result<Unit>

    fun info(): EngineInfo

    /**
     * Generate a full reply to [prompt] given the recent [history]. Returns
     * the complete text -- streaming is exposed separately via
     * [generateStreaming] for engines that support it (both fall back to a
     * single emission when the underlying SDK has no token streaming).
     */
    suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String>

    /** Streaming variant: emits growing partial text, last emission is final. */
    fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): kotlinx.coroutines.flow.Flow<String>

    fun release()
}

/**
 * Prompt systeme par defaut du moteur local -- utilise seulement quand
 * l'appelant n'en fournit pas un explicitement (ApiClient.sendLocal, voir
 * LOCAL_SYSTEM_PROMPT, passe toujours le sien).
 */
const val LOCAL_AI_ENGINE_DEFAULT_SYSTEM_PROMPT = """
Tu es JARVIS, l'assistant vocal/texte 100% local du smartphone de l'utilisateur.
Precis, concis, jamais bavard pour rien. Si tu ne sais vraiment pas repondre a
une question factuelle, dis-le clairement plutot que d'inventer.
"""
