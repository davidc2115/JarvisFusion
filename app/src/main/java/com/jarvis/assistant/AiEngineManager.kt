package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Portage Jarvis2/ai/AiEngineManager.kt (fusion Phase 4g, "RETIRE TOUT IA
 * LOCAL DE NEWJARVIS, POUR GREFFER SIMPLEMENT CELLE DE JARVIS2") -- choisit
 * le meilleur [LocalAiEngine] disponible a l'execution et expose une API
 * unique et stable au reste de l'app. Remplace entierement l'ancien duo
 * GeminiNanoController + LocalLlmController. Chaine de secours ordonnee, du
 * plus au moins prefere :
 *
 *  1. [AiCoreEngine] (Gemini Nano / AICore) -- natif, le plus rapide, aucun
 *     telechargement de modele par l'app, mais present seulement sur une
 *     poignee d'appareils haut de gamme recents. Essaye en premier car son
 *     cout de sondage est quasi nul.
 *  2. [SelectableLlmEngine] (Qwen 2.5 1.5B / Phi-3.5 mini / Dolphin 3.0 /
 *     Bonsai 27B / Gemma 4 E4B / LFM2.5-VL, llama.cpp) -- optionnel, choisi
 *     explicitement par l'utilisateur dans Reglages (voir LocalModelCatalog.kt).
 *     Place AVANT SmolVLM2 dans la chaine expres : si l'utilisateur a pris
 *     la peine de choisir un de ces modeles, il s'attend a ce que Jarvis lui
 *     parle vraiment, pas a continuer silencieusement avec le defaut. Quand
 *     rien n'est selectionne, prepare() echoue instantanement sans le
 *     moindre appel reseau, donc cette etape ne coute rien dans le cas
 *     courant. Aucun de ces modeles ne necessite de compte ni de jeton API.
 *  3. [SmolVlmEngine] (SmolVLM2, llama.cpp) -- le defaut garanti : fonctionne
 *     sur n'importe quel telephone ARM64, se telecharge tout seul au premier
 *     usage depuis un depot Hugging Face non verrouille (aucun compte, aucun
 *     clic de licence), et est nativement multimodal (texte + image).
 *
 * [ensureReady] parcourt la chaine une fois et se fixe sur le premier moteur
 * dont [LocalAiEngine.prepare] reussit. [generate] s'auto-repare en plus au
 * moment de la requete : si le moteur actuellement selectionne a menti lors
 * de prepare() (succes signale mais generation qui echoue quand meme -- cela
 * arrive reellement avec le SDK experimental d'AICore), il passe
 * silencieusement au moteur suivant de la chaine et reessaie la meme
 * requete au lieu de remonter l'erreur brute du SDK a l'utilisateur.
 *
 * IMPORTANT -- le choix explicite de l'utilisateur prime sur l'ordre de
 * chaine ci-dessus : si Reglages a `Prefs.getPreferredEngineId` different de
 * "auto", [ensureReady] essaie ce moteur exact EN PREMIER, avant de
 * retomber sur l'ordre normal de la chaine s'il echoue. Sans ce
 * contournement, un utilisateur ayant explicitement choisi Qwen/Phi/Dolphin/
 * Bonsai/etc. dans Reglages le verrait silencieusement ignore des que AICore
 * fonctionne deja sur son appareil (AICore gagne toujours l'ordre de chaine
 * fixe ci-dessus) -- aucun telechargement ne demarrerait jamais pour son
 * choix reel. C'etait un vrai bug cote Jarvis2, pas une hypothese.
 *
 * Adapte pour Newjarvis : [ApiClient] est un `object` sans injection de
 * dependances (contrairement au ViewModel Koin-injecte de Jarvis2), donc
 * cette classe expose un singleton paresseux via [Companion.getInstance]
 * au lieu d'etre construite directement par un framework DI. Utilise
 * [Prefs] (SharedPreferences) au lieu de SettingsDataStore.
 */
class AiEngineManager private constructor(private val context: Context) {

    private val aiCore = AiCoreEngine(context)

    // onStatusChanged relaie chaque mise a jour de progression de
    // telechargement (voir SelectableLlmEngine/SmolVlmEngine) directement
    // dans _activeEngine, pour qu'une UI qui observerait ce StateFlow
    // affiche une progression reelle en Mo/pourcentage au lieu d'un simple
    // spinner sans texte pendant tout le telechargement.
    private val selectable by lazy {
        SelectableLlmEngine(context, onStatusChanged = { _activeEngine.value = it })
    }
    private val smolVlm by lazy {
        SmolVlmEngine(context, onStatusChanged = { _activeEngine.value = it })
    }

    /** Ordonnee du plus au moins prefere ; voir doc de classe. */
    private val engineChain: List<LocalAiEngine> by lazy { listOf(aiCore, selectable, smolVlm) }

    private val _activeEngine = MutableStateFlow<EngineInfo?>(null)
    val activeEngine: StateFlow<EngineInfo?> = _activeEngine.asStateFlow()

    private var current: LocalAiEngine? = null

    // Verrou UNIQUE protegeant toute operation touchant le contexte natif
    // llama.cpp partage (voir doc de classe SelectableLlmEngine : "un seul
    // contexte natif global cote SDK", partage entre SelectableLlmEngine et
    // SmolVlmEngine). Sans ce verrou, ensureReady()/generate()/refresh()
    // pourraient s'executer en parallele sur des coroutines independantes,
    // et refresh() appelant engine.release() -> LlamaBridge.shutdown() sur
    // le MEME contexte natif qu'un generate() en cours sur une autre
    // coroutine provoquerait un crash natif (use-after-free JNI, non
    // rattrapable par un try/catch Kotlin). Toutes les methodes publiques
    // ci-dessous prennent ce meme verrou ; le corps reel vit dans des
    // variantes *Locked() privees pour eviter tout appel imbrique (Mutex
    // n'est pas reentrant).
    private val engineMutex = Mutex()

    suspend fun ensureReady(): EngineInfo {
        current?.let { return it.info() }
        return engineMutex.withLock { ensureReadyLocked() }
    }

    private suspend fun ensureReadyLocked(): EngineInfo {
        // Un autre appelant a peut-etre deja termine pendant qu'on
        // attendait le verrou -- pas la peine de refaire tout le travail.
        current?.let { return it.info() }

        val orderedChain = preferredFirstChain()
        for (engine in orderedChain) {
            // Le prepare() de SmolVLM2 peut prendre du temps au premier
            // lancement (telechargement du modele) -- on affiche tout de
            // suite un statut interimaire "telechargement" pour qu'une UI
            // qui lirait activeEngine.notes ne reste pas bloquee sur un
            // "Initialisation..." fige pendant tout ce temps.
            _activeEngine.value = engine.info()
            val result = engine.prepare()
            if (result.isSuccess) {
                current = engine
                _activeEngine.value = engine.info()
                return engine.info()
            }
        }

        // Aucun moteur n'a reussi son prepare(): on retombe sur le dernier
        // de la chaine (SmolVLM2, qui reussit quasiment toujours puisqu'il
        // se telecharge lui-meme) pour qu'une UI affiche un message utile
        // plutot que de planter.
        val last = engineChain.last()
        current = last
        _activeEngine.value = last.info()
        return last.info()
    }

    /**
     * [engineChain] reordonnee pour que le choix explicite de l'utilisateur
     * (Prefs.getPreferredEngineId, si different de "auto") soit essaye en
     * premier, le reste de la chaine normale restant en secours au cas ou ce
     * moteur precis n'arrive pas a se preparer (ex : pas de reseau pour
     * telecharger un modele GGUF). Voir la note "IMPORTANT" de la doc de
     * classe pour la raison d'etre.
     */
    private fun preferredFirstChain(): List<LocalAiEngine> {
        val preferredId = Prefs.getPreferredEngineId(context).takeIf { it != "auto" } ?: return engineChain
        val preferred = engineChain.firstOrNull { it.info().id == preferredId } ?: return engineChain
        return listOf(preferred) + engineChain.filter { it !== preferred }
    }

    suspend fun generate(
        prompt: String,
        history: List<Turn>,
        systemPrompt: String = LOCAL_AI_ENGINE_DEFAULT_SYSTEM_PROMPT,
    ): Result<String> = engineMutex.withLock {
        if (current == null) ensureReadyLocked()
        val startIndex = current?.let { engineChain.indexOf(it) }?.coerceAtLeast(0) ?: 0

        var lastResult: Result<String>? = null
        for (i in startIndex until engineChain.size) {
            val engine = engineChain[i]
            if (engine !== current) {
                _activeEngine.value = engine.info()
                val prep = engine.prepare()
                if (prep.isFailure) {
                    lastResult = Result.failure(prep.exceptionOrNull() ?: IllegalStateException("Moteur indisponible"))
                    continue
                }
                current = engine
                _activeEngine.value = engine.info()
            }
            val result = engine.generate(prompt, history, systemPrompt)
            if (result.isSuccess) return@withLock result.mapCatching { deduplicateRepeatedSentences(it) }
            lastResult = result
            // Echec a l'execution malgre prepare() reussi (voir doc de
            // classe) -- on essaie le moteur suivant de la chaine.
        }
        lastResult ?: Result.failure(IllegalStateException("Aucun moteur IA disponible"))
    }

    fun generateStreaming(
        prompt: String,
        history: List<Turn>,
        systemPrompt: String = LOCAL_AI_ENGINE_DEFAULT_SYSTEM_PROMPT,
    ): Flow<String> {
        val engine = current ?: aiCore
        return engine.generateStreaming(prompt, history, systemPrompt).map { deduplicateRepeatedSentences(it) }
    }

    /**
     * Force une re-verification, p.ex. apres que l'utilisateur importe/
     * telecharge un modele local dans Reglages, OU change le modele GGUF
     * selectionne (SelectableLlmEngine.prepare() ne recharge que si
     * loadedModel differe -- voir sa doc). Prend [engineMutex] comme toute
     * autre operation touchant le contexte natif partage, pour ne JAMAIS
     * liberer/recharger le modele pendant qu'un generate() est en cours
     * dessus.
     */
    suspend fun refresh(): EngineInfo = engineMutex.withLock {
        current?.release()
        current = null
        ensureReadyLocked()
    }

    fun release() {
        aiCore.release()
        selectable.release()
        smolVlm.release()
        current = null
    }

    companion object {
        @Volatile private var INSTANCE: AiEngineManager? = null

        /** Singleton paresseux, cree au premier appel avec le Context applicatif. */
        fun getInstance(context: Context): AiEngineManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiEngineManager(context.applicationContext).also { INSTANCE = it }
            }
    }
}
