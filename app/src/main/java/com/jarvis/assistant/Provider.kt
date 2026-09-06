package com.jarvis.assistant

/**
 * Liste des fournisseurs IA disponibles.
 * isLocal = true signifie : aucun réseau, modèle exécuté directement sur le téléphone.
 * isAuto = true signifie : essaie plusieurs fournisseurs configurés jusqu'à ce que l'un réponde.
 * needsApiKey = false signifie : pas de clé API requise (Custom sans auth).
 */
enum class Provider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isLocal: Boolean = false,
    val isAuto: Boolean = false,
    val needsApiKey: Boolean = true
) {
    AUTO_BEST(
        "🤖 Automatique (essaie tes IA configurées)",
        "",
        "",
        isAuto = true,
        needsApiKey = false
    ),

    // ── Fournisseurs Cloud ────────────────────────────────────────────────────
    // llama-3.3-70b-versatile déprécié par Groq le 16/08/2026 (avec llama-3.1-8b-instant) —
    // requêtes en erreur "model does not exist" depuis cette date, confirmé par l'utilisateur.
    // Remplacement officiellement recommandé par Groq (console.groq.com/docs/deprecations) :
    // openai/gpt-oss-120b (l'autre option suggérée, qwen/qwen3.6-27b, est plus petite/rapide
    // mais moins capable — gpt-oss-120b reste le choix par défaut le plus proche en capacité
    // de l'ancien 70B, y compris pour le tool use dont JARVIS dépend pour JARVIS_CMD).
    GROQ(
        "Groq (gratuit, très rapide)",
        "https://api.groq.com/openai/v1/chat/completions",
        "openai/gpt-oss-120b"
    ),
    OPENAI(
        "ChatGPT (OpenAI)",
        "https://api.openai.com/v1/chat/completions",
        "gpt-4o-mini"
    ),
    CLAUDE(
        "Claude (Anthropic)",
        "https://api.anthropic.com/v1/messages",
        "claude-sonnet-4-5"
    ),
    // gemini-3.1-pro-preview (essayé d'abord) renvoyait systématiquement HTTP 429 sur TOUTES
    // les clés de l'utilisateur (confirmé en usage réel) : les modèles "Preview" ont des quotas
    // gratuits nettement plus restrictifs que les modèles "Stable", indépendamment d'un
    // abonnement Gemini (l'abonnement consommateur gemini.google.com/l'app et les quotas de
    // clé API AI Studio sont deux systèmes distincts). gemini-3.7-flash est le modèle STABLE
    // le plus récent et le plus capable de la gamme (coding/agentique/multi-étapes), avec un
    // quota gratuit bien plus généreux qu'un modèle Preview — priorité à la fiabilité réelle
    // plutôt qu'au label "Pro" d'un modèle Preview qui échoue en pratique.
    GEMINI(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent",
        "gemini-3.7-flash"
    ),
    MISTRAL(
        "Mistral AI",
        "https://api.mistral.ai/v1/chat/completions",
        "mistral-large-latest"
    ),
    DEEPSEEK(
        "DeepSeek",
        "https://api.deepseek.com/v1/chat/completions",
        "deepseek-chat"
    ),
    PERPLEXITY(
        "Perplexity AI",
        "https://api.perplexity.ai/chat/completions",
        "sonar"
    ),
    TOGETHER(
        "Together AI",
        "https://api.together.xyz/v1/chat/completions",
        "mistralai/Mixtral-8x7B-Instruct-v0.1"
    ),
    OPENROUTER(
        "OpenRouter (multi-modèles)",
        "https://openrouter.ai/api/v1/chat/completions",
        "openai/gpt-4o-mini"
    ),
    SERPAPI(
        "SerpAPI (Recherche Web)",
        "https://serpapi.com/search",
        "",
        needsApiKey = true
    ),
    // Pollinations RETIRÉ (signalement utilisateur répété : "Toutes les IA configurées ont
    // échoué" persistait à cause de lui) -- ce filet de secours anonyme et gratuit limitait les
    // requêtes à 1/15s (voir doc officielle), et l'architecture à 2 appels par question
    // (réponse + reformulation) le faisait quasi systématiquement échouer lui-même dès qu'il
    // était atteint comme dernier recours, produisant l'échec total au pire moment (plus aucun
    // candidat après lui dans AUTO_FALLBACK_ORDER). Un espacement proactif avait été tenté sans
    // suffire à satisfaire l'utilisateur -- retiré complètement sur sa demande explicite plutôt
    // que de continuer à rafistoler un service tiers non fiable. Le mode Automatique s'arrête
    // désormais honnêtement sur "Aucune IA configurée"/"Toutes les IA ont échoué" quand aucun
    // fournisseur à clé configurée ne répond, plutôt que de dépendre d'un filet cassé.

    CUSTOM(
        "Autre / URL personnalisée",
        "",
        "",
        needsApiKey = false
    ),

    // ── Modèle embarqué sur le téléphone (hors-ligne) ─────────────────────────
    // Fusion Phase 4g ("RETIRE TOUT IA LOCAL DE NEWJARVIS, POUR GREFFER SIMPLEMENT CELLE DE
    // JARVIS2") : remplace les deux anciens backends GeminiNanoController (AICore direct via
    // ML Kit GenAI) et LocalLlmController (LiteRT-LM/Qwen uniquement, tâches #247/#248) par
    // UN SEUL provider représentant toute la chaîne de secours AiEngineManager de Jarvis2
    // (AICore -> modèle GGUF optionnel choisi par l'utilisateur -> SmolVLM2 garanti) --
    // AiEngineManager choisit lui-même en interne le meilleur moteur réellement disponible,
    // l'utilisateur n'a plus qu'un seul choix "IA locale" à faire ici, le détail (quel modèle
    // GGUF, si AICore est dispo...) se règle dans l'onglet Local des Réglages.
    LOCAL_JARVIS(
        "IA locale (Gemini Nano / modèle embarqué)",
        "",
        "",
        isLocal = true,
        needsApiKey = false
    );

    /** Fournisseurs cloud éligibles au mode Automatique, par ordre de préférence. */
    companion object {
        val AUTO_FALLBACK_ORDER = listOf(
            GROQ, GEMINI, CLAUDE, OPENAI, MISTRAL, DEEPSEEK, PERPLEXITY, TOGETHER, OPENROUTER
        )

        /** Tous les providers cloud qui acceptent une clé API individuelle. */
        val CLOUD_KEY_PROVIDERS = listOf(
            GROQ, OPENAI, CLAUDE, GEMINI, MISTRAL,
            DEEPSEEK, PERPLEXITY, TOGETHER, OPENROUTER, SERPAPI
        )

        fun fromName(name: String): Provider =
            entries.find { it.name == name } ?: GROQ
    }
}
