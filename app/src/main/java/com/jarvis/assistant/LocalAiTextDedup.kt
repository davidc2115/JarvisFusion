package com.jarvis.assistant

/**
 * Portage Jarvis2/ai/TextDedup.kt (fusion Phase 4g). Filet de securite
 * anti-repetition, applique en un seul endroit (AiEngineManager) donc
 * valable pour tous les moteurs locaux sans dupliquer la logique.
 *
 * Contexte : les petits modeles locaux embarques (SmolVLM2 500M en
 * particulier) peuvent, faute de detecter correctement le token de fin de
 * generation, se mettre a repeter la meme phrase ou le meme paragraphe en
 * boucle jusqu'a la limite de tokens au lieu de s'arreter proprement.
 */
fun deduplicateRepeatedSentences(text: String): String {
    if (text.isBlank()) return text
    val bySentence = collapseRepeatedSentences(text)
    return collapseRepeatedWholeText(bySentence)
}

/**
 * Decoupe le texte en phrases/lignes et retire toute phrase (normalisee :
 * espaces, casse et ponctuation finale ignorees) deja vue parmi les
 * quelques phrases precedentes conservees.
 */
private fun collapseRepeatedSentences(text: String): String {
    val segments = Regex("(?<=[.!?\\n])\\s+").split(text).filter { it.isNotBlank() }
    if (segments.size <= 1) return text

    fun normalize(s: String) = s.trim().lowercase().trimEnd('.', '!', '?', ' ')

    val kept = mutableListOf<String>()
    val recent = ArrayDeque<String>()
    val window = 3 // suffisant pour detecter une boucle courte sans casser des reformulations legitimes

    for (seg in segments) {
        val norm = normalize(seg)
        if (norm.isNotEmpty() && recent.contains(norm)) continue
        kept.add(seg.trim())
        if (norm.isNotEmpty()) {
            recent.addLast(norm)
            if (recent.size > window) recent.removeFirst()
        }
    }

    return kept.joinToString(" ").replace(Regex("[ \t]{2,}"), " ").trim()
}

/**
 * Cas degenere sans ponctuation de phrase exploitable : le texte entier
 * n'est que la meme sous-chaine repetee bout a bout. Si le texte complet se
 * decompose en >= 2 repetitions exactes d'une meme unite, on ne garde que
 * la premiere.
 */
private fun collapseRepeatedWholeText(text: String): String {
    val trimmed = text.trim()
    if (trimmed.length < 20) return text

    var period = 8
    while (period <= trimmed.length / 2) {
        if (trimmed.length % period == 0) {
            val unit = trimmed.substring(0, period)
            val repeats = trimmed.length / period
            val allMatch = (1 until repeats).all { i -> trimmed.regionMatches(i * period, unit, 0, period) }
            if (allMatch && repeats >= 2) return unit.trim()
        }
        period++
    }
    return text
}
