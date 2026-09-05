package com.jarvis.assistant

import android.content.Context

/**
 * Dernier recours 100% local, SANS AUCUN appel réseau ni IA -- signalement utilisateur répété
 * ("toujours aucun système en local pour utiliser seulement le vault ou les fonctions du
 * téléphone : réveil, minuteur, flash, etc.") : même quand la cascade IA cloud est totalement
 * injoignable — panne réseau, aucune clé configurée — certaines actions basiques
 * n'ont techniquement BESOIN d'aucune IA pour être exécutées : elles sont déjà des fonctions
 * directes du téléphone (lampe torche, réveil Android, minuteur) ou une simple recherche dans
 * des fichiers déjà présents sur le disque (vault Obsidian). Reconnaissance par mots-clés/regex
 * volontairement simple et sans ambiguïté : ne remplace jamais l'IA pour une vraie conversation,
 * seulement pour éviter qu'une panne réseau totale bloque même l'allumage d'une lampe torche.
 * Deux points d'appel dans ApiClient : (1) ApiClient.sendChat() quand la réponse de la cascade
 * IA cloud indique un échec total (voir aiTotallyFailed) ; (2) ApiClient.sendLocal(), EN AMONT de
 * tout appel au modèle génératif embarqué (AiEngineManager) -- signalement utilisateur ("IA
 * locale extrêmement longue pour lampe/réveil/minuteur alors qu'avant c'était instantané") :
 * inutile de payer le coût, potentiellement plusieurs secondes (chargement d'un modèle GGUF en
 * mémoire si le process a été tué entre-temps), d'une inférence générative complète juste pour
 * ces commandes déjà 100% déterministes. Dans ce deuxième cas [prefix] est vide (pas
 * d'"IA injoignable" à afficher, l'IA locale n'a simplement pas été sollicitée du tout) ; on ne
 * retombe sur le modèle génératif que si la phrase ne correspond à aucune commande simple ici.
 */
object LocalCommandController {

    /** Tente de reconnaître et d'exécuter directement une commande simple depuis le texte brut
     *  du dernier message utilisateur. Retourne null si rien de sûr n'a été reconnu -- dans ce
     *  cas l'appelant doit afficher le message d'échec IA normal plutôt que de risquer une
     *  mauvaise interprétation sans confirmation possible (pas d'IA disponible pour clarifier). */
    fun tryHandle(context: Context, rawText: String, prefix: String = PREFIX): String? {
        val text = rawText.trim().lowercase()
        if (text.isBlank()) return null

        val raw = flashlightCommand(context, text)
            ?: timerCommand(context, text)
            ?: alarmCommand(context, text)
            ?: vaultSearchCommand(context, text)
            ?: return null
        return prefix + raw
    }

    private const val PREFIX = "🔌 IA injoignable — commande locale hors-ligne reconnue :\n"

    private fun flashlightCommand(context: Context, text: String): String? {
        val mentionsTorch = text.contains("lampe torche") || text.contains("torche") || text.contains("flash")
        if (!mentionsTorch) return null
        val turnsOn = text.contains("allume") || text.contains("active")
        val turnsOff = text.contains("éteins") || text.contains("eteins") ||
            text.contains("désactive") || text.contains("desactive") || text.contains("coupe")
        return when {
            turnsOn && !turnsOff -> DeviceControlController.setFlashlight(context, true)
            turnsOff && !turnsOn -> DeviceControlController.setFlashlight(context, false)
            else -> null
        }
    }

    private val timerKeywordRegex = Regex("minute(u|r)?|minuterie|\\btimer\\b|chrono")
    private val durationRegex = Regex("(\\d{1,3})\\s*(heures?|h|minutes?|min|secondes?|sec)\\b")

    private fun timerCommand(context: Context, text: String): String? {
        if (!timerKeywordRegex.containsMatchIn(text)) return null
        val match = durationRegex.find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val unit = match.groupValues[2]
        val totalSeconds = when {
            unit.startsWith("heure") || unit == "h" -> amount * 3600
            unit.startsWith("min") -> amount * 60
            else -> amount
        }
        if (totalSeconds <= 0) return null
        return DeviceControlController.setTimer(context, totalSeconds, "")
    }

    private val alarmRegex = Regex("(r[ée]veil|alarme).{0,20}?(\\d{1,2})\\s*h\\s*(\\d{0,2})")

    private fun alarmCommand(context: Context, text: String): String? {
        val m = alarmRegex.find(text) ?: return null
        val hour = m.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23) return null
        val minuteRaw = m.groupValues[3]
        val minute = if (minuteRaw.isBlank()) 0 else minuteRaw.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        return DeviceControlController.setAlarm(context, hour, minute, "", emptyList())
    }

    private val vaultSearchTriggers = listOf(
        "cherche dans mes notes", "cherche dans le vault", "cherche dans obsidian",
        "recherche dans mes notes", "recherche dans le vault",
        "qu'est-ce que j'ai noté sur", "qu'est ce que j'ai note sur"
    )

    private fun vaultSearchCommand(context: Context, text: String): String? {
        val trigger = vaultSearchTriggers.firstOrNull { text.contains(it) } ?: return null
        val query = text.substringAfter(trigger).trim().removePrefix(":").trim()
        if (query.isBlank()) return null
        return ObsidianController.searchNotes(context, query)
    }
}
