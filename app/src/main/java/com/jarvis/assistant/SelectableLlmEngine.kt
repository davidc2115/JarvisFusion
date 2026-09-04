package com.jarvis.assistant

import android.content.Context
import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Portage Jarvis2/ai/gguf/SelectableLlmEngine.kt (fusion Phase 4g) --
 * moteur local optionnel, choisi par l'utilisateur dans Reglages parmi
 * [LocalGgufModel] (Qwen/Phi/Dolphin/Bonsai/Gemma4/LFM). Remplace l'ancien
 * LocalLlmController (LiteRT-LM/Qwen only) : meme idee (registre de
 * modeles, telechargement direct depuis Hugging Face non gated) mais moteur
 * natif llama.cpp (Llamatik) au lieu de LiteRT-LM, et catalogue bien plus
 * large (jusqu'a Bonsai 27B).
 *
 * Adapte pour Newjarvis : utilise directement [Prefs] (SharedPreferences,
 * meme cle KEY_LOCAL_LLM_MODEL_ID que l'ancien LocalLlmController -- aucune
 * migration necessaire) au lieu du SettingsDataStore/DataStore de Jarvis2.
 *
 * Tant qu'aucun modele n'est selectionne, [prepare] echoue immediatement
 * sans la moindre requete reseau -- c'est ce qui permet a AiEngineManager
 * de passer directement a SmolVlmEngine sans latence quand l'utilisateur
 * n'a rien choisi ici.
 *
 * Partage le meme pont natif Llamatik/llama.cpp que SmolVlmEngine (un seul
 * contexte natif global cote SDK) : seul un des deux peut avoir un modele
 * reellement charge a un instant donne.
 */
class SelectableLlmEngine(
    private val context: Context,
    /** Rappelee a chaque changement d'etat significatif (debut/avancement/fin de telechargement). */
    private val onStatusChanged: (EngineInfo) -> Unit = {},
) : LocalAiEngine {

    private var ready = false
    private var lastError: String? = null
    private var downloadStatus: String? = null
    private var loadedModel: LocalGgufModel? = null

    override suspend fun prepare(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val selectedId = Prefs.getLocalLlmModelId(context).takeIf { it.isNotBlank() }
            val model = LocalGgufModel.byId(selectedId)
                ?: run {
                    ready = false
                    lastError = null
                    downloadStatus = null
                    throw NoOptionalModelSelected()
                }

            if (ready && loadedModel == model) return@runCatching

            val modelFile = ggufModelFile(context, model)
            loadedModel = null
            downloadStatus = "Téléchargement de ${model.displayName} : 0 / ${model.sizeBytes / 1_000_000} Mo (0 %)…"
            onStatusChanged(info())
            var lastReportedPercent = -1
            LocalAiModelDownloader.downloadIfMissing(
                url = model.downloadUrl,
                destFile = modelFile,
                expectedSizeBytes = model.sizeBytes,
                onProgress = { done, total ->
                    val totalKnown = total.takeIf { it > 0 } ?: model.sizeBytes
                    val percent = if (totalKnown > 0) ((done * 100) / totalKnown).toInt() else -1
                    if (percent != lastReportedPercent) {
                        lastReportedPercent = percent
                        downloadStatus = "Téléchargement de ${model.displayName} : ${done / 1_000_000} / ${totalKnown / 1_000_000} Mo" +
                            (if (percent >= 0) " ($percent %)" else "") + "…"
                        onStatusChanged(info())
                    }
                },
            ).getOrThrow()
            downloadStatus = "Chargement de ${model.displayName} en mémoire…"
            onStatusChanged(info())

            LlamaBridge.updateGenerateParams(
                temperature = 0.6f,
                maxTokens = 400,
                topP = 0.9f,
                topK = 40,
                repeatPenalty = 1.3f,
                contextLength = 4096,
                numThreads = recommendedInferenceThreads(),
                useMmap = true,
                flashAttention = true,
                batchSize = 512,
                gpuLayers = 0,
            )

            val loaded = LlamaBridge.initGenerateModel(modelFile.absolutePath)
            if (!loaded) {
                throw IllegalStateException("LlamaBridge.initGenerateModel a échoué pour ${modelFile.absolutePath}")
            }
            ready = true
            loadedModel = model
            downloadStatus = null
            onStatusChanged(info())
        }.onFailure {
            if (it !is NoOptionalModelSelected) {
                lastError = it.message
                downloadStatus = null
                onStatusChanged(info())
            }
            ready = false
        }
    }

    override fun info() = EngineInfo(
        id = "selectable-gguf",
        displayName = loadedModel?.displayName ?: "Modèle local optionnel",
        isFullyLocal = true,
        isReady = ready,
        notes = downloadStatus
            ?: lastError
            ?: if (ready) "Aucun compte ni jeton requis (${loadedModel?.license})." else "Aucun modèle optionnel sélectionné (voir Réglages).",
    )

    private fun renderPrompt(prompt: String, history: List<Turn>, systemPrompt: String): String {
        val messages = buildList {
            add("system" to systemPrompt.trim())
            history.takeLast(8).forEach { turn ->
                val role = when (turn.role) {
                    Turn.Role.USER -> "user"
                    Turn.Role.ASSISTANT -> "assistant"
                    Turn.Role.SYSTEM -> "system"
                }
                add(role to turn.text)
            }
            add("user" to prompt)
        }
        return LlamaBridge.applyChatTemplate(messages, addAssistantPrefix = true)
            ?: buildString {
                appendLine(systemPrompt.trim())
                history.takeLast(8).forEach { appendLine("${it.role}: ${it.text}") }
                appendLine("USER: $prompt")
                append("ASSISTANT:")
            }
    }

    override suspend fun generate(prompt: String, history: List<Turn>, systemPrompt: String): Result<String> =
        withContext(Dispatchers.Default) {
            if (!ready) return@withContext Result.failure(IllegalStateException("Modèle local optionnel non prêt"))
            runCatching { LlamaBridge.generate(renderPrompt(prompt, history, systemPrompt)) }
        }

    override fun generateStreaming(prompt: String, history: List<Turn>, systemPrompt: String): Flow<String> =
        callbackFlow {
            if (!ready) {
                close(IllegalStateException("Modèle local optionnel non prêt"))
                return@callbackFlow
            }
            val built = StringBuilder()
            LlamaBridge.generateStream(
                renderPrompt(prompt, history, systemPrompt),
                object : GenStream {
                    override fun onDelta(text: String) {
                        built.append(text)
                        trySend(built.toString())
                    }
                    override fun onComplete() { close() }
                    override fun onError(message: String) { close(IllegalStateException(message)) }
                },
            )
            awaitClose { /* pas d'annulation cote natif exposee pour ce chemin */ }
        }

    override fun release() {
        if (ready) {
            LlamaBridge.shutdown()
            ready = false
        }
        loadedModel = null
    }

    /** Signal interne "rien a faire" -- pas une vraie erreur, ne doit pas remplir [lastError]. */
    private class NoOptionalModelSelected : Exception()
}
