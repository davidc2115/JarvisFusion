package com.jarvis.assistant

/**
 * Portage Jarvis2/ai/DeviceCapabilities.kt (fusion Phase 4g). Nombre de
 * threads CPU a utiliser pour l'inference locale (llama.cpp, via
 * Llamatik/LlamaBridge.updateGenerateParams). GPU volontairement PAS
 * utilise (gpuLayers reste a 0 partout ou ce nombre est consomme) : le
 * backend natif precompile fourni par Llamatik 1.10.1 pour Android est
 * CPU-only -- aucun backend GPU (Vulkan/OpenCL) n'y est compile.
 *
 * Garde 2 coeurs libres pour l'UI/le systeme plutot que de saturer tous les
 * coeurs disponibles. Borne a [2, 6].
 */
fun recommendedInferenceThreads(): Int {
    val cores = Runtime.getRuntime().availableProcessors()
    return (cores - 2).coerceIn(2, 6)
}
