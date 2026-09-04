package com.jarvis.assistant

import android.content.Context
import java.io.File

/**
 * Portage Jarvis2/ai/gguf/LocalModelCatalog.kt (fusion Phase 4g). Catalogue
 * des modeles GGUF optionnels que l'utilisateur peut choisir dans Reglages
 * pour [SelectableLlmEngine]. Chaque entree verifiee non-gated (aucun
 * compte/jeton Hugging Face requis).
 */
enum class LocalGgufModel(
    val id: String,
    val displayName: String,
    val repo: String,
    val filename: String,
    val sizeBytes: Long,
    val license: String,
    val description: String,
) {
    QWEN_2_5_1_5B(
        id = "qwen2.5-1.5b",
        displayName = "Qwen 2.5 1.5B Instruct",
        repo = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
        filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 1_117_320_736L,
        license = "Apache-2.0",
        description = "~1.1 Go -- rapide, bon compromis vitesse/qualité.",
    ),
    PHI_3_5_MINI(
        id = "phi-3.5-mini",
        displayName = "Phi-3.5 mini Instruct",
        repo = "bartowski/Phi-3.5-mini-instruct-GGUF",
        filename = "Phi-3.5-mini-instruct-Q4_K_M.gguf",
        sizeBytes = 2_393_232_672L,
        license = "MIT",
        description = "~2.4 Go -- plus capable, pour les téléphones avec plus de RAM.",
    ),
    DOLPHIN_3_QWEN_2_5_1_5B(
        id = "dolphin3-qwen2.5-1.5b",
        displayName = "Dolphin 3.0 (Qwen2.5 1.5B)",
        repo = "bartowski/Dolphin3.0-Qwen2.5-1.5B-GGUF",
        filename = "Dolphin3.0-Qwen2.5-1.5B-Q4_K_M.gguf",
        sizeBytes = 986_051_648L,
        license = "Apache-2.0",
        description = "~1 Go -- variante finetunee, conversationnelle.",
    ),
    /**
     * Bonsai 27B (PrismML) : derive quantifie en 1-bit natif de Qwen3.6-27B --
     * premier modele "classe 27B" tenant sur un telephone (~3.8 Go). Bien plus
     * gros/lent que les trois modeles au-dessus.
     */
    BONSAI_27B(
        id = "bonsai-27b",
        displayName = "Bonsai 27B (1-bit, PrismML)",
        repo = "prism-ml/Bonsai-27B-gguf",
        filename = "Bonsai-27B-Q1_0.gguf",
        sizeBytes = 3_803_452_480L,
        license = "Apache-2.0",
        description = "~3.8 Go -- le plus capable, lent à charger/inférer, réservé aux téléphones puissants.",
    ),
    GEMMA_4_E4B(
        id = "gemma4-e4b",
        displayName = "Gemma 4 E4B",
        repo = "bartowski/google_gemma-4-E4B-it-GGUF",
        filename = "google_gemma-4-E4B-it-Q4_K_M.gguf",
        sizeBytes = 5_405_168_384L,
        license = "Apache-2.0",
        description = "~5.4 Go -- Google, 128K tokens de contexte, très gros.",
    ),
    LFM_2_5_VL_1_6B(
        id = "lfm2.5-vl-1.6b",
        displayName = "LFM2.5-VL 1.6B (Liquid AI, rapide)",
        repo = "LiquidAI/LFM2.5-VL-1.6B-GGUF",
        filename = "LFM2.5-VL-1.6B-Q4_K_M.gguf",
        sizeBytes = 730_896_256L,
        license = "LFM Open v1.0 (libre, <10M$/an)",
        description = "~730 Mo -- très rapide, pensé pour l'edge/téléphone.",
    );

    val downloadUrl: String get() = "https://huggingface.co/$repo/resolve/main/$filename"

    companion object {
        fun byId(id: String?): LocalGgufModel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Repertoire de stockage des modeles GGUF selectionnables -- DOIT rester
 * synchronise avec SelectableLlmEngine.modelsDir (meme convention).
 */
private fun ggufModelsDir(context: Context): File =
    File(context.getExternalFilesDir(null) ?: context.filesDir, "models/selectable")

fun ggufModelFile(context: Context, model: LocalGgufModel): File = File(ggufModelsDir(context), model.filename)

fun isGgufModelDownloaded(context: Context, model: LocalGgufModel): Boolean = ggufModelFile(context, model).exists()

/** Supprime le fichier modele du stockage (l'appelant doit gerer separement la liberation du moteur si actif -- voir AiEngineManager.refresh). */
fun deleteGgufModel(context: Context, model: LocalGgufModel): Boolean {
    val file = ggufModelFile(context, model)
    return if (file.exists()) file.delete() else true
}

/** Telecharge un modele du catalogue -- utilise par l'ecran Reglages pour un telechargement explicite avec barre de progression, hors du cycle prepare() normal. */
suspend fun downloadGgufModel(context: Context, model: LocalGgufModel, onProgress: (downloaded: Long, total: Long) -> Unit) {
    LocalAiModelDownloader.downloadIfMissing(
        url = model.downloadUrl,
        destFile = ggufModelFile(context, model),
        expectedSizeBytes = model.sizeBytes,
        onProgress = onProgress,
    ).getOrThrow()
}
