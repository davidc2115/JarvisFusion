package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gestion des préférences JARVIS v3.
 *
 * Nouveautés v3 :
 *  - Multi-clés par provider (JSON array) avec rotation round-robin
 *  - Blacklist temporaire des clés en erreur (429/401)
 *  - Stratégie de rotation configurable par provider
 *  - Comptes email IMAP/SMTP multiples
 *  - Paramètres de contrôle téléphone (permissions accordées)
 */
object Prefs {
    private const val PREFS_NAME = "jarvis_prefs"

    // ─── Clés de stockage ─────────────────────────────────────────────────────
    private const val KEY_PROVIDER          = "provider"
    private const val KEY_BASE_URL          = "base_url"
    private const val KEY_MODEL             = "model"
    private const val KEY_API_KEY           = "api_key"            // rétrocompat
    private const val KEY_LOCAL_MODEL_PATH  = "local_model_path"
    private const val KEY_LOCAL_MODEL_FORMAT= "local_model_format"
    private const val KEY_LOCAL_LLM_MODEL_ID = "local_llm_model_id"
    // Fusion Phase 4g ("RETIRE TOUT IA LOCAL DE NEWJARVIS, POUR GREFFER
    // SIMPLEMENT CELLE DE JARVIS2") : surcharge explicite de l'utilisateur
    // pour l'ordre de la chaine AiEngineManager ("auto" = ordre par defaut
    // AICore -> modele GGUF selectionne -> SmolVLM2). Voir AiEngineManager.kt.
    private const val KEY_PREFERRED_ENGINE_ID = "preferred_local_engine_id"
    private const val KEY_DEFAULT_CALENDAR_ID = "default_calendar_id"
    private const val KEY_ACCENT_COLOR      = "accent_color"
    private const val KEY_HF_TOKEN          = "hf_token"
    private const val KEY_ORB_STYLE         = "orb_style"
    private const val KEY_EMAIL_ACCOUNTS    = "email_accounts"     // JSON array
    private const val KEY_GITHUB_ACCOUNTS   = "github_accounts"    // JSON array
    private const val KEY_LOGS_GIST_ID      = "logs_github_gist_id"
    private const val KEY_LOGS_AUTO_UPLOAD  = "logs_auto_upload_github_enabled"
    private const val KEY_ROTATION_STRATEGY = "rotation_strategy"  // "ROUNDROBIN"|"FALLBACK"|"RANDOM"
    private const val KEY_OBSIDIAN_VAULT_PATH = "obsidian_vault_path"
    // OAuth Google (Agenda/Mail) -- porte depuis l'appli reecrite (avant la restauration de l'ancienne base, voir taches #247-249), a la
    // demande explicite de l'utilisateur de garder l'integration OAuth Google actuelle en
    // plus (pas a la place) du systeme IMAP/SMTP existant de cette base.
    private const val KEY_GOOGLE_WEB_CLIENT_ID = "google_web_client_id"
    private const val KEY_GOOGLE_ACCOUNTS = "google_linked_accounts_json"
    private const val KEY_GOOGLE_ACCESS_TOKEN = "google_oauth_access_token"
    private const val KEY_GOOGLE_ACCESS_TOKEN_EXPIRY = "google_oauth_access_token_expiry_millis"
    private const val KEY_GOOGLE_ACTIVE_ACCOUNT_EMAIL = "google_active_account_email"
    private const val KEY_GOOGLE_ACCOUNT_TOKENS = "google_account_tokens_json"

    const val DEFAULT_ACCENT_COLOR = -1525685 // #FFE8B84B (or — thème Apex Studio)

    // ═════════════════════════════════════════════════════════════════════════
    // MULTI-CLÉS API PAR PROVIDER
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Retourne toutes les clés configurées pour ce provider.
     * Migration douce : si aucune liste n'existe, cherche l'ancienne clé unique.
     */
    fun getApiKeysFor(context: Context, provider: Provider): List<String> {
        val json = prefs(context).getString("api_keys_${provider.name}", null)
        if (json != null) {
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
            } catch (_: Exception) { emptyList() }
        }
        // Migration : ancienne clé unique
        val legacy = prefs(context).getString("api_key_${provider.name}", "")
            ?: prefs(context).getString(KEY_API_KEY, "") ?: ""
        return if (legacy.isNotBlank()) listOf(legacy) else emptyList()
    }

    fun saveApiKeysFor(context: Context, provider: Provider, keys: List<String>) {
        val arr = JSONArray()
        keys.filter { it.isNotBlank() }.forEach { arr.put(it) }
        prefs(context).edit()
            .putString("api_keys_${provider.name}", arr.toString())
            .remove("api_key_${provider.name}") // nettoie l'ancien format
            .apply()
    }

    fun addApiKeyFor(context: Context, provider: Provider, key: String) {
        val current = getApiKeysFor(context, provider).toMutableList()
        if (key.isNotBlank() && !current.contains(key)) {
            current.add(key)
            saveApiKeysFor(context, provider, current)
        }
    }

    fun removeApiKeyFor(context: Context, provider: Provider, key: String) {
        val current = getApiKeysFor(context, provider).toMutableList()
        current.remove(key)
        saveApiKeysFor(context, provider, current)
        // Réinitialise l'index si nécessaire
        val idx = getKeyIndex(context, provider)
        if (idx >= current.size) saveKeyIndex(context, provider, 0)
    }

    /** Rétrocompat : retourne la 1ère clé configurée ou "". */
    fun getApiKeyFor(context: Context, provider: Provider): String =
        getApiKeysFor(context, provider).firstOrNull() ?: ""

    /** Sauvegarde un Map<Provider, String> en découpant les clés multiples par virgule ou retour à la ligne. */
    fun saveApiKeys(context: Context, keys: Map<Provider, String>) {
        for ((provider, keyStr) in keys) {
            val keysList = keyStr.split(",", "\n", ";").map { it.trim() }.filter { it.isNotBlank() }
            saveApiKeysFor(context, provider, keysList)
        }
    }

    /** Sauvegarde un Map<Provider, List<String>> en batch (écran Clés API). */
    fun saveAllApiKeys(context: Context, keysMap: Map<Provider, List<String>>) {
        val editor = prefs(context).edit()
        for ((provider, keys) in keysMap) {
            val arr = JSONArray()
            keys.filter { it.isNotBlank() }.forEach { arr.put(it) }
            editor.putString("api_keys_${provider.name}", arr.toString())
        }
        editor.apply()
    }

    // ─── Rotation round-robin ─────────────────────────────────────────────────

    /** Retourne la prochaine clé valide selon la stratégie configurée. */
    fun getNextApiKey(context: Context, provider: Provider): String {
        val keys = getApiKeysFor(context, provider)
        if (keys.isEmpty()) return ""
        val validKeys = keys.filter { !isKeyBlacklisted(context, provider, it) }
        if (validKeys.isEmpty()) {
            clearBlacklist(context, provider) // toutes blacklistées → reset
            return keys.first()
        }
        return when (getRotationStrategy(context)) {
            RotationStrategy.RANDOM -> validKeys.random()
            RotationStrategy.ROUNDROBIN -> {
                val idx = getKeyIndex(context, provider) % validKeys.size
                saveKeyIndex(context, provider, (idx + 1) % validKeys.size)
                validKeys[idx]
            }
            RotationStrategy.FALLBACK -> validKeys.first() // premier valide
        }
    }

    // Durée de blacklist par défaut pour un vrai échec (401 invalide, erreur inattendue) --
    // inchangée par rapport au comportement historique (1h).
    const val KEY_BLACKLIST_DEFAULT_MS = 60 * 60 * 1000L

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "j'ai 4 clés API Groq, et en seulement 2
    // demandes le quota des 4 clés serait atteint" -- confirmé pré-existant avant la fusion
    // JarvisFusion, pas une régression introduite ici) : un simple 429 (quota TEMPORAIRE,
    // PAS une clé invalide/morte) blacklistait la clé pour 1h ENTIÈRE via markKeyFailed, avec
    // la même durée qu'un vrai 401. Or UN SEUL tour de conversation peut déclencher plusieurs
    // appels IA internes (réponse principale, rebond vault, reformulation de présentation...),
    // et sendOpenAiWithRotation/sendClaudeWithRotation/sendGeminiWithRotation essaient TOUTES
    // les clés configurées en rotation en cas d'échec -- si le quota Groq est partagé au niveau
    // du COMPTE plutôt que par clé individuelle (plausible sur le tier gratuit), une seule
    // rafale de 429 épuise les 4 clés d'un coup, qui restaient ensuite indisponibles pour le
    // reste de l'heure même si le quota réel se libère bien plus vite (souvent en secondes).
    const val KEY_BLACKLIST_RATE_LIMIT_MS = 30 * 1000L

    /** Signale une clé comme défaillante -- [blacklistDurationMs] : voir KEY_BLACKLIST_RATE_LIMIT_MS
     *  (429, quota temporaire) vs KEY_BLACKLIST_DEFAULT_MS (401/autre, échec réel) ci-dessus. */
    fun markKeyFailed(context: Context, provider: Provider, key: String, blacklistDurationMs: Long = KEY_BLACKLIST_DEFAULT_MS) {
        val mapJson = prefs(context).getString("api_keys_failed_${provider.name}", "{}") ?: "{}"
        val map = try { JSONObject(mapJson) } catch (_: Exception) { JSONObject() }
        map.put(key, System.currentTimeMillis() + blacklistDurationMs)
        prefs(context).edit().putString("api_keys_failed_${provider.name}", map.toString()).apply()
    }

    private fun isKeyBlacklisted(context: Context, provider: Provider, key: String): Boolean {
        val mapJson = prefs(context).getString("api_keys_failed_${provider.name}", "{}") ?: "{}"
        return try {
            val map = JSONObject(mapJson)
            if (!map.has(key)) return false
            val expiry = map.getLong(key)
            System.currentTimeMillis() < expiry
        } catch (_: Exception) { false }
    }

    private fun clearBlacklist(context: Context, provider: Provider) {
        prefs(context).edit().remove("api_keys_failed_${provider.name}").apply()
    }

    private fun getKeyIndex(context: Context, provider: Provider): Int =
        prefs(context).getInt("api_key_idx_${provider.name}", 0)

    private fun saveKeyIndex(context: Context, provider: Provider, idx: Int) {
        prefs(context).edit().putInt("api_key_idx_${provider.name}", idx).apply()
    }

    // ─── Stratégie de rotation ────────────────────────────────────────────────

    enum class RotationStrategy { ROUNDROBIN, FALLBACK, RANDOM }

    fun getRotationStrategy(context: Context): RotationStrategy =
        try {
            RotationStrategy.valueOf(
                prefs(context).getString(KEY_ROTATION_STRATEGY, "ROUNDROBIN") ?: "ROUNDROBIN"
            )
        } catch (_: Exception) { RotationStrategy.ROUNDROBIN }

    fun saveRotationStrategy(context: Context, strategy: RotationStrategy) {
        prefs(context).edit().putString(KEY_ROTATION_STRATEGY, strategy.name).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // COMPTES EMAIL IMAP / SMTP
    // ═════════════════════════════════════════════════════════════════════════

    data class EmailAccount(
        val id: String = System.currentTimeMillis().toString(),
        val label: String = "",          // ex: "Gmail perso"
        val email: String = "",          // adresse email complète
        val password: String = "",       // mot de passe ou app-password
        // IMAP (lecture)
        val imapHost: String = "",
        val imapPort: Int = 993,
        val imapSsl: Boolean = true,
        // SMTP (envoi)
        val smtpHost: String = "",
        val smtpPort: Int = 587,
        val smtpSsl: Boolean = false,
        val smtpStartTls: Boolean = true,
        val isDefault: Boolean = false,
        // OAuth2 Google (AccountManager)
        val oauthToken: String = "",
        val isOAuth: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("label", label); put("email", email)
            put("password", password)
            put("imapHost", imapHost); put("imapPort", imapPort); put("imapSsl", imapSsl)
            put("smtpHost", smtpHost); put("smtpPort", smtpPort); put("smtpSsl", smtpSsl)
            put("smtpStartTls", smtpStartTls); put("isDefault", isDefault)
            put("oauthToken", oauthToken); put("isOAuth", isOAuth)
        }

        companion object {
            fun fromJson(j: JSONObject) = EmailAccount(
                id          = j.optString("id", System.currentTimeMillis().toString()),
                label       = j.optString("label", ""),
                email       = j.optString("email", ""),
                password    = j.optString("password", ""),
                imapHost    = j.optString("imapHost", ""),
                imapPort    = j.optInt("imapPort", 993),
                imapSsl     = j.optBoolean("imapSsl", true),
                smtpHost    = j.optString("smtpHost", ""),
                smtpPort    = j.optInt("smtpPort", 587),
                smtpSsl     = j.optBoolean("smtpSsl", false),
                smtpStartTls= j.optBoolean("smtpStartTls", true),
                isDefault   = j.optBoolean("isDefault", false),
                oauthToken  = j.optString("oauthToken", ""),
                isOAuth     = j.optBoolean("isOAuth", false)
            )

            /** Configs pré-remplies pour les grands services. */
            fun preset(service: String, email: String, password: String): EmailAccount? = when (service.lowercase()) {
                "gmail" -> EmailAccount(
                    label = "Gmail", email = email, password = password,
                    imapHost = "imap.gmail.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.gmail.com", smtpPort = 587, smtpStartTls = true
                )
                "outlook", "hotmail", "live" -> EmailAccount(
                    label = "Outlook", email = email, password = password,
                    imapHost = "outlook.office365.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.office365.com", smtpPort = 587, smtpStartTls = true
                )
                "yahoo" -> EmailAccount(
                    label = "Yahoo", email = email, password = password,
                    imapHost = "imap.mail.yahoo.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.mail.yahoo.com", smtpPort = 587, smtpStartTls = true
                )
                "icloud" -> EmailAccount(
                    label = "iCloud", email = email, password = password,
                    imapHost = "imap.mail.me.com", imapPort = 993, imapSsl = true,
                    smtpHost = "smtp.mail.me.com", smtpPort = 587, smtpStartTls = true
                )
                else -> null
            }
        }
    }

    fun getEmailAccounts(context: Context): List<EmailAccount> {
        val json = prefs(context).getString(KEY_EMAIL_ACCOUNTS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { EmailAccount.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun getDefaultEmailAccount(context: Context): EmailAccount? =
        getEmailAccounts(context).firstOrNull { it.isDefault }
            ?: getEmailAccounts(context).firstOrNull()

    fun saveEmailAccounts(context: Context, accounts: List<EmailAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_EMAIL_ACCOUNTS, arr.toString()).apply()
    }

    fun addEmailAccount(context: Context, account: EmailAccount) {
        val list = getEmailAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        list.add(account)
        saveEmailAccounts(context, list)
    }

    fun removeEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).filter { it.id != id }
        saveEmailAccounts(context, list)
    }

    fun setDefaultEmailAccount(context: Context, id: String) {
        val list = getEmailAccounts(context).map { it.copy(isDefault = it.id == id) }
        saveEmailAccounts(context, list)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PROVIDER ACTIF, URL, MODÈLE
    // ═════════════════════════════════════════════════════════════════════════

    fun getProvider(context: Context): Provider =
        Provider.fromName(
            prefs(context).getString(KEY_PROVIDER, Provider.GROQ.name) ?: Provider.GROQ.name
        )

    fun getBaseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, Provider.GROQ.defaultBaseUrl)
            ?: Provider.GROQ.defaultBaseUrl

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, Provider.GROQ.defaultModel)
            ?: Provider.GROQ.defaultModel

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    // ─── Modèle local ─────────────────────────────────────────────────────────

    fun getLocalModelPath(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_PATH, "") ?: ""

    // Identifiant du modele Qwen local actif (voir LocalLlmController.AVAILABLE_MODELS) --
    // remplace KEY_LOCAL_MODEL_PATH/KEY_LOCAL_MODEL_FORMAT (ancien systeme GGUF/ONNX/MediaPipe
    // multi-format par chemin de fichier libre) pour les taches #247/#248 : LocalLlmController
    // gere lui-meme le chemin du fichier a partir de l'ID (voir modelFile()), donc seul l'ID
    // a besoin d'etre persiste ici.
    fun getLocalLlmModelId(context: Context): String =
        prefs(context).getString(KEY_LOCAL_LLM_MODEL_ID, "") ?: ""

    fun setLocalLlmModelId(context: Context, modelId: String) {
        prefs(context).edit().putString(KEY_LOCAL_LLM_MODEL_ID, modelId).apply()
    }

    // Fusion Phase 4g -- voir AiEngineManager.preferredFirstChain(). "auto"
    // (valeur par defaut) signifie : pas de surcharge, ordre de chaine normal.
    fun getPreferredEngineId(context: Context): String =
        prefs(context).getString(KEY_PREFERRED_ENGINE_ID, "auto") ?: "auto"

    fun setPreferredEngineId(context: Context, engineId: String) {
        prefs(context).edit().putString(KEY_PREFERRED_ENGINE_ID, engineId).apply()
    }

    fun saveLocalModelPath(context: Context, path: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_PATH, path).apply()
    }

    // ─── Modèle Stable Diffusion local (génération d'image embarquée) ──────────

    fun getLocalSdModelPath(context: Context): String =
        prefs(context).getString("local_sd_model_path", "") ?: ""

    fun saveLocalSdModelPath(context: Context, path: String) {
        prefs(context).edit().putString("local_sd_model_path", path).apply()
    }

    fun getLocalModelFormat(context: Context): String =
        prefs(context).getString(KEY_LOCAL_MODEL_FORMAT, "TASK") ?: "TASK"

    fun saveLocalModelFormat(context: Context, format: String) {
        prefs(context).edit().putString(KEY_LOCAL_MODEL_FORMAT, format).apply()
    }

    // ─── Registre des modèles locaux téléchargés (demande utilisateur : "encoche sur les
    // modèles locaux téléchargés" + "rotation entre les modèles") ────────────────────────
    // Jusqu'ici un SEUL modèle local pouvait exister à la fois : chaque téléchargement
    // écrasait le fichier précédent (toujours "local_model.<ext>"), donc impossible d'avoir
    // plusieurs modèles installés en même temps, impossible de savoir dans l'écran de
    // téléchargement lesquels étaient déjà présents, et impossible de basculer sur un autre
    // modèle si le modèle actif échoue. Ce registre (JSON, indépendant du fichier physique)
    // retient CHAQUE modèle téléchargé (chemin, format, libellé) -- getLocalModelPath/
    // getLocalModelFormat ci-dessus restent le modèle "actif" (essayé en premier), les autres
    // servent de repli automatique (voir ApiClient.sendLocal).
    data class LocalModelRegistryEntry(val path: String, val format: String, val label: String)

    private const val KEY_LOCAL_MODELS_REGISTRY = "local_models_registry"

    fun getLocalModelsRegistry(context: Context): List<LocalModelRegistryEntry> {
        val json = prefs(context).getString(KEY_LOCAL_MODELS_REGISTRY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull {
                val o = arr.getJSONObject(it)
                val path = o.optString("path")
                if (path.isBlank() || !java.io.File(path).exists()) return@mapNotNull null
                LocalModelRegistryEntry(path, o.optString("format"), o.optString("label"))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Ajoute (ou met à jour si déjà présent au même chemin) une entrée du registre. */
    fun addLocalModelToRegistry(context: Context, path: String, format: String, label: String) {
        val current = getLocalModelsRegistry(context).filter { it.path != path }
        val updated = current + LocalModelRegistryEntry(path, format, label)
        val arr = JSONArray()
        updated.forEach {
            arr.put(JSONObject().put("path", it.path).put("format", it.format).put("label", it.label))
        }
        prefs(context).edit().putString(KEY_LOCAL_MODELS_REGISTRY, arr.toString()).apply()
    }

    fun removeLocalModelFromRegistry(context: Context, path: String) {
        val updated = getLocalModelsRegistry(context).filter { it.path != path }
        val arr = JSONArray()
        updated.forEach {
            arr.put(JSONObject().put("path", it.path).put("format", it.format).put("label", it.label))
        }
        prefs(context).edit().putString(KEY_LOCAL_MODELS_REGISTRY, arr.toString()).apply()
    }

    // ─── UI / Style ───────────────────────────────────────────────────────────

    fun getAccentColor(context: Context): Int =
        prefs(context).getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT_COLOR)

    fun saveAccentColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_ACCENT_COLOR, color).apply()
    }

    // ─── Thème du chat (fond + bulles), personnalisable depuis le chat/vocal ──
    // 0 = pas de surcharge, on garde les couleurs par défaut du thème Apex Studio.
    private const val KEY_CHAT_BG_COLOR = "chat_bg_color"
    private const val KEY_CHAT_BUBBLE_USER_COLOR = "chat_bubble_user_color"
    private const val KEY_CHAT_BUBBLE_AI_COLOR = "chat_bubble_ai_color"

    fun getChatBackgroundColor(context: Context): Int = prefs(context).getInt(KEY_CHAT_BG_COLOR, 0)
    fun saveChatBackgroundColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CHAT_BG_COLOR, color).apply()
    }

    fun getChatBubbleUserColor(context: Context): Int = prefs(context).getInt(KEY_CHAT_BUBBLE_USER_COLOR, 0)
    fun saveChatBubbleUserColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CHAT_BUBBLE_USER_COLOR, color).apply()
    }

    fun getChatBubbleAiColor(context: Context): Int = prefs(context).getInt(KEY_CHAT_BUBBLE_AI_COLOR, 0)
    fun saveChatBubbleAiColor(context: Context, color: Int) {
        prefs(context).edit().putInt(KEY_CHAT_BUBBLE_AI_COLOR, color).apply()
    }

    fun resetChatTheme(context: Context) {
        prefs(context).edit()
            .remove(KEY_CHAT_BG_COLOR)
            .remove(KEY_CHAT_BUBBLE_USER_COLOR)
            .remove(KEY_CHAT_BUBBLE_AI_COLOR)
            .apply()
    }

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "chaque fois que je demande une
    // modification -- ordre, ajout d'une donnée, emoji... -- RIEN N'EST CONSERVÉ, ça change
    // complètement l'affichage") : les 3 fonctions saveXxxPresentationStyle ci-dessous
    // ÉCRASAIENT purement et simplement l'ancienne consigne à chaque nouvel appel. Résultat :
    // demander d'abord "ajoute la date de naissance" puis plus tard "mets le numéro avant
    // l'email" faisait perdre la première consigne, qui n'était plus jamais appliquée. On
    // accumule maintenant chaque nouvelle consigne à la suite des précédentes (au lieu de les
    // remplacer), pour qu'elles s'appliquent TOUTES ensemble à chaque fois. Un plafond de
    // longueur évite une croissance illimitée du prompt (voir réduction de tokens) : passé
    // ce plafond, on abandonne les consignes les plus anciennes (les plus susceptibles d'être
    // déjà couvertes par une consigne plus récente) plutôt que de tout tronquer au hasard.
    private const val MAX_STYLE_LENGTH = 900

    private fun appendStyleInstruction(existing: String, newInstruction: String): String {
        val trimmedNew = newInstruction.trim()
        if (trimmedNew.isBlank()) return existing
        var combined = if (existing.isBlank()) trimmedNew else "$existing ; puis : $trimmedNew"
        while (combined.length > MAX_STYLE_LENGTH) {
            val nextSeparator = combined.indexOf(" ; puis : ")
            if (nextSeparator < 0) break
            combined = combined.substring(nextSeparator + " ; puis : ".length)
        }
        return combined
    }

    // ─── Style de présentation préféré des fiches contact ─────────────────────
    // Cause réelle du bug "je demande un type de présentation et il ne le reprend pas
    // automatiquement" : le format d'une fiche contact était toujours généré tel quel par
    // PeopleController.formatFullDetails (fixe, en dur), et la seule façon de "changer" la
    // présentation était que l'IA reformule elle-même le texte dans SA réponse pour CE tour
    // précis — sans aucune mémoire persistante au-delà de la fenêtre de contexte de la
    // conversation en cours. Dès que la conversation redémarre ou que le contexte s'allonge,
    // la consigne de mise en forme est oubliée et JARVIS revient au format brut par défaut.
    private const val KEY_CONTACT_PRESENTATION_STYLE = "contact_presentation_style"

    fun getContactPresentationStyle(context: Context): String =
        prefs(context).getString(KEY_CONTACT_PRESENTATION_STYLE, "") ?: ""

    fun saveContactPresentationStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_CONTACT_PRESENTATION_STYLE, appendStyleInstruction(getContactPresentationStyle(context), style)).apply()
    }

    fun resetContactPresentationStyle(context: Context) {
        prefs(context).edit().remove(KEY_CONTACT_PRESENTATION_STYLE).apply()
    }

    // Même principe que ci-dessus mais pour la présentation de la localisation d'une personne
    // (ha_status{domain:"person"}) — demandé par l'utilisateur en même temps que la persistance
    // du style des fiches contact.
    private const val KEY_LOCATION_PRESENTATION_STYLE = "location_presentation_style"

    fun getLocationPresentationStyle(context: Context): String =
        prefs(context).getString(KEY_LOCATION_PRESENTATION_STYLE, "") ?: ""

    fun saveLocationPresentationStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_LOCATION_PRESENTATION_STYLE, appendStyleInstruction(getLocationPresentationStyle(context), style)).apply()
    }

    fun resetLocationPresentationStyle(context: Context) {
        prefs(context).edit().remove(KEY_LOCATION_PRESENTATION_STYLE).apply()
    }

    // Même principe que ci-dessus mais pour la présentation d'un planning/agenda
    // (today_events/upcoming_events/week_events/search_event) -- demande utilisateur
    // explicite, captures d'écran à l'appui : "quand je demande un planning, calendrier,
    // agenda, que cela s'affiche toujours comme sur l'image". Le format PAR DÉFAUT est déjà
    // du Kotlin déterministe groupé par jour (voir CalendarController.getEventsTimeRange),
    // donc utile surtout pour un réglage PERSONNALISÉ différent du défaut ; sans consigne
    // ici, l'affichage par défaut reste utilisé tel quel.
    private const val KEY_CALENDAR_PRESENTATION_STYLE = "calendar_presentation_style"

    fun getCalendarPresentationStyle(context: Context): String =
        prefs(context).getString(KEY_CALENDAR_PRESENTATION_STYLE, "") ?: ""

    fun saveCalendarPresentationStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_CALENDAR_PRESENTATION_STYLE, appendStyleInstruction(getCalendarPresentationStyle(context), style)).apply()
    }

    fun resetCalendarPresentationStyle(context: Context) {
        prefs(context).edit().remove(KEY_CALENDAR_PRESENTATION_STYLE).apply()
    }

    // ─── Liens cliquables (tel/mail/itinéraire) dans le texte des fiches ──────
    // Désactivé par défaut (false) : à la demande explicite de l'utilisateur, ces liens ne
    // doivent plus apparaître automatiquement dès qu'un numéro/email/adresse est détecté —
    // seulement quand il le demande, via enable_contact_links.
    private const val KEY_CONTACT_LINKS_ENABLED = "contact_links_enabled"

    fun isContactLinksEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONTACT_LINKS_ENABLED, false)

    fun setContactLinksEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONTACT_LINKS_ENABLED, enabled).apply()
    }

    fun getHfToken(context: Context): String =
        prefs(context).getString(KEY_HF_TOKEN, "") ?: ""

    fun saveHfToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_HF_TOKEN, token).apply()
    }

    fun getGithubToken(context: Context): String =
        prefs(context).getString("github_token", "") ?: ""

    // ═════════════════════════════════════════════════════════════════════════
    // MULTI-COMPTES GITHUB
    // ═════════════════════════════════════════════════════════════════════════

    data class GitHubAccount(
        val id: String = System.currentTimeMillis().toString(),
        val label: String = "",   // ex: "Perso", "Pro", "Client X"
        val token: String = "",
        val isDefault: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("label", label); put("token", token); put("isDefault", isDefault)
        }

        companion object {
            fun fromJson(j: JSONObject) = GitHubAccount(
                id        = j.optString("id", System.currentTimeMillis().toString()),
                label     = j.optString("label", ""),
                token     = j.optString("token", ""),
                isDefault = j.optBoolean("isDefault", false)
            )
        }
    }

    /**
     * Migration douce : si aucun compte n'a encore été ajouté à la nouvelle liste
     * multi-comptes mais qu'un ancien jeton unique (github_token, ⚙ → Clés API) existe,
     * il est exposé comme un compte "Principal" par défaut — la configuration existante
     * de l'utilisateur continue de fonctionner sans qu'il ait besoin de tout reconfigurer.
     */
    fun getGithubAccounts(context: Context): List<GitHubAccount> {
        val json = prefs(context).getString(KEY_GITHUB_ACCOUNTS, "[]") ?: "[]"
        val stored = try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { GitHubAccount.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
        if (stored.isNotEmpty()) return stored
        val legacyToken = getGithubToken(context)
        return if (legacyToken.isNotBlank()) {
            listOf(GitHubAccount(id = "legacy", label = "Principal", token = legacyToken, isDefault = true))
        } else {
            emptyList()
        }
    }

    fun getDefaultGithubAccount(context: Context): GitHubAccount? =
        getGithubAccounts(context).firstOrNull { it.isDefault }
            ?: getGithubAccounts(context).firstOrNull()

    /** Recherche floue d'un compte GitHub par son libellé (ex: "perso", "pro", "client X"). */
    fun findGithubAccount(context: Context, label: String): GitHubAccount? {
        if (label.isBlank()) return null
        val q = label.trim().lowercase()
        val all = getGithubAccounts(context)
        return all.firstOrNull { it.label.lowercase() == q }
            ?: all.firstOrNull { it.label.lowercase().contains(q) }
    }

    fun saveGithubAccounts(context: Context, accounts: List<GitHubAccount>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_GITHUB_ACCOUNTS, arr.toString()).apply()
    }

    fun addGithubAccount(context: Context, account: GitHubAccount) {
        val list = getGithubAccounts(context).toMutableList()
        list.removeAll { it.id == account.id }
        // Le tout premier compte ajouté devient automatiquement celui par défaut.
        val withDefault = if (list.isEmpty()) account.copy(isDefault = true) else account
        list.add(withDefault)
        saveGithubAccounts(context, list)
    }

    fun removeGithubAccount(context: Context, id: String) {
        val list = getGithubAccounts(context).filter { it.id != id }
        saveGithubAccounts(context, list)
    }

    fun setDefaultGithubAccount(context: Context, id: String) {
        val list = getGithubAccounts(context).map { it.copy(isDefault = it.id == id) }
        saveGithubAccounts(context, list)
    }

    // ─── Pipeline logs -> GitHub Gist (demande utilisateur : que Claude puisse recuperer les
    //     logs "directement" sans etape manuelle) : un Gist PRIVE unique est cree puis mis a
    //     jour a chaque envoi (au lieu d'un nouveau Gist a chaque fois), son id est retenu ici.
    //     Actif par defaut (choix explicite : envoi auto a chaque erreur + sur commande),
    //     mais reste desactivable dans Reglages pour la confidentialite.

    fun getLogsGistId(context: Context): String? =
        prefs(context).getString(KEY_LOGS_GIST_ID, null)?.ifBlank { null }

    fun setLogsGistId(context: Context, gistId: String) {
        prefs(context).edit().putString(KEY_LOGS_GIST_ID, gistId).apply()
    }

    fun isLogsAutoUploadEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGS_AUTO_UPLOAD, true)

    fun setLogsAutoUploadEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LOGS_AUTO_UPLOAD, enabled).apply()
    }

    // ─── Écoute permanente (mot-clé d'activation) ───────────────────────────────

    fun getWakeWord(context: Context): String =
        prefs(context).getString("wake_word", "jarvis") ?: "jarvis"

    fun saveWakeWord(context: Context, word: String) {
        prefs(context).edit().putString("wake_word", word.ifBlank { "jarvis" }).apply()
    }

    fun isWakeWordEnabled(context: Context): Boolean =
        prefs(context).getBoolean("wake_word_enabled", false)

    fun saveWakeWordEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("wake_word_enabled", enabled).apply()
    }

    /** Nom d'affichage de l'assistant (titre UI, message d'accueil, statuts "réfléchit"/
     *  "répond", persona dans les prompts IA) — demande utilisateur : "pour le nom de JARVIS
     *  peux-tu faire en sorte qu'il se modifie en fonction du mot-clé choisi pour l'écoute".
     *  Réutilise DÉLIBÉRÉMENT le mot-clé d'écoute (wake word) déjà existant ci-dessus comme
     *  SEULE source de vérité, plutôt que d'ajouter un champ "nom" redondant qui pourrait
     *  diverger du mot-clé réellement écouté — évite d'avoir à synchroniser deux réglages
     *  différents pour ce qui est conceptuellement la même identité ("comment on appelle
     *  l'assistant"). Capitalisé pour l'affichage (le mot-clé est saisi en minuscules). */
    fun getAssistantDisplayName(context: Context): String {
        val raw = getWakeWord(context).trim()
        if (raw.isBlank()) return "Jarvis"
        return raw.replaceFirstChar { it.uppercase() }
    }

    // ─── Surnoms de calendriers (pour distinguer plusieurs agendas similaires) ──

    fun getCalendarNickname(context: Context, calendarId: Long): String =
        prefs(context).getString("calendar_nickname_$calendarId", "") ?: ""

    fun saveCalendarNickname(context: Context, calendarId: Long, nickname: String) {
        prefs(context).edit().putString("calendar_nickname_$calendarId", nickname).apply()
    }

    /** Retrouve l'ID d'un calendrier à partir d'un surnom déjà enregistré. */
    fun findCalendarIdByNickname(context: Context, nickname: String): Long? {
        val all = prefs(context).all
        for ((key, value) in all) {
            if (key.startsWith("calendar_nickname_") && value is String &&
                value.equals(nickname, ignoreCase = true)
            ) {
                return key.removePrefix("calendar_nickname_").toLongOrNull()
            }
        }
        return null
    }

    /**
     * Efface TOUS les surnoms de calendrier enregistrés. Ces surnoms vivent dans les
     * SharedPreferences de l'app (pas dans le vault Obsidian) — "réinitialiser Obsidian"
     * ne les touche donc jamais, ce qui explique qu'ils survivent à un vidage du vault.
     * Cette fonction est le vrai bouton de réinitialisation pour eux spécifiquement.
     */
    fun clearAllCalendarNicknames(context: Context): Int {
        val p = prefs(context)
        val keysToRemove = p.all.keys.filter { it.startsWith("calendar_nickname_") }
        val editor = p.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
        return keysToRemove.size
    }

    // ─── Calendrier par défaut ("mon planning") ─────────────────────────────────
    // Demande utilisateur : "quand je lui demande mon planning il m'affiche un planning
    // spécifique, comme un surnom" -- mémorise UN calendrier précis (par ID, résolu au
    // moment de set_default_calendar depuis un nom/surnom/compte) à utiliser automatiquement
    // pour today_events/upcoming_events/week_events/search_event quand aucun calendrier
    // n'est explicitement précisé dans la demande, au lieu du repli générique "tous les
    // calendriers Google" (voir CalendarController.getEventsTimeRange/searchEvents).
    // Même mécanisme de stockage (SharedPreferences) que les surnoms de calendrier ci-dessus,
    // pour cohérence -- pas dans le vault Obsidian.

    fun getDefaultCalendarId(context: Context): Long? {
        val v = prefs(context).getLong(KEY_DEFAULT_CALENDAR_ID, -1L)
        return if (v == -1L) null else v
    }

    fun setDefaultCalendarId(context: Context, calendarId: Long?) {
        val editor = prefs(context).edit()
        if (calendarId == null) editor.remove(KEY_DEFAULT_CALENDAR_ID) else editor.putLong(KEY_DEFAULT_CALENDAR_ID, calendarId)
        editor.apply()
    }


    fun saveGithubToken(context: Context, token: String) {
        prefs(context).edit().putString("github_token", token).apply()
    }

    fun getOrbStyle(context: Context): String =
        prefs(context).getString(KEY_ORB_STYLE, "NETWORK_SPHERE") ?: "NETWORK_SPHERE"

    fun saveOrbStyle(context: Context, style: String) {
        prefs(context).edit().putString(KEY_ORB_STYLE, style).apply()
    }

    // ─── Sauvegarde groupée ───────────────────────────────────────────────────

    fun save(context: Context, provider: Provider, baseUrl: String, model: String, apiKey: String) {
        prefs(context).edit()
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_BASE_URL, baseUrl.ifBlank { provider.defaultBaseUrl })
            .putString(KEY_MODEL, model.ifBlank { provider.defaultModel })
            .putString(KEY_API_KEY, apiKey)
            .apply()
        if (!provider.isLocal && !provider.isAuto && apiKey.isNotBlank()) {
            // Ajoute à la liste multi-clés si pas déjà présente
            addApiKeyFor(context, provider, apiKey)
        }
    }

    // ─── Obsidian Second Brain ────────────────────────────────────────────────

    fun getObsidianVaultPath(context: Context): String =
        prefs(context).getString(KEY_OBSIDIAN_VAULT_PATH, "") ?: ""

    fun saveObsidianVaultPath(context: Context, path: String) =
        prefs(context).edit().putString(KEY_OBSIDIAN_VAULT_PATH, path).apply()

    // ═════════════════════════════════════════════════════════════════════════
    // HOME ASSISTANT (domotique)
    // ═════════════════════════════════════════════════════════════════════════

    fun getHaUrl(context: Context): String =
        prefs(context).getString("ha_url", "") ?: ""

    fun saveHaUrl(context: Context, url: String) {
        prefs(context).edit().putString("ha_url", url.trim().trimEnd('/')).apply()
    }

    fun getHaToken(context: Context): String =
        prefs(context).getString("ha_token", "") ?: ""

    fun saveHaToken(context: Context, token: String) {
        prefs(context).edit().putString("ha_token", token.trim()).apply()
    }

    /**
     * URL distante de secours pour Home Assistant (ex: URL Nabu Casa
     * "https://xxxx.ui.nabu.casa", ou une URL externe/DDNS + reverse-proxy configurée
     * par l'utilisateur) — utilisée UNIQUEMENT quand l'URL locale (ha_url) est
     * injoignable, pour piloter la maison même hors du réseau Wi-Fi local.
     */
    fun getHaRemoteUrl(context: Context): String =
        prefs(context).getString("ha_remote_url", "") ?: ""

    fun saveHaRemoteUrl(context: Context, url: String) {
        prefs(context).edit().putString("ha_remote_url", url.trim().trimEnd('/')).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // RÉSEAU LOCAL — appareils enregistrés (pour Wake-on-LAN rapide)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * [remoteHost] = adresse publique/DDNS (optionnellement suivie de ":port") vers laquelle
     * basculer quand l'appareil n'est plus joignable en local (donc hors du Wi-Fi domestique).
     * JARVIS ne peut PAS créer cet accès lui-même : l'utilisateur doit avoir configuré une
     * redirection de port (port forwarding) sur sa box/routeur vers cet appareil, idéalement
     * avec une IP fixe ou un nom DDNS (No-IP, DuckDNS...) puisque l'IP publique change souvent.
     * Une fois ce champ renseigné, network_ping/network_open_web/wake_on_lan/print_file
     * basculent automatiquement dessus si l'accès local échoue.
     */
    data class SavedDevice(val name: String, val mac: String, val ip: String = "", val remoteHost: String = "")

    fun getSavedNetworkDevices(context: Context): List<SavedDevice> {
        val json = prefs(context).getString("network_saved_devices", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SavedDevice(o.optString("name"), o.optString("mac"), o.optString("ip", ""), o.optString("remoteHost", ""))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveNetworkDevice(context: Context, device: SavedDevice) {
        val list = getSavedNetworkDevices(context).filter { it.name != device.name }.toMutableList()
        list.add(device)
        writeSavedNetworkDevices(context, list)
    }

    fun removeNetworkDevice(context: Context, name: String) {
        writeSavedNetworkDevices(context, getSavedNetworkDevices(context).filter { it.name != name })
    }

    /** Enregistre/actualise l'adresse distante (publique/DDNS) d'un appareil déjà connu (ou nouveau). */
    fun setDeviceRemoteHost(context: Context, name: String, remoteHost: String) {
        val list = getSavedNetworkDevices(context).toMutableList()
        val idx = list.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx >= 0) {
            list[idx] = list[idx].copy(remoteHost = remoteHost.trim())
        } else {
            list.add(SavedDevice(name = name, mac = "", ip = "", remoteHost = remoteHost.trim()))
        }
        writeSavedNetworkDevices(context, list)
    }

    /**
     * Enregistre automatiquement les appareils trouvés lors d'un scan réseau
     * (📡 Réseau local, ou commande vocale/chat "scanne le réseau") dans la
     * liste des appareils connus, pour qu'ils apparaissent dans l'écran
     * Réseau local sans action manuelle. Un appareil déjà connu (même IP) est
     * mis à jour (nom rafraîchi) sans écraser une adresse MAC déjà renseignée
     * manuellement pour le Wake-on-LAN.
     */
    fun saveScannedDevices(context: Context, devices: List<NetworkController.Device>) {
        if (devices.isEmpty()) return
        val existing = getSavedNetworkDevices(context).toMutableList()

        devices.forEach { scanned ->
            val label = scanned.label
            val scannedMac = scanned.mac?.takeIf { it.isNotBlank() } ?: ""
            val existingIdx = existing.indexOfFirst { it.ip.isNotBlank() && it.ip == scanned.ip }
            if (existingIdx >= 0) {
                val current = existing[existingIdx]
                existing[existingIdx] = current.copy(
                    // Ne renomme que si l'utilisateur n'a pas déjà donné un nom manuel (mac vide = probablement auto-détecté).
                    name = if (current.mac.isBlank() && current.name != label) label else current.name,
                    // Garde une MAC déjà connue ; ne la remplace que si elle était vide.
                    mac = current.mac.ifBlank { scannedMac },
                    ip = scanned.ip
                )
            } else {
                val nameTaken = existing.any { it.name == label }
                val finalName = if (nameTaken) "$label (${scanned.ip})" else label
                existing.add(SavedDevice(name = finalName, mac = scannedMac, ip = scanned.ip))
            }
        }

        writeSavedNetworkDevices(context, existing)
    }

    private fun writeSavedNetworkDevices(context: Context, list: List<SavedDevice>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().put("name", d.name).put("mac", d.mac).put("ip", d.ip).put("remoteHost", d.remoteHost))
        }
        prefs(context).edit().putString("network_saved_devices", arr.toString()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // GÉNÉRATION VIDÉO (Replicate) & SITES WEB
    // ═════════════════════════════════════════════════════════════════════════

    /** Jeton API Replicate (replicate.com/account/api-tokens) pour la génération vidéo IA. */
    fun getReplicateToken(context: Context): String =
        prefs(context).getString("replicate_token", "") ?: ""

    fun saveReplicateToken(context: Context, token: String) {
        prefs(context).edit().putString("replicate_token", token.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // IMPRIMANTE RÉSEAU PAR DÉFAUT (IPP)
    // ═════════════════════════════════════════════════════════════════════════

    /** Adresse IP de l'imprimante réseau à utiliser par défaut quand aucune n'est précisée dans la commande. */
    fun getDefaultPrinterIp(context: Context): String =
        prefs(context).getString("default_printer_ip", "") ?: ""

    fun saveDefaultPrinterIp(context: Context, ip: String) {
        prefs(context).edit().putString("default_printer_ip", ip.trim()).apply()
    }

    /**
     * Adresse distante (publique/DDNS, ex: "monreseau.ddns.net:6310") de l'imprimante par
     * défaut, utilisée en repli si l'IP locale est injoignable (donc aussi hors Wi-Fi
     * domestique) — nécessite que l'utilisateur ait redirigé le port 631 (ou un port de son
     * choix) de sa box/routeur vers l'imprimante.
     */
    fun getDefaultPrinterRemoteHost(context: Context): String =
        prefs(context).getString("default_printer_remote_host", "") ?: ""

    fun saveDefaultPrinterRemoteHost(context: Context, host: String) {
        prefs(context).edit().putString("default_printer_remote_host", host.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // FREEBOX OS API (contrôle complet : LAN, Wi-Fi, domotique Freebox Home)
    // Ré-ajoutée à la demande explicite de l'utilisateur (accès total lecture/écriture
    // depuis JARVIS). app_token = jeton obtenu lors de l'appairage de l'app avec la
    // Freebox (Freebox OS -> Paramètres -> Gestion des accès -> Applications), JAMAIS
    // codé en dur dans le code source (dépôt public) : uniquement saisi ici via ⚙.
    // ═════════════════════════════════════════════════════════════════════════

    fun getFreeboxHost(context: Context): String {
        val raw = prefs(context).getString("freebox_host", "") ?: ""
        return raw.ifBlank { "http://mafreebox.freebox.fr" }
    }

    fun saveFreeboxHost(context: Context, host: String) {
        prefs(context).edit().putString("freebox_host", host.trim()).apply()
    }

    fun getFreeboxAppId(context: Context): String =
        prefs(context).getString("freebox_app_id", "") ?: ""

    fun saveFreeboxAppId(context: Context, appId: String) {
        prefs(context).edit().putString("freebox_app_id", appId.trim()).apply()
    }

    fun getFreeboxAppToken(context: Context): String =
        prefs(context).getString("freebox_app_token", "") ?: ""

    fun saveFreeboxAppToken(context: Context, token: String) {
        prefs(context).edit().putString("freebox_app_token", token.trim()).apply()
    }

    // Appairage EN ATTENTE (track_id + app_token candidat, avant validation sur l'écran
    // de la Freebox) — persisté ICI (pas seulement en mémoire) pour survivre à une éventuelle
    // fermeture du processus JARVIS pendant les ~90s d'attente (appli mise en arrière-plan,
    // gestion de batterie agressive...) : sans ça, l'utilisateur valide bien la demande sur
    // l'écran physique mais JARVIS "oublie" le track_id et redemande un nouvel appairage à
    // chaque fois, cause réelle du signalement "j'accepte sur l'écran mais elle dit toujours
    // non appairée". Voir FreeboxController.tryResolvePendingPairing.
    fun getFreeboxPendingTrackId(context: Context): Int =
        prefs(context).getInt("freebox_pending_track_id", -1)

    fun getFreeboxPendingAppToken(context: Context): String =
        prefs(context).getString("freebox_pending_app_token", "") ?: ""

    fun saveFreeboxPendingPairing(context: Context, trackId: Int, appToken: String) {
        prefs(context).edit()
            .putInt("freebox_pending_track_id", trackId)
            .putString("freebox_pending_app_token", appToken)
            .apply()
    }

    fun clearFreeboxPendingPairing(context: Context) {
        prefs(context).edit()
            .remove("freebox_pending_track_id")
            .remove("freebox_pending_app_token")
            .apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BOX INTERNET UNIFIÉE (voir RouterController) — un seul système pour piloter
    // Freebox, Livebox (Orange), SFR Box ou Bbox (Bouygues) selon le fournisseur
    // choisi ici. La Freebox continue d'utiliser app_id/app_token ci-dessus
    // (appairage officiel) ; les 3 autres n'ont pas de mécanisme d'appairage à
    // l'écran (pas d'écran physique sur ces box) donc utilisent le mot de passe
    // admin local, jamais codé en dur (saisi uniquement ici via ⚙, dépôt public).
    // ═════════════════════════════════════════════════════════════════════════

    fun getBoxVendor(context: Context): String =
        prefs(context).getString("box_vendor", "FREEBOX") ?: "FREEBOX"

    fun saveBoxVendor(context: Context, vendor: String) {
        prefs(context).edit().putString("box_vendor", vendor).apply()
    }

    fun getBoxHost(context: Context, default: String): String {
        val raw = prefs(context).getString("box_host", "") ?: ""
        return raw.ifBlank { default }
    }

    fun saveBoxHost(context: Context, host: String) {
        prefs(context).edit().putString("box_host", host.trim()).apply()
    }

    fun getBoxPassword(context: Context): String =
        prefs(context).getString("box_password", "") ?: ""

    fun saveBoxPassword(context: Context, password: String) {
        prefs(context).edit().putString("box_password", password).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // HISTORIQUE DES GÉNÉRATIONS (image / vidéo / site web)
    // ═════════════════════════════════════════════════════════════════════════

    data class GenerationRecord(
        val id: String,
        val type: String,          // "image" | "video" | "website"
        val prompt: String,
        val status: String,        // "pending" | "success" | "failed"
        val timestamp: Long,
        val resultPath: String? = null,
        val errorMessage: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id); put("type", type); put("prompt", prompt)
            put("status", status); put("timestamp", timestamp)
            put("resultPath", resultPath ?: ""); put("errorMessage", errorMessage ?: "")
        }
        companion object {
            fun fromJson(j: JSONObject) = GenerationRecord(
                id = j.optString("id"),
                type = j.optString("type"),
                prompt = j.optString("prompt"),
                status = j.optString("status", "pending"),
                timestamp = j.optLong("timestamp", System.currentTimeMillis()),
                resultPath = j.optString("resultPath", "").ifBlank { null },
                errorMessage = j.optString("errorMessage", "").ifBlank { null }
            )
        }
    }

    private const val MAX_GENERATION_HISTORY = 100

    /** Les plus récentes en premier. */
    fun getGenerationHistory(context: Context): List<GenerationRecord> {
        val json = prefs(context).getString("generation_history", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { GenerationRecord.fromJson(arr.getJSONObject(it)) }
                .sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun addGenerationRecord(context: Context, record: GenerationRecord) {
        val list = getGenerationHistory(context).toMutableList()
        list.add(0, record)
        writeGenerationHistory(context, list.take(MAX_GENERATION_HISTORY))
        if (record.status == "success") logGenerationToVault(context, record)
    }

    /** Met à jour l'enregistrement identifié par [id] (ex: passage pending → success/failed). */
    fun updateGenerationRecord(context: Context, id: String, transform: (GenerationRecord) -> GenerationRecord) {
        var updated: GenerationRecord? = null
        val list = getGenerationHistory(context).map {
            if (it.id == id) {
                val was = it.status
                val t = transform(it)
                if (was != "success" && t.status == "success") updated = t
                t
            } else it
        }
        writeGenerationHistory(context, list)
        updated?.let { logGenerationToVault(context, it) }
    }

    /**
     * Obsidian en premier plan : toute génération réussie (image, vidéo, site web, graphique,
     * PDF/Word/Excel/ZIP) est aussi tracée sous forme de note dans le vault (dossier « Générations »),
     * en plus de rester listée par list_generations — ainsi obsidian_search/obsidian_list retrouvent
     * aussi ces actions, et l'historique survit même si le cache generation_history est un jour purgé.
     * Best-effort : une erreur d'écriture vault (permission stockage manquante, etc.) ne doit jamais
     * faire échouer la génération elle-même, d'où le try/catch silencieux (déjà loggé côté ObsidianController).
     */
    private fun logGenerationToVault(context: Context, record: GenerationRecord) {
        try {
            val typeLabel = when (record.type) {
                "image" -> "🎨 Image générée"
                "video" -> "🎬 Vidéo générée"
                "website", "website_edit" -> "🌐 Site web généré"
                "chart" -> "📊 Graphique généré"
                "file_pdf" -> "📄 PDF créé"
                "file_docx" -> "📝 Document Word créé"
                "file_xlsx" -> "📊 Tableur Excel créé"
                "file_zip" -> "🗜️ Archive ZIP créée"
                else -> "✨ Génération (${record.type})"
            }
            val safePrompt = record.prompt.ifBlank { "(sans description)" }
            val title = "$typeLabel — ${safePrompt.take(60)}"
            val content = buildString {
                append("Type : ${record.type}\n")
                append("Description : $safePrompt\n")
                if (!record.resultPath.isNullOrBlank()) append("Fichier : ${record.resultPath}\n")
            }
            ObsidianController.createNote(context, title, content, folder = "Générations", tags = listOf("jarvis", "generation", record.type))
        } catch (_: Exception) {
            // best-effort, ne jamais faire échouer la génération pour ça
        }
    }

    fun removeGenerationRecord(context: Context, id: String) {
        writeGenerationHistory(context, getGenerationHistory(context).filter { it.id != id })
    }

    /**
     * Vide entièrement l'historique des générations (liste consultable dans l'onglet
     * 🎨 Génération / list_generations). Ne supprime PAS les fichiers déjà créés sur le
     * disque (images/vidéos/sites restent accessibles via l'explorateur de fichiers), ni
     * les notes déjà écrites dans le vault Obsidian par logGenerationToVault — seule la
     * liste elle-même (ce cache generation_history) est réinitialisée. Action irréversible
     * côté historique, donc à confirmer avant d'appeler (voir clear_generation_history).
     */
    fun clearGenerationHistory(context: Context) {
        writeGenerationHistory(context, emptyList())
    }

    // Au-delà de ce délai, aucune génération ne travaille légitimement encore : la vidéo
    // est plafonnée à ~3min20 de sondage (MAX_POLL_ATTEMPTS côté VideoGenController), l'image
    // à 90s de délai réseau max, le site à la durée d'un simple appel de chat. Un enregistrement
    // encore "pending" après ce délai signifie que GenerationService a été tué par le système
    // (optimisation de batterie, manque de mémoire, app fermée de force) avant d'avoir pu écrire
    // un résultat — pas qu'il travaille toujours. Sans cette réconciliation, ces enregistrements
    // restaient bloqués sur "en cours" indéfiniment, ce qui donnait l'impression d'une boucle
    // infinie côté utilisateur.
    private const val STALE_GENERATION_TIMEOUT_MS = 10 * 60 * 1000L

    /**
     * Marque comme "failed" (avec une cause honnête) tout enregistrement resté "pending"
     * plus longtemps que ce qu'une génération met légitimement à se terminer. À appeler à
     * chaque ouverture/rafraîchissement de l'écran Génération. Retourne true si au moins un
     * enregistrement a été corrigé.
     */
    fun reconcileStaleGenerations(context: Context): Boolean {
        val now = System.currentTimeMillis()
        var changed = false
        val list = getGenerationHistory(context).map { record ->
            if (record.status == "pending" && now - record.timestamp > STALE_GENERATION_TIMEOUT_MS) {
                changed = true
                record.copy(
                    status = "failed",
                    errorMessage = "Interrompue : le service a été arrêté par le système avant la fin " +
                        "(optimisation de batterie, manque de mémoire, ou application fermée de force), " +
                        "sans message d'erreur explicite du moteur de génération. Réessaie ; si ça se " +
                        "reproduit souvent, désactive l'optimisation de batterie pour JARVIS dans les " +
                        "réglages système Android."
                )
            } else record
        }
        if (changed) writeGenerationHistory(context, list)
        return changed
    }

    private fun writeGenerationHistory(context: Context, list: List<GenerationRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString("generation_history", arr.toString()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTÉGRATIONS MCP DISTANTES (Perplexity, Firecrawl, Glif)
    // ═════════════════════════════════════════════════════════════════════════
    // Playwright n'a pas d'équivalent hébergé/distant officiel (c'est un serveur MCP local qui
    // pilote un vrai navigateur via Node.js — pas embarquable dans un APK Android) : remplacé
    // en pratique par web_search/open_maps déjà existants. Les 3 ci-dessous exposent une API
    // HTTP distante utilisable directement depuis le téléphone.

    // Perplexity n'a PAS de stockage dédié ici : PerplexityController réutilise directement
    // Prefs.getApiKeysFor(context, Provider.PERPLEXITY), la clé déjà configurable dans
    // ⚙ → Clés API (Perplexity est déjà un fournisseur du mode Automatique de chat) — inutile
    // de faire ressaisir la même clé une deuxième fois dans un champ séparé.

    /** Clé API Firecrawl (firecrawl.dev, préfixe "fc-") — extraction propre de pages web. */
    fun getFirecrawlApiKey(context: Context): String =
        prefs(context).getString("firecrawl_api_key", "") ?: ""

    fun saveFirecrawlApiKey(context: Context, key: String) {
        prefs(context).edit().putString("firecrawl_api_key", key.trim()).apply()
    }

    /** Jeton API Glif (glif.app/settings/api-tokens) — exécution de workflows IA (glifs). */
    fun getGlifApiToken(context: Context): String =
        prefs(context).getString("glif_api_token", "") ?: ""

    fun saveGlifApiToken(context: Context, token: String) {
        prefs(context).edit().putString("glif_api_token", token.trim()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // STABLE DIFFUSION WEBUI VIA TERMUX (voir TermuxController)
    // ═════════════════════════════════════════════════════════════════════════
    // Désactivé par défaut : nécessite une installation manuelle complète côté utilisateur
    // (Termux + allow-external-apps + permission RUN_COMMAND) avant d'avoir le moindre effet
    // — jamais activé automatiquement, pour ne jamais tenter silencieusement une commande
    // Termux chez un utilisateur qui n'a rien configuré.
    private const val KEY_TERMUX_SD_ENABLED = "termux_sd_enabled"

    fun isTermuxSdEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TERMUX_SD_ENABLED, false)

    fun setTermuxSdEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TERMUX_SD_ENABLED, enabled).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MODÈLES DE FICHES CONTACT (templates réutilisables)
    // ═════════════════════════════════════════════════════════════════════════
    // Un modèle est un jeu de valeurs par défaut nommé (ex: "Client pro", "Famille"),
    // créé une fois puis réutilisé pour : (1) pré-remplir un NOUVEAU contact, (2) mettre
    // à jour un contact EXISTANT en lui appliquant ces valeurs. Champs identiques à ceux
    // de PeopleController.saveContact() pour que le mapping modèle → contact soit direct.
    // Stocké en JSON dans les SharedPreferences (même schéma que GenerationRecord ci-dessus).

    data class ContactTemplate(
        val name: String,
        val category: String? = null,
        val nickname: String? = null,
        val phone: String? = null,
        val phonePro: String? = null,
        val email: String? = null,
        val address: String? = null,
        val addressPro: String? = null,
        val birthday: String? = null,
        val company: String? = null,
        val position: String? = null,
        val notes: String? = null
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name)
            put("category", category ?: "")
            put("nickname", nickname ?: "")
            put("phone", phone ?: "")
            put("phonePro", phonePro ?: "")
            put("email", email ?: "")
            put("address", address ?: "")
            put("addressPro", addressPro ?: "")
            put("birthday", birthday ?: "")
            put("company", company ?: "")
            put("position", position ?: "")
            put("notes", notes ?: "")
        }
        companion object {
            fun fromJson(j: JSONObject) = ContactTemplate(
                name = j.optString("name"),
                category = j.optString("category", "").ifBlank { null },
                nickname = j.optString("nickname", "").ifBlank { null },
                phone = j.optString("phone", "").ifBlank { null },
                phonePro = j.optString("phonePro", "").ifBlank { null },
                email = j.optString("email", "").ifBlank { null },
                address = j.optString("address", "").ifBlank { null },
                addressPro = j.optString("addressPro", "").ifBlank { null },
                birthday = j.optString("birthday", "").ifBlank { null },
                company = j.optString("company", "").ifBlank { null },
                position = j.optString("position", "").ifBlank { null },
                notes = j.optString("notes", "").ifBlank { null }
            )
        }
    }

    /** Triés par nom pour un affichage/recherche stable. */
    fun getContactTemplates(context: Context): List<ContactTemplate> {
        val json = prefs(context).getString("contact_templates", "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { ContactTemplate.fromJson(arr.getJSONObject(it)) }
                .sortedBy { it.name.lowercase() }
        } catch (_: Exception) { emptyList() }
    }

    /** Recherche insensible à la casse/accents, comme findExactNameMatch côté contacts. */
    fun findContactTemplate(context: Context, name: String): ContactTemplate? {
        val normalized = name.trim().lowercase()
        if (normalized.isBlank()) return null
        return getContactTemplates(context).firstOrNull { it.name.trim().lowercase() == normalized }
    }

    /**
     * Crée le modèle [template] ou, s'il en existe déjà un du même nom (comparaison
     * insensible à la casse), le remplace entièrement — comportement "upsert" plutôt que
     * doublons silencieux ou erreur bloquante, cohérent avec saveContact() qui réutilise
     * lui aussi une fiche existante de même nom au lieu d'en créer une nouvelle.
     */
    fun saveContactTemplate(context: Context, template: ContactTemplate) {
        val normalized = template.name.trim().lowercase()
        val list = getContactTemplates(context).filter { it.name.trim().lowercase() != normalized }.toMutableList()
        list.add(template)
        writeContactTemplates(context, list)
    }

    /** Retourne true si un modèle correspondant a bien été trouvé et supprimé. */
    fun deleteContactTemplate(context: Context, name: String): Boolean {
        val normalized = name.trim().lowercase()
        val before = getContactTemplates(context)
        val after = before.filter { it.name.trim().lowercase() != normalized }
        if (after.size == before.size) return false
        writeContactTemplates(context, after)
        return true
    }

    private fun writeContactTemplates(context: Context, list: List<ContactTemplate>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString("contact_templates", arr.toString()).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ESTIMATION DE CONSOMMATION DE TOKENS (demande utilisateur : "regarde ça par réponse")
    // ═════════════════════════════════════════════════════════════════════════
    // Pas d'API officielle de comptage de tokens disponible côté client (nécessiterait le
    // tokenizer EXACT de chaque fournisseur, différent selon le modèle) : estimation standard
    // ~4 caractères = 1 token (approximation communément admise pour le français/anglais,
    // suffisante pour repérer un prompt anormalement gros plutôt qu'un chiffre exact facturé).
    // Enregistré à CHAQUE appel IA (voir ApiClient.sendChat) : dernière requête + cumul depuis
    // l'installation, consultable en conversation via l'action token_usage.

    fun recordTokenUsage(context: Context, promptChars: Int, responseChars: Int) {
        val promptTokens = promptChars / 4
        val responseTokens = responseChars / 4
        val totalRequests = prefs(context).getLong("token_usage_requests", 0L) + 1
        val totalTokens = prefs(context).getLong("token_usage_total", 0L) + promptTokens + responseTokens
        prefs(context).edit()
            .putInt("token_usage_last_prompt", promptTokens)
            .putInt("token_usage_last_response", responseTokens)
            .putLong("token_usage_requests", totalRequests)
            .putLong("token_usage_total", totalTokens)
            .apply()
    }

    fun getTokenUsageReport(context: Context): String {
        val lastPrompt = prefs(context).getInt("token_usage_last_prompt", 0)
        val lastResponse = prefs(context).getInt("token_usage_last_response", 0)
        val requests = prefs(context).getLong("token_usage_requests", 0L)
        val total = prefs(context).getLong("token_usage_total", 0L)
        if (requests == 0L) return "📊 Aucun appel IA enregistré pour l'instant."
        val avg = if (requests > 0) total / requests else 0L
        val localHits = prefs(context).getLong("token_usage_local_hits", 0L)
        val localLine = if (localHits > 0) {
            val pct = (localHits * 100 / requests)
            "\n• Répondu en local (gratuit, sans cloud) : $localHits/$requests appel(s) (~$pct%)"
        } else ""
        return "📊 Estimation de tokens (≈4 caractères = 1 token, approximatif — pas de compteur " +
            "officiel côté téléphone) :\n" +
            "• Dernière requête : ~$lastPrompt tokens envoyés (prompt système + mémoire + contexte " +
            "vault + historique), ~$lastResponse tokens reçus\n" +
            "• Depuis l'installation : $requests appel(s) IA, ~$total tokens au total (~$avg/appel " +
            "en moyenne)$localLine"
    }

    fun clearTokenUsage(context: Context) {
        prefs(context).edit()
            .remove("token_usage_last_prompt")
            .remove("token_usage_last_response")
            .remove("token_usage_requests")
            .remove("token_usage_total")
            .remove("token_usage_local_hits")
            .apply()
    }

    // ─── Mode "IA locale d'abord" (économie de tokens) ─────────────────────────────────────
    // Demande utilisateur : "faire des prompts plus courts ou une consommation de token
    // beaucoup moins importante, passer par IA locale et cloud ?" -- quand actif, ApiClient
    // tente D'ABORD le modèle embarqué (Gemini Nano/Qwen local, gratuit, ~200 tokens de prompt
    // système au lieu de ~1400-6800) pour chaque message ; si le modèle local s'estime incapable
    // de répondre (données réelles du téléphone nécessaires) ou échoue, on repasse
    // automatiquement et silencieusement sur le fournisseur cloud habituel -- l'utilisateur ne
    // voit jamais la tentative locale ratée, seulement la réponse finale. Désactivé par défaut
    // (comportement inchangé tant que l'utilisateur ne l'active pas) car un petit modèle local
    // peut se tromper sur des demandes ambiguës qu'un modèle cloud aurait mieux gérées.
    fun isLocalFirstMode(context: Context): Boolean =
        prefs(context).getBoolean("local_first_mode", false)

    fun setLocalFirstMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("local_first_mode", enabled).apply()
    }

    /** Compte un message répondu localement (sans passer par le cloud) -- voir
     *  ApiClient.sendChat. Affiché dans getTokenUsageReport pour rendre l'économie visible. */
    fun recordLocalFirstHit(context: Context) {
        val hits = prefs(context).getLong("token_usage_local_hits", 0L) + 1
        prefs(context).edit().putLong("token_usage_local_hits", hits).apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIMITE D'HISTORIQUE ENVOYÉ À L'IA (demande utilisateur : "réduire le nombre de
    // tokens pour réduire la consommation et utiliser les IA plus longtemps")
    // ═════════════════════════════════════════════════════════════════════════
    // ApiClient envoyait déjà au plus MAX_HISTORY_MESSAGES=16 messages (utilisateur+JARVIS
    // confondus) par requête, valeur figée dans le code — l'historique complet reste toujours
    // affiché dans le chat, seule la fenêtre transmise à l'IA était bornée. Rendu réglable ici
    // pour que l'utilisateur puisse lui-même arbitrer contexte-conservé vs tokens-consommés
    // (ex: "réduis l'historique envoyé à l'IA à 8 messages" en conversation) sans dépendre
    // d'un outil tiers non fiable pour "compresser les prompts" (voir échange sur OmniRoute,
    // écarté pour risques de sécurité réels : code obfusqué signalé, comptes GitHub multiples
    // quasi-identiques, CVE référencée).
    // Défaut abaissé 16 -> 8 (demande utilisateur répétée : "toujours +4000 tokens par
    // requête, réduis-les drastiquement") : avec CORE_SYSTEM_PROMPT déjà découpé en modules
    // à mot-clé, l'historique (dont la longueur de CHAQUE message, sans plafond individuel
    // avant ce correctif -- voir trimHistory ci-dessous) restait le plus gros poste variable
    // sur une conversation active. Le chat affiché reste, comme toujours, complet et
    // inchangé -- seule la fenêtre transmise à l'IA est réduite.
    private const val DEFAULT_MAX_HISTORY_MESSAGES = 8
    private const val MIN_HISTORY_MESSAGES = 4
    private const val MAX_HISTORY_MESSAGES_CAP = 60

    fun getMaxHistoryMessages(context: Context): Int =
        prefs(context).getInt("max_history_messages", DEFAULT_MAX_HISTORY_MESSAGES)

    /** Enregistre la nouvelle limite, bornée à [MIN_HISTORY_MESSAGES, MAX_HISTORY_MESSAGES_CAP]
     *  pour éviter un réglage inutilisable par erreur (0/négatif) ou sans effet réel (valeur
     *  énorme qui annule tout l'intérêt de la limite). Retourne la valeur réellement appliquée. */
    fun saveMaxHistoryMessages(context: Context, value: Int): Int {
        val clamped = value.coerceIn(MIN_HISTORY_MESSAGES, MAX_HISTORY_MESSAGES_CAP)
        prefs(context).edit().putInt("max_history_messages", clamped).apply()
        return clamped
    }

    // Mode compact (demande utilisateur : "~7500 tokens par message, c'est beaucoup trop",
    // puis "toujours +4000 tokens par requête, réduis-les drastiquement") : voir
    // ApiClient.buildSystemPrompt pour le détail de ce que ça change concrètement (modules
    // alwaysIf GitHub/Home Assistant/Box repassent en pur mot-clé, ajoutés seulement si le
    // message en parle). ACTIVÉ par défaut désormais (c'était désactivé par défaut au premier
    // ajout de ce réglage) : les mots-clés de ces trois modules couvrent déjà largement les
    // formulations naturelles ("allume la lumière", "github", "freebox"...), donc le risque
    // réel de rater une intégration pourtant configurée reste faible face au gain systématique
    // sur CHAQUE message pour qui a une/plusieurs intégrations actives. set_compact_mode{false}
    // reste disponible pour revenir à l'ancien comportement (toujours inclus) si besoin.
    fun isCompactPromptMode(context: Context): Boolean =
        prefs(context).getBoolean("compact_prompt_mode", true)

    fun setCompactPromptMode(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("compact_prompt_mode", enabled).apply()
    }

    // Taille max de la note "Mémoire JARVIS" réellement injectée dans le prompt (voir
    // ObsidianController.MAX_MEMORY_CHARS, historiquement figée à 4000 caractères = ~1000
    // tokens systématiquement possibles à CHAQUE message puisque cette note est relue EN
    // ENTIER sans condition de mot-clé). Réglable pour la même raison que l'historique
    // ci-dessus (ex: "réduis la mémoire à 1500 caractères"). Bornes larges car une "mémoire"
    // trop courte perd vite son utilité.
    // Défaut abaissé 4000 -> 1500 (même demande utilisateur que ci-dessus, "réduis
    // drastiquement") : ~1000 tokens systématiques à CHAQUE message ramenés à ~375 tokens,
    // sans rien retirer des faits déjà enregistrés -- seuls les plus ANCIENS (FIFO, voir
    // ObsidianController) sortent de la fenêtre injectée si la note dépasse la limite.
    private const val DEFAULT_MAX_MEMORY_CHARS = 1500
    private const val MIN_MEMORY_CHARS = 500
    private const val MAX_MEMORY_CHARS_CAP = 20_000

    fun getMaxMemoryChars(context: Context): Int =
        prefs(context).getInt("max_memory_chars", DEFAULT_MAX_MEMORY_CHARS)

    fun saveMaxMemoryChars(context: Context, value: Int): Int {
        val clamped = value.coerceIn(MIN_MEMORY_CHARS, MAX_MEMORY_CHARS_CAP)
        prefs(context).edit().putInt("max_memory_chars", clamped).apply()
        return clamped
    }

    // ─── OAuth Google (Agenda/Mail) ──────────────────────────────────────────
    // Porte depuis l'appli reecrite (voir GoogleAccountController/GoogleCalendarApiController/
    // GmailApiController) -- coexiste avec le systeme IMAP/SMTP existant de cette base : cette
    // base essaie d'abord le calendrier LOCAL (CalendarController, deja synchronise par Android)
    // et l'IMAP existant, puis se rabat sur l'API Google OAuth si l'utilisateur a lie un compte.

    /** ID client OAuth "Web application" (Google Cloud Console -- voir GoogleAccountController),
     *  requis comme serverClientId par Credential Manager. Ce n'est PAS un secret (contrairement
     *  au client secret, jamais utilise ici) -- Google le documente explicitement comme un
     *  identifiant public sans risque a embarquer dans une appli -- donc une valeur par defaut
     *  est acceptable ici. Reste modifiable dans Reglages si l'utilisateur cree son propre
     *  projet Cloud Console. */
    private const val DEFAULT_GOOGLE_WEB_CLIENT_ID =
        "253880913410-74a517f8fdmouu01hkojh01durm80236.apps.googleusercontent.com"

    fun getGoogleWebClientId(context: Context): String? {
        val saved = prefs(context).getString(KEY_GOOGLE_WEB_CLIENT_ID, null)
        return if (saved.isNullOrBlank()) DEFAULT_GOOGLE_WEB_CLIENT_ID else saved
    }

    fun setGoogleWebClientId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_GOOGLE_WEB_CLIENT_ID, id).apply()
    }

    /** Jeton d'acces OAuth Google (Gmail/Agenda), voir GoogleAccountController.requestAuthorization
     *  -- de courte duree de vie (~1h), on retient l'echeance pour savoir quand le redemander en
     *  silencieux plutot que de le reutiliser expire (l'API Google renverrait alors 401). */
    fun getGoogleAccessToken(context: Context): String? {
        val expiry = prefs(context).getLong(KEY_GOOGLE_ACCESS_TOKEN_EXPIRY, 0L)
        if (System.currentTimeMillis() >= expiry) return null
        return prefs(context).getString(KEY_GOOGLE_ACCESS_TOKEN, null)
    }

    fun setGoogleAccessToken(context: Context, token: String, expiresInSeconds: Long = 3300) {
        prefs(context).edit()
            .putString(KEY_GOOGLE_ACCESS_TOKEN, token)
            .putLong(KEY_GOOGLE_ACCESS_TOKEN_EXPIRY, System.currentTimeMillis() + expiresInSeconds * 1000)
            .apply()
    }

    /** Comptes Google lies (email + nom affiche) -- voir GoogleAccountController.LinkedAccount. */
    fun loadGoogleAccounts(context: Context): MutableList<GoogleAccountController.LinkedAccount> {
        val raw = prefs(context).getString(KEY_GOOGLE_ACCOUNTS, null) ?: return mutableListOf()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<GoogleAccountController.LinkedAccount>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                result.add(
                    GoogleAccountController.LinkedAccount(
                        obj.getString("email"),
                        obj.optString("displayName", "")
                    )
                )
            }
            result
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveGoogleAccounts(context: Context, accounts: List<GoogleAccountController.LinkedAccount>) {
        val array = JSONArray()
        accounts.forEach { acc ->
            val obj = JSONObject()
            obj.put("email", acc.email)
            obj.put("displayName", acc.displayName)
            array.put(obj)
        }
        prefs(context).edit().putString(KEY_GOOGLE_ACCOUNTS, array.toString()).apply()
    }

    /**
     * Email du compte Google dont le jeton est actuellement en cache (voir
     * getGoogleAccessToken/setGoogleAccessToken -- UN SEUL jeton a la fois, pas un par compte
     * lie). Sert uniquement a AFFICHER clairement a l'utilisateur quel compte parmi ceux lies
     * est actif pour Agenda/Mail.
     */
    fun getActiveGoogleAccountEmail(context: Context): String? =
        prefs(context).getString(KEY_GOOGLE_ACTIVE_ACCOUNT_EMAIL, null)

    fun setActiveGoogleAccountEmail(context: Context, email: String) {
        prefs(context).edit().putString(KEY_GOOGLE_ACTIVE_ACCOUNT_EMAIL, email).apply()
    }

    // --- Jetons OAuth PAR compte (email -> {token, expiry}) --------------------------------
    // Permet de LIRE (agenda, mails) simultanement sur tous les comptes dont le jeton est
    // encore valide, sans repasser par le selecteur systeme a chaque fois -- contourne la
    // limite "un seul compte par defaut a la fois" de l'API Google puisqu'on ne redemande
    // jamais un jeton pour un AUTRE compte que celui qui vient d'etre autorise.
    fun setGoogleAccessTokenForAccount(context: Context, email: String, token: String, expiresInSeconds: Long = 3300) {
        if (email.isBlank()) return
        val map = loadGoogleAccountTokensRaw(context)
        val entry = JSONObject()
        entry.put("token", token)
        entry.put("expiry", System.currentTimeMillis() + expiresInSeconds * 1000)
        map.put(email, entry)
        prefs(context).edit().putString(KEY_GOOGLE_ACCOUNT_TOKENS, map.toString()).apply()
    }

    /** Tous les jetons de compte encore valides (non expires), email -> jeton. */
    fun getAllValidGoogleAccountTokens(context: Context): Map<String, String> {
        val map = loadGoogleAccountTokensRaw(context)
        val now = System.currentTimeMillis()
        val result = mutableMapOf<String, String>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val email = keys.next()
            val entry = map.optJSONObject(email) ?: continue
            val expiry = entry.optLong("expiry", 0L)
            val token = entry.optString("token", "")
            if (token.isNotBlank() && now < expiry) result[email] = token
        }
        return result
    }

    private fun loadGoogleAccountTokensRaw(context: Context): JSONObject {
        val raw = prefs(context).getString(KEY_GOOGLE_ACCOUNT_TOKENS, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            JSONObject()
        }
    }

    // ─── Interne ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
