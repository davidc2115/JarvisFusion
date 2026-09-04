package com.jarvis.assistant

import android.content.Context
import android.media.MediaScannerConnection
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import java.io.File

object JarvisCommandParser {

    sealed class CommandResult {
        data class Executed(
            val outputMessage: String,
            val action: String,
            val isInformational: Boolean,
            val imageBase64: String? = null,
            val imageMime: String? = null
        ) : CommandResult()
        data class ExecutedMultiple(val results: List<Executed>) : CommandResult()
        object None : CommandResult()
    }

    // Canal temporaire pour faire remonter une image générée jusqu'au chat
    // (executeAction renvoie un simple texte ; l'image est déposée ici par
    // l'action generate_image puis consommée immédiatement après par
    // parseAndExecute, avant que la commande suivante ne s'exécute).
    private var pendingImageBase64: String? = null
    private var pendingImageMime: String? = null

    // Actions qui RENVOIENT une information à présenter (l'IA doit reformuler
    // naturellement le résultat). Les autres actions sont des confirmations
    // d'exécution (ex: "SMS envoyé") qui n'ont pas besoin d'être reformulées.
    // Remarque : search_contact_profile / list_contacts_by_category restent
    // ABSENTS de cette liste — la reformulation "naturelle/orale" (summarizeNaturally,
    // SANS markdown, EN prose) casserait la présentation visuelle en sections/emojis
    // d'une fiche contact. Leur formatage reste modifiable, mais via un mécanisme
    // DÉDIÉ (voir applyContactFormattingIfNeeded dans ApiClient.kt) qui ne s'active
    // QUE si une consigne de présentation (persistée ou ponctuelle) est réellement
    // présente — sinon l'affichage brut de PeopleController reste utilisé tel quel,
    // sans risque de perte/altération de données par un appel IA superflu.
    // today_events/upcoming_events/search_event RETIRÉS de cette liste (demande utilisateur,
    // captures d'écran à l'appui : "JARVIS ne conserve jamais cette présentation" pour le
    // planning) -- passer par summarizeNaturally reformule TOUJOURS en prose SANS mise en
    // forme (retours à la ligne, sections, emojis structurants), ce qui détruisait le format
    // déterministe groupé par jour produit par CalendarController.getEventsTimeRange/
    // searchEvents (voir leur commentaire). Même traitement que search_contact_profile/
    // list_contacts_by_category : l'affichage brut, déjà bien formaté en Kotlin, s'affiche
    // maintenant tel quel par défaut, sauf consigne de présentation explicite (voir
    // CALENDAR_FORMAT_MARKER plus bas) qui passe alors par applyMarkerFormatting.
    // Même liste/normalisation que PeopleController.GENERIC_CONTACT_QUERIES (privée dans ce
    // fichier-là) -- réutilisée ici pour que le garde-fou anti-requête-trop-générale s'applique
    // AUSSI au chemin search_contact -> PeopleController.searchContacts, avant même d'appeler
    // la fonction (évite un message d'erreur générique du contrôleur natif en repli).
    private val GENERIC_CONTACT_QUERIES = setOf(
        "contact", "contacts", "fiche", "fiches", "personne", "personnes", "carnet"
    )

    private fun normalizeGenericQuery(name: String): String =
        java.text.Normalizer.normalize(name.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private val INFORMATIONAL_ACTIONS = setOf(
        "list_files", "search_files", "read_file", "storage_info",
        "list_calendars",
        "read_sms", "read_unread_sms", "search_sms", "recent_calls",
        "read_emails", "read_unread_emails", "search_email", "read_email_content",
        "get_notifications", "bluetooth_info", "wifi_info",
        "web_search", "get_location", "list_contact_labels", "list_contacts_by_label",
        "github_list_repos", "github_read_file", "github_list_contents", "github_list_accounts", "github_test_access", "list_generations",
        "perplexity_search", "firecrawl_scrape", "run_glif",
        "termux_sd_setup", "termux_sd_status", "refresh_all_contacts", "read_debug_logs", "token_usage",
        "list_contact_templates", "get_history_limit", "get_compact_mode", "get_memory_limit",
        "get_local_first_mode"
    )

    // Fait correspondre les mots-clés que l'utilisateur/l'IA peuvent employer (« pdf »,
    // « word », « le fichier excel »...) aux vraies valeurs de type stockées dans
    // Prefs.GenerationRecord, pour retrouver un fichier déjà généré sans connaître son
    // chemin exact — condition sine qua non pour que "ouvre/imprime le PDF que tu as créé"
    // fonctionne même si la création a eu lieu dans un tour de conversation antérieur.
    private val GENERATION_TYPE_ALIASES: Map<String, Set<String>> = mapOf(
        "pdf" to setOf("file_pdf"),
        "docx" to setOf("file_docx"), "word" to setOf("file_docx"), "doc" to setOf("file_docx"),
        "xlsx" to setOf("file_xlsx"), "excel" to setOf("file_xlsx"), "tableur" to setOf("file_xlsx"),
        "zip" to setOf("file_zip"), "archive" to setOf("file_zip"),
        "image" to setOf("image"), "photo" to setOf("image"), "img" to setOf("image"),
        "video" to setOf("video"), "vidéo" to setOf("video"),
        "chart" to setOf("chart"), "graphique" to setOf("chart"),
        "website" to setOf("website", "website_edit"), "site" to setOf("website", "website_edit")
    )

    /**
     * Retrouve le chemin du dernier fichier généré avec succès correspondant à [typeHint]
     * (ex: "pdf", "excel"...) — ou toute génération réussie si [typeHint] est vide/inconnu.
     * Utilisé en repli par open_file/print_file quand aucun chemin exact n'est fourni.
     */
    private fun findRecentGenerationPath(context: Context, typeHint: String): String? {
        val types = GENERATION_TYPE_ALIASES[typeHint.trim().lowercase()]
        return Prefs.getGenerationHistory(context)
            .firstOrNull { rec ->
                rec.status == "success" && !rec.resultPath.isNullOrBlank() &&
                    (types == null || rec.type in types)
            }
            ?.resultPath
    }

    /**
     * Exécute une ou plusieurs commandes trouvées dans la réponse de l'IA.
     * Plusieurs blocs [JARVIS_CMD:...] peuvent apparaître dans une seule
     * réponse — utile par exemple pour créer un projet GitHub complet
     * (plusieurs fichiers) en une seule fois.
     */
    private data class JarvisCmdMatch(val payload: String, val fullStart: Int, val fullEnd: Int)

    /**
     * Trouve les blocs [JARVIS_CMD:...] d'une réponse IA en COMPTANT les crochets imbriqués
     * (en ignorant ceux à l'intérieur des chaînes JSON, échappements compris) pour repérer le
     * VRAI crochet fermant du bloc.
     *
     * BUG RÉEL CORRIGÉ : l'ancien regex non-gourmand "\[JARVIS_CMD:(.*?)\]" s'arrêtait au TOUT
     * PREMIER "]" rencontré — qui est souvent celui d'un tableau interne au payload lui-même
     * (ex: images:[...] pour create_pdf/create_docx). Le JSON capturé était alors tronqué en
     * plein milieu ("Unterminated array"), cassant l'action entière dès qu'une commande
     * contenait son propre tableau — exactement le symptôme signalé sur create_pdf{images}.
     * Utilisée à la fois par parseAndExecute (exécution) et cleanResponse (nettoyage de
     * l'affichage) pour ne jamais dupliquer cette logique délicate à deux endroits différents.
     */
    private fun findJarvisCommands(text: String): List<JarvisCmdMatch> {
        val marker = "[JARVIS_CMD:"
        val results = mutableListOf<JarvisCmdMatch>()
        var searchFrom = 0
        while (true) {
            val start = text.indexOf(marker, searchFrom)
            if (start < 0) break
            val contentStart = start + marker.length
            var depth = 1 // le crochet ouvrant du marker lui-même
            var inString = false
            var escaped = false
            var i = contentStart
            var end = -1
            while (i < text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else {
                    when (c) {
                        '"' -> inString = true
                        '[' -> depth++
                        ']' -> {
                            depth--
                            if (depth == 0) end = i
                        }
                    }
                }
                if (end != -1) break
                i++
            }
            if (end == -1) break // bloc non terminé (réponse tronquée par le fournisseur) — on abandonne, pas de crash
            results.add(JarvisCmdMatch(text.substring(contentStart, end), start, end + 1))
            searchFrom = end + 1
        }
        return results
    }

    suspend fun parseAndExecute(context: Context, llmResponse: String): CommandResult {
        val matches = findJarvisCommands(llmResponse)
        if (matches.isEmpty()) return CommandResult.None

        val results = matches.mapNotNull { match ->
            val jsonStr = match.payload.trim()
            // action extrait AVANT le try/catch de l'exécution (pas juste du parsing JSON) pour
            // pouvoir l'inclure dans DiagnosticsLog même si executeAction lève une exception —
            // sans quoi une action qui plante ne laisserait aucune trace de LAQUELLE, seulement
            // le message d'erreur générique affiché une fois puis perdu.
            var action = ""
            try {
                val json = JSONObject(jsonStr)
                action = json.optString("action", "").lowercase()
                val resultText = executeAction(context, action, json)
                val img = pendingImageBase64
                val mime = pendingImageMime
                pendingImageBase64 = null
                pendingImageMime = null
                CommandResult.Executed(resultText, action, action in INFORMATIONAL_ACTIONS, img, mime)
            } catch (e: JSONException) {
                // Bloc [JARVIS_CMD:...] repéré (crochets équilibrés) mais JSON invalide À
                // L'INTÉRIEUR (guillemets manquants, virgule en trop...) — typiquement un
                // modèle IA LOCAL plus limité qui rate le format exact, pas une vraie erreur
                // système. Signalé par l'utilisateur : le message technique Java brut
                // ("Value ... of type java.lang.String cannot be converted to JSONObject")
                // s'affichait tel quel dans le chat comme si JARVIS avait planté. On journalise
                // pour debug et on ignore silencieusement CE bloc : le reste de la réponse
                // (texte hors bloc, déjà nettoyé par cleanResponse côté appelant) continue de
                // s'afficher normalement, sans jargon technique visible par l'utilisateur.
                DiagnosticsLog.log(context, "JARVIS_CMD", "Bloc [JARVIS_CMD] mal formé ignoré (JSON invalide, probablement IA locale) : ${e.message}")
                null
            } catch (e: Exception) {
                DiagnosticsLog.logError(context, "JARVIS_CMD", "Action « $action » — exception : ${e.javaClass.simpleName} ${e.message}")
                CommandResult.Executed("❌ Erreur d'exécution de la commande système : ${e.message}", "", false)
            }
        }

        if (results.isEmpty()) {
            // TOUS les blocs [JARVIS_CMD] détectés étaient du JSON invalide (voir ci-dessus).
            // On retombe sur le texte restant déjà nettoyé plutôt que sur CommandResult.None,
            // qui aurait réaffiché la réponse BRUTE (donc le bloc cassé visible) côté appelant.
            val remainder = cleanResponse(llmResponse)
            return if (remainder.isBlank())
                CommandResult.Executed("🤔 Je n'ai pas réussi à formuler ma réponse correctement, tu peux reformuler ?", "", isInformational = false)
            else
                CommandResult.Executed("", "", isInformational = false)
        }

        return if (results.size == 1) results[0] else CommandResult.ExecutedMultiple(results)
    }

    private suspend fun executeAction(context: Context, action: String, json: JSONObject): String {
        return when (action) {
            "call" -> {
                val target = json.optString("target", "").ifBlank { json.optString("contact", "") }
                if (target.isBlank()) "❌ Aucun destinataire d'appel spécifié."
                else PhoneController.makeCall(context, target)
            }
            "end_call" -> PhoneController.endCall(context)
            "recent_calls" -> PhoneController.getRecentCalls(context, json.optInt("count", 10))

            "send_sms" -> {
                val to = json.optString("to", "").ifBlank { json.optString("contact", "") }
                val body = json.optString("message", "").ifBlank { json.optString("body", "") }
                if (to.isBlank() || body.isBlank()) "❌ Destinataire ou message SMS manquant."
                else SmsController.sendSms(context, to, body)
            }
            "read_sms" -> SmsController.readInboxSms(context, json.optInt("count", 5))
            "search_sms" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun mot-clé de recherche fourni."
                else SmsController.searchSms(context, query, json.optInt("count", 10))
            }
            "read_unread_sms" -> SmsController.readUnreadSms(context)

            // BUG RÉEL CORRIGÉ (signalement utilisateur : affichage incohérent, "il m'affiche les
            // fiches contenant contact" tantôt bien mises en forme tantôt "en vrac") : cette
            // action passait TOUJOURS par ContactsController (carnet Android natif, simple liste
            // plate) au lieu de la fiche riche du vault (PeopleController.formatFullDetails,
            // identique à search_contact_profile) -- selon que l'IA appelait search_contact ou
            // search_contact_profile pour une même demande utilisateur, le résultat affiché
            // n'avait RIEN à voir. On donne maintenant systématiquement la priorité à la fiche
            // du vault ; le carnet natif ne sert plus que de repli si aucune fiche n'existe.
            "search_contact" -> {
                val name = json.optString("name", "").ifBlank { json.optString("query", "") }
                if (name.isBlank()) {
                    withContactPresentationStyleNote(context, PeopleController.listByCategory(context, ""), "")
                } else if (normalizeGenericQuery(name) in GENERIC_CONTACT_QUERIES) {
                    "❌ « $name » est trop général pour identifier un contact précis — donne un nom, un numéro, un email ou une adresse."
                } else {
                    val ficheResult = PeopleController.searchContacts(context, name)
                    val ficheFound = !ficheResult.startsWith("🔍 Aucun") && !ficheResult.startsWith("❌")
                    if (ficheFound) {
                        PeopleController.getLatestImageAttachment(context, name)?.let { (b64, mime) ->
                            pendingImageBase64 = b64
                            pendingImageMime = mime
                        }
                        withContactPresentationStyleNote(context, ficheResult, "")
                    } else {
                        ContactsController.searchContacts(context, name)
                    }
                }
            }
            "list_contact_labels" -> ContactsController.listAllLabels(context)
            "list_contacts_by_label" -> {
                val label = json.optString("label", "")
                if (label.isBlank()) "❌ Précise le libellé à rechercher."
                else ContactsController.listContactsByLabel(context, label)
            }
            "add_contact" -> {
                val name = json.optString("name", "")
                val phone = json.optString("phone", "")
                val email = json.optString("email", "")
                if (name.isBlank() || phone.isBlank()) "❌ Nom ou numéro manquant pour le contact."
                else ContactsController.addContact(context, name, phone, email)
            }

            "play_music" -> {
                val query = json.optString("query", "")
                MediaController.playMusic(context, query)
            }
            "pause_music" -> MediaController.pauseMusic(context)
            "resume_music" -> MediaController.resumeMusic(context)
            "stop_music" -> MediaController.stopMusic(context)
            "next_track" -> MediaController.nextTrack(context)
            "set_volume" -> {
                val level = json.optInt("level", 5)
                MediaController.setVolume(context, level)
            }

            // BUG RÉEL CORRIGÉ : withCalendarPresentationStyleNote existait déjà (constante
            // CALENDAR_FORMAT_MARKER, Prefs, action set_calendar_presentation_style...) mais
            // n'était en fait JAMAIS appelée ici -- un style personnalisé enregistré par
            // l'utilisateur pour son planning n'avait donc strictement aucun effet.
            //
            // BUG RÉEL CORRIGÉ (signalement utilisateur : "je demande un planning en
            // particulier, il me répond calendrier null introuvable") : le paramètre
            // "calendar" n'était filtré qu'avec .ifBlank { null } -- si l'IA (surtout un
            // modèle plus limité) écrivait littéralement la chaîne "calendar":"null" au lieu
            // d'omettre le champ (schéma classique déjà connu, voir BLANK_PLACEHOLDER_VALUES/
            // cleanOptionalField utilisés pour les fiches contact), cette chaîne n'était PAS
            // vide donc passait telle quelle -- CalendarController cherchait alors un vrai
            // calendrier nommé "null", ne le trouvait jamais, et affichait littéralement
            // "Calendrier « null » introuvable." Les 7 usages de "calendar" dans les actions
            // agenda passent maintenant par cleanOptionalField, qui filtre aussi "null"/
            // "aucun"/"n/a"/etc. comme pour les autres champs optionnels.
            "today_events" -> withCalendarPresentationStyleNote(context, CalendarController.getTodayEvents(context, cleanOptionalField(json.optString("calendar", ""))))
            // offsetDays (nouveau) : décale le DÉBUT de la plage en jours calendaires entiers
            // par rapport à aujourd'hui -- 0=aujourd'hui (défaut, comportement inchangé),
            // 1=demain, 2=après-demain... BUG RÉEL CORRIGÉ (signalement utilisateur : "demain",
            // "à partir de demain" mal compris) : sans ce paramètre, aucune action ne permettait
            // de cibler un jour précis autre qu'aujourd'hui ; l'IA n'avait alors aucun moyen
            // fiable de répondre à ces demandes.
            "upcoming_events" -> withCalendarPresentationStyleNote(context, CalendarController.getUpcomingEvents(context, json.optInt("days", 7), cleanOptionalField(json.optString("calendar", "")), json.optInt("offsetDays", 0)))
            "week_events" -> withCalendarPresentationStyleNote(context, CalendarController.getEventsForWeek(context, json.optInt("offset", 0), cleanOptionalField(json.optString("calendar", ""))))
            "create_event" -> {
                val title = json.optString("title", "Événement")
                val dateStr = json.optString("date", "")
                val timeStr = json.optString("time", "")
                val durationMinutes = json.optInt("durationMinutes", 60).coerceAtLeast(1)
                val desc = json.optString("description", "")
                val loc = json.optString("location", "")
                val calendarRef = cleanOptionalField(json.optString("calendar", ""))

                // BUG RÉEL CORRIGÉ : create_event exigeait auparavant que le modèle calcule
                // lui-même des epoch millisecondes (startTime/endTime) pour "demain à 14h" —
                // exactement le genre de calcul de date que les LLM ratent régulièrement (pas
                // de connaissance fiable de "aujourd'hui", arithmétique d'epoch peu fiable).
                // Même principe déjà appliqué à getEventsForWeek (voir son commentaire) : le
                // modèle fournit maintenant date="demain"/"lundi"/"15/03"/... et time="14h30"
                // en LANGAGE, et c'est CalendarController.resolveDate/resolveTime — calculé à
                // partir de l'horloge RÉELLE de l'appareil — qui fait le calcul, jamais le LLM.
                val start: Long
                val end: Long
                if (json.has("startTime")) {
                    // Compatibilité avec d'anciens appels qui fourniraient encore des epoch
                    // millis directement — honorés tels quels, mais plus documentés/encouragés
                    // dans le prompt système désormais.
                    start = json.optLong("startTime", System.currentTimeMillis() + 3600000)
                    end = json.optLong("endTime", start + durationMinutes * 60000L)
                } else if (dateStr.isBlank() && timeStr.isBlank()) {
                    // Ni date ni heure précisée : comportement historique par défaut, dans 1h.
                    start = System.currentTimeMillis() + 3600000
                    end = start + durationMinutes * 60000L
                } else {
                    val cal = CalendarController.resolveDate(dateStr)
                    CalendarController.resolveTime(timeStr, cal)
                    start = cal.timeInMillis
                    end = start + durationMinutes * 60000L
                }

                CalendarController.createEvent(context, title, start, end, desc, loc, calendarRef)
            }
            "list_calendars" -> CalendarController.getCalendarList(context)
            "name_calendar" -> {
                // "calendar" accepte un ID, un nom affiché, ou un compte (email) — pas besoin
                // d'appeler list_calendars avant. "calendarId" reste accepté pour compatibilité.
                val calendarRef = cleanOptionalField(json.optString("calendar", "")) ?: run {
                    val legacyId = json.optLong("calendarId", -1)
                    if (legacyId != -1L) legacyId.toString() else null
                }
                val nickname = json.optString("nickname", "")
                if (calendarRef.isNullOrBlank() || nickname.isBlank()) "❌ Calendrier ou surnom manquant. Précise le nom affiché du calendrier, son compte (email), ou son ID (via list_calendars)."
                else CalendarController.nameCalendar(context, calendarRef, nickname)
            }
            "reset_calendar_nicknames" -> CalendarController.resetCalendarNicknames(context)
            "sync_calendar" -> {
                val calendarRef = cleanOptionalField(json.optString("calendar", ""))
                if (calendarRef == null) "❌ Précise quel calendrier synchroniser (nom, compte, ID — voir list_calendars)."
                else CalendarController.syncCalendar(context, calendarRef, json.optBoolean("enable", true))
            }
            "set_default_calendar" -> {
                val calendarRef = cleanOptionalField(json.optString("calendar", ""))
                if (calendarRef == null) "❌ Précise quel calendrier utiliser par défaut (nom, compte, surnom ou ID — voir list_calendars)."
                else CalendarController.setDefaultCalendar(context, calendarRef)
            }
            "reset_default_calendar" -> CalendarController.resetDefaultCalendar(context)

            // Mail : IMAP/SMTP (mot de passe d'application) reste le chemin PAR DEFAUT ---
            // repli sur l'API Gmail OAuth (voir GmailApiController/GoogleAccountController)
            // UNIQUEMENT si aucun compte IMAP n'est configure ET qu'un compte Google est deja
            // lie/autorise (voir Reglages > Systeme > Compte(s) Google) -- greffe de
            // l'integration OAuth actuelle (taches #247-249, demande explicite de
            // l'utilisateur de la garder en plus du systeme IMAP/SMTP existant).
            "read_emails" -> {
                if (Prefs.getDefaultEmailAccount(context) == null) {
                    val token = Prefs.getGoogleAccessToken(context)
                    if (token != null) GmailApiController.readInbox(token, json.optInt("count", 5))
                    else EmailController.readInbox(context, json.optInt("count", 5))
                } else EmailController.readInbox(context, json.optInt("count", 5))
            }
            "read_unread_emails" -> {
                if (Prefs.getDefaultEmailAccount(context) == null) {
                    val token = Prefs.getGoogleAccessToken(context)
                    if (token != null) GmailApiController.readUnread(token)
                    else EmailController.readUnread(context)
                } else EmailController.readUnread(context)
            }
            "search_email" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun mot-clé de recherche fourni."
                else if (Prefs.getDefaultEmailAccount(context) == null) {
                    val token = Prefs.getGoogleAccessToken(context)
                    if (token != null) GmailApiController.searchEmails(token, query)
                    else EmailController.searchEmails(context, query)
                } else EmailController.searchEmails(context, query)
            }
            "read_email_content" -> EmailController.readEmailContent(context, json.optInt("index", 1))
            "send_email" -> {
                val to = json.optString("to", "")
                val subject = json.optString("subject", "")
                val body = json.optString("body", "")
                if (to.isBlank()) "❌ Adresse email destinataire manquante."
                else if (Prefs.getDefaultEmailAccount(context) == null) {
                    val token = Prefs.getGoogleAccessToken(context)
                    if (token != null) GmailApiController.sendEmail(token, to, subject, body)
                    else EmailController.sendEmail(context, to, subject, body)
                } else EmailController.sendEmail(context, to, subject, body)
            }

            "list_files" -> {
                val path = json.optString("path", "/sdcard")
                StorageController.listFiles(context, path)
            }
            "search_files" -> {
                val query = json.optString("query", "")
                StorageController.searchFiles(context, query)
            }
            "read_file" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin de fichier manquant."
                else StorageController.readTextFile(context, path)
            }
            "write_file" -> {
                val path = json.optString("path", "")
                val content = json.optString("content", "")
                if (path.isBlank()) "❌ Chemin de fichier manquant."
                else StorageController.writeTextFile(context, path, content)
            }
            "rename_file" -> {
                val oldPath = json.optString("oldPath", "").ifBlank { json.optString("path", "") }
                val newName = json.optString("newName", "").ifBlank { json.optString("newPath", "") }
                if (oldPath.isBlank() || newName.isBlank()) "❌ Ancien ou nouveau nom manquant."
                else StorageController.renameFile(context, oldPath, newName)
            }
            "copy_file" -> {
                val src = json.optString("source", "").ifBlank { json.optString("src", "") }
                val dest = json.optString("dest", "").ifBlank { json.optString("destination", "") }
                if (src.isBlank() || dest.isBlank()) "❌ Source ou destination manquante."
                else StorageController.copyFile(context, src, dest)
            }
            "move_file" -> {
                val src = json.optString("source", "").ifBlank { json.optString("src", "") }
                val dest = json.optString("dest", "").ifBlank { json.optString("destination", "") }
                if (src.isBlank() || dest.isBlank()) "❌ Source ou destination manquante."
                else StorageController.moveFile(context, src, dest)
            }
            "delete_file" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin de fichier manquant."
                else StorageController.deleteFile(context, path)
            }
            "create_folder" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Chemin de dossier manquant."
                else StorageController.createFolder(context, path)
            }
            "storage_info" -> StorageController.getStorageInfo(context)

            "get_notifications" -> JarvisNotificationListenerService.getRecent(json.optInt("count", 5))

            "get_location" -> {
                var res = ""
                LocationController.getLastKnownLocation(context) { res = it }
                res.ifBlank { "📍 Recherche de localisation lancée..." }
            }
            "open_maps" -> {
                val query = json.optString("query", "")
                LocationController.openMaps(context, query)
            }

            "bluetooth_info" -> BluetoothController.getPairedDevices(context)
            "enable_bluetooth" -> BluetoothController.enableBluetooth(context)
            "disable_bluetooth" -> BluetoothController.disableBluetooth(context)

            "wifi_info" -> WifiController.getWifiInfo(context)
            "enable_wifi" -> WifiController.enableWifi(context)
            "disable_wifi" -> WifiController.disableWifi(context)

            "flashlight_on" -> DeviceControlController.setFlashlight(context, true)
            "flashlight_off" -> DeviceControlController.setFlashlight(context, false)
            "set_alarm" -> {
                val hour = json.optInt("hour", -1)
                val minute = json.optInt("minute", 0)
                val message = json.optString("message", "")
                val daysOfWeek = mutableListOf<Int>()
                json.optJSONArray("daysOfWeek")?.let { arr -> for (i in 0 until arr.length()) daysOfWeek.add(arr.optInt(i)) }
                if (hour < 0) "❌ Précise l'heure du réveil (hour, 0-23)."
                else DeviceControlController.setAlarm(context, hour, minute, message, daysOfWeek)
            }
            "show_alarms" -> DeviceControlController.showAlarms(context)
            "set_timer" -> {
                val seconds = json.optInt("seconds", -1)
                val minutes = json.optInt("minutes", -1)
                val totalSeconds = when {
                    seconds > 0 -> seconds
                    minutes > 0 -> minutes * 60
                    else -> -1
                }
                val message = json.optString("message", "")
                if (totalSeconds <= 0) "❌ Précise une durée pour le minuteur (minutes ou seconds)."
                else DeviceControlController.setTimer(context, totalSeconds, message)
            }

            "web_search" -> {
                val query = json.optString("query", "")
                WebSearchController.search(context, query)
            }

            // ─── Intégrations distantes (Perplexity / Firecrawl / Glif) ───────────────
            // Voir Prefs.getApiKeysFor(Provider.PERPLEXITY)/getFirecrawlApiKey/getGlifApiToken : clés saisies
            // uniquement par l'utilisateur dans ⚙ → Clés API, jamais codées en dur (dépôt public).
            "perplexity_search" -> {
                val query = json.optString("query", "")
                val r = PerplexityController.search(context, query)
                r.message
            }
            "firecrawl_scrape" -> {
                val url = json.optString("url", "")
                if (url.isBlank()) "❌ Précise l'URL de la page à lire."
                else FirecrawlController.scrape(context, url).message
            }
            "run_glif" -> {
                val prompt = json.optString("prompt", "").ifBlank { json.optString("input", "") }
                val continueProjectId = json.optString("projectId", "").ifBlank { null }
                GlifController.composeProject(context, prompt, continueProjectId).message
            }

            // ─── Stable Diffusion local via Termux (opt-in, voir TermuxController) ────
            "termux_sd_setup" -> TermuxController.setupAndLaunch(context).message
            "termux_sd_status" -> TermuxController.checkWebuiStatus(context).message

            // ─── Journal de diagnostics (échecs de cascade IA/image/commandes, voir
            // DiagnosticsLog) — JARVIS n'a aucun accès distant au téléphone, ce journal
            // consultable en conversation est la façon honnête de "voir les logs".
            "read_debug_logs" -> DiagnosticsLog.readRecent(context)
            "clear_debug_logs" -> DiagnosticsLog.clear(context)
            // Fichier .txt (journal complet, pas juste les 60 dernières lignes comme
            // read_debug_logs) que l'utilisateur peut envoyer en un geste (email, Drive, ou
            // directement ici en pièce jointe) au lieu de copier/coller du texte tronqué.
            "export_debug_logs" -> {
                val result = FileGenController.exportDebugLogs(context)
                logFileRecord(context, "logs", "logs_JARVIS", result.success, result.filePath, result.message)
                result.message
            }
            "share_file" -> {
                val path = json.optString("path", "").ifBlank {
                    findRecentGenerationPath(context, json.optString("type", ""))
                }
                if (path.isNullOrBlank()) "❌ Aucun fichier correspondant trouvé à partager. Précise le chemin exact ou utilise d'abord export_debug_logs/list_generations."
                else FileGenController.shareFile(context, path)
            }
            // Demande explicite utilisateur : que les logs soient récupérables DIRECTEMENT,
            // sans étape manuelle côté téléphone. JARVIS n'a aucun accès réseau entrant vers
            // le téléphone (impossible d'ouvrir une connexion distante), mais peut POUSSER le
            // journal complet vers un Gist GitHub privé (réutilise le jeton déjà configuré
            // dans ⚙ → Clés API → Codage GitHub) — envoyé automatiquement à chaque erreur
            // système réelle (voir DiagnosticsLog.logError), et ici sur demande explicite pour
            // forcer un envoi immédiat sans attendre une prochaine erreur.
            "upload_logs_to_github" -> GitHubController.uploadLogs(context, DiagnosticsLog.readAll(context))
            "token_usage" -> Prefs.getTokenUsageReport(context)
            "clear_token_usage" -> { Prefs.clearTokenUsage(context); "✅ Compteur de tokens réinitialisé." }
            // Limite d'historique envoyé à l'IA (voir Prefs.getMaxHistoryMessages/ApiClient.trimHistory,
            // demande utilisateur : "réduire le nombre de tokens pour utiliser les IA plus longtemps").
            // Config en conversation, comme le reste de l'app — jamais de formulaire dédié.
            "set_history_limit" -> {
                val requested = json.optInt("count", -1)
                if (requested <= 0) {
                    "❌ Précise un nombre de messages (ex : \"réduis l'historique à 8 messages\")."
                } else {
                    val applied = Prefs.saveMaxHistoryMessages(context, requested)
                    val note = if (applied != requested) " (ajusté entre 4 et 60)" else ""
                    "✅ Historique envoyé à l'IA limité à $applied message(s)$note. Moins de messages passés " +
                        "= moins de tokens envoyés à chaque requête, mais JARVIS se souvient de moins de " +
                        "contexte récent dans la conversation — utilise token_usage pour voir l'effet réel."
                }
            }
            "get_history_limit" -> "📊 Historique envoyé à l'IA à chaque requête : ${Prefs.getMaxHistoryMessages(context)} message(s) (utilisateur+JARVIS confondus). L'historique affiché dans le chat n'est jamais tronqué, seule cette fenêtre transmise à l'IA l'est."
            // Mode compact (voir ApiClient.buildSystemPrompt, demande utilisateur : "~7500
            // tokens par message, c'est beaucoup trop") : désactive l'ajout automatique des
            // modules GitHub/Home Assistant/Box internet quand une intégration est configurée
            // (ils repassent en pur mot-clé, comme tous les autres modules).
            "set_compact_mode" -> {
                val enabled = json.optBoolean("enabled", true)
                Prefs.setCompactPromptMode(context, enabled)
                if (enabled) {
                    "✅ Mode compact activé : GitHub/Home Assistant/Box internet ne sont plus ajoutés " +
                        "automatiquement au prompt, seulement si le message en cours en parle. Économise des " +
                        "tokens à chaque requête, au prix d'un risque (rare) d'oubli d'une intégration si aucun " +
                        "mot-clé pertinent n'apparaît."
                } else {
                    "✅ Mode compact désactivé : GitHub/Home Assistant/Box internet redeviennent ajoutés " +
                        "automatiquement dès qu'ils sont configurés, comme avant."
                }
            }
            "get_compact_mode" -> if (Prefs.isCompactPromptMode(context)) "📊 Mode compact : activé (réglage par défaut)." else "📊 Mode compact : désactivé."
            // "IA locale d'abord" (voir Prefs.isLocalFirstMode/ApiClient.readyLocalProviderForFirstTry,
            // demande utilisateur : "passer par IA locale et cloud" pour réduire la consommation
            // de tokens). Désactivé par défaut : quand actif, chaque message est d'abord tenté
            // GRATUITEMENT sur le modèle embarqué (Gemini Nano/Qwen local) si prêt ; en cas
            // d'échec ou si le modèle local ne peut pas répondre (message <<ESCALATE_CLOUD>>,
            // jamais montré tel quel), bascule silencieuse et automatique sur le cloud habituel.
            "set_local_first_mode" -> {
                val enabled = json.optBoolean("enabled", true)
                Prefs.setLocalFirstMode(context, enabled)
                if (enabled) {
                    "✅ IA locale d'abord activée : chaque message est d'abord tenté gratuitement sur le " +
                        "modèle embarqué (Gemini Nano ou modèle Qwen local, selon ce qui est prêt sur cet " +
                        "appareil) — le cloud n'est sollicité que si le modèle local ne peut pas répondre. " +
                        "Nécessite un modèle local déjà prêt (⚙ Réglages → Local) pour avoir un effet réel."
                } else {
                    "✅ IA locale d'abord désactivée : chaque message repart directement sur le fournisseur " +
                        "cloud configuré, comme avant."
                }
            }
            "get_local_first_mode" -> if (Prefs.isLocalFirstMode(context)) "📊 IA locale d'abord : activée." else "📊 IA locale d'abord : désactivée (réglage par défaut)."
            // Taille de la note "Mémoire JARVIS" injectée à CHAQUE message (voir
            // Prefs.getMaxMemoryChars/ObsidianController.trimMemoryIfNeeded).
            "set_memory_limit" -> {
                val requested = json.optInt("chars", -1)
                if (requested <= 0) {
                    "❌ Précise une taille en caractères (ex : \"réduis la mémoire à 1500 caractères\")."
                } else {
                    val applied = Prefs.saveMaxMemoryChars(context, requested)
                    val note = if (applied != requested) " (ajusté entre 500 et 20000)" else ""
                    "✅ Mémoire JARVIS limitée à $applied caractère(s)$note. Les entrées les plus anciennes " +
                        "sont retirées en premier si la limite est dépassée — rien n'est perdu tant que ça tient dedans."
                }
            }
            "get_memory_limit" -> "📊 Limite actuelle de la mémoire JARVIS : ${Prefs.getMaxMemoryChars(context)} caractères (~${Prefs.getMaxMemoryChars(context) / 4} tokens max injectés à chaque message)."

            "delete_event" -> {
                val eventId = json.optLong("eventId", -1)
                if (eventId == -1L) "❌ Identifiant d'événement manquant."
                else CalendarController.deleteEvent(context, eventId)
            }
            "update_event" -> {
                val eventId = json.optLong("eventId", -1)
                if (eventId == -1L) {
                    "❌ Identifiant d'événement manquant."
                } else {
                    // Même correctif que create_event : newDate/newTime en langage naturel
                    // ("demain", "14h30"...) plutôt que de faire calculer un epoch millis au
                    // LLM — voir CalendarController.resolveDate/resolveTime. newStartTime/
                    // newEndTime restent acceptés tels quels pour compatibilité.
                    val newDateStr = json.optString("newDate", "")
                    val newTimeStr = json.optString("newTime", "")
                    var newStart: Long? = if (json.has("newStartTime")) json.optLong("newStartTime") else null
                    var newEnd: Long? = if (json.has("newEndTime")) json.optLong("newEndTime") else null
                    if (newStart == null && (newDateStr.isNotBlank() || newTimeStr.isNotBlank())) {
                        val cal = CalendarController.resolveDate(newDateStr)
                        CalendarController.resolveTime(newTimeStr, cal)
                        newStart = cal.timeInMillis
                        if (newEnd == null) {
                            val durationMinutes = json.optInt("newDurationMinutes", 60).coerceAtLeast(1)
                            newEnd = newStart + durationMinutes * 60000L
                        }
                    }
                    CalendarController.updateEvent(
                        context,
                        eventId,
                        newTitle = json.optString("newTitle", "").ifBlank { null },
                        newStartTimeMillis = newStart,
                        newEndTimeMillis = newEnd,
                        newDescription = json.optString("newDescription", "").ifBlank { null },
                        newLocation = json.optString("newLocation", "").ifBlank { null }
                    )
                }
            }
            "search_event" -> {
                val query = json.optString("query", "")
                withCalendarPresentationStyleNote(context, CalendarController.searchEvents(context, query, cleanOptionalField(json.optString("calendar", ""))))
            }

            "create_client_from_event" -> {
                val eventId = json.optLong("eventId", -1)
                if (eventId == -1L) "❌ Identifiant d'événement manquant (cherche-le d'abord via search_event/today_events)."
                else {
                    val event = CalendarController.getEventDetails(context, eventId)
                    if (event == null) "❌ Événement introuvable."
                    else PeopleController.createClientFromEvent(context, event, json.optString("name", "").ifBlank { null })
                }
            }
            "add_client_visit" -> {
                val name = json.optString("name", "")
                val note = json.optString("note", "").ifBlank { json.optString("visit", "") }
                if (name.isBlank() || note.isBlank()) "❌ Nom du client ou détail du rendez-vous manquant."
                else PeopleController.addVisit(context, name, note)
            }
            "export_clients_kml" -> {
                val category = json.optString("category", "client")
                KmlExportController.exportToKml(context, category).message
            }

            "github_list_repos" -> GitHubController.listRepos(context, json.optString("account", ""))

            "github_list_accounts" -> GitHubController.listAccounts(context)
            "github_test_access" -> GitHubController.testAccess(
                context, json.optString("owner", ""), json.optString("repo", ""), json.optString("account", "")
            )

            "github_add_account" -> {
                val label = json.optString("label", "")
                val token = json.optString("token", "")
                if (label.isBlank() || token.isBlank()) "❌ Précise un libellé (ex: \"perso\", \"pro\") et le jeton d'accès personnel GitHub."
                else {
                    Prefs.addGithubAccount(context, Prefs.GitHubAccount(label = label, token = token))
                    "✅ Compte GitHub « $label » ajouté."
                }
            }

            "github_remove_account" -> {
                val label = json.optString("label", "")
                val account = Prefs.findGithubAccount(context, label)
                if (account == null) "❌ Aucun compte GitHub trouvé pour « $label »."
                else {
                    Prefs.removeGithubAccount(context, account.id)
                    "✅ Compte GitHub « ${account.label} » supprimé de JARVIS (le compte GitHub lui-même n'est pas affecté)."
                }
            }

            "github_set_default_account" -> {
                val label = json.optString("label", "")
                val account = Prefs.findGithubAccount(context, label)
                if (account == null) "❌ Aucun compte GitHub trouvé pour « $label »."
                else {
                    Prefs.setDefaultGithubAccount(context, account.id)
                    "✅ Compte GitHub par défaut : « ${account.label} »."
                }
            }

            "github_create_repo" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom de dépôt manquant."
                else GitHubController.createRepo(
                    context, name,
                    json.optString("description", ""),
                    json.optBoolean("private", false),
                    json.optString("account", "")
                )
            }

            "github_list_contents" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                if (owner.isBlank() || repo.isBlank()) "❌ Paramètres manquants (owner et repo sont requis)."
                else GitHubController.listContents(
                    context, owner, repo,
                    json.optString("path", ""),
                    json.optString("branch", "main"),
                    json.optString("account", "")
                )
            }

            "github_create_file", "github_update_file" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val path = json.optString("path", "")
                val content = json.optString("content", "")
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) {
                    "❌ Paramètres manquants (owner, repo et path sont requis)."
                } else {
                    GitHubController.createOrUpdateFile(
                        context, owner, repo, path, content,
                        json.optString("message", "Mise à jour via JARVIS"),
                        json.optString("branch", "main"),
                        json.optString("account", "")
                    )
                }
            }

            "github_delete_file" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val path = json.optString("path", "")
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) "❌ Paramètres manquants (owner, repo et path sont requis)."
                else GitHubController.deleteFile(
                    context, owner, repo, path,
                    json.optString("message", "Suppression via JARVIS"),
                    json.optString("branch", "main"),
                    json.optString("account", "")
                )
            }

            "github_delete_folder" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val path = json.optString("path", "")
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) "❌ Paramètres manquants (owner, repo et path sont requis)."
                else GitHubController.deleteFolder(
                    context, owner, repo, path,
                    json.optString("message", "Suppression de dossier via JARVIS"),
                    json.optString("branch", "main"),
                    json.optString("account", "")
                )
            }

            "github_delete_repo" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                if (owner.isBlank() || repo.isBlank()) "❌ Paramètres manquants (owner et repo sont requis)."
                else GitHubController.deleteRepo(context, owner, repo, json.optString("account", ""))
            }

            "github_read_file" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val path = json.optString("path", "")
                if (owner.isBlank() || repo.isBlank() || path.isBlank()) "❌ Paramètres manquants."
                else GitHubController.readFile(context, owner, repo, path, json.optString("branch", "main"), json.optString("account", ""))
            }

            "github_create_branch" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val newBranch = json.optString("newBranch", "")
                if (owner.isBlank() || repo.isBlank() || newBranch.isBlank()) "❌ Paramètres manquants."
                else GitHubController.createBranch(context, owner, repo, newBranch, json.optString("fromBranch", "main"), json.optString("account", ""))
            }

            "github_create_pr" -> {
                val owner = json.optString("owner", "")
                val repo = json.optString("repo", "")
                val title = json.optString("title", "")
                val head = json.optString("head", "")
                if (owner.isBlank() || repo.isBlank() || title.isBlank() || head.isBlank()) {
                    "❌ Paramètres manquants (owner, repo, title et head sont requis)."
                } else {
                    GitHubController.createPullRequest(
                        context, owner, repo, title, head,
                        json.optString("base", "main"),
                        json.optString("body", ""),
                        json.optString("account", "")
                    )
                }
            }

            "save_contact_profile" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else {
                    // Paramètre optionnel "template" : applique un modèle de fiche créé au
                    // préalable (save_contact_template) — sert à la fois à PRÉ-REMPLIR un
                    // nouveau contact et à METTRE À JOUR un contact existant avec les valeurs
                    // du modèle. Priorité : champ donné explicitement dans CETTE demande >
                    // valeur du modèle > valeur déjà enregistrée sur la fiche (gérée plus loin
                    // par PeopleController.saveContact, qui conserve l'existant si on lui passe
                    // null) > défaut. Un champ du modèle ne s'applique donc JAMAIS par-dessus un
                    // champ explicitement précisé dans le même message.
                    val templateName = cleanOptionalField(json.optString("template", ""))
                    val template = templateName?.let { Prefs.findContactTemplate(context, it) }
                    val templateWarning = if (templateName != null && template == null) {
                        "\n⚠️ Modèle « $templateName » introuvable — contact enregistré sans lui " +
                            "(vérifie le nom exact avec la liste des modèles)."
                    } else ""

                    val explicitCategory = if (json.has("category")) cleanOptionalField(json.optString("category", "")) else null

                    val result = PeopleController.saveContact(
                        context = context,
                        name = name,
                        category = explicitCategory ?: template?.category,
                        nickname = cleanOptionalField(json.optString("nickname", "")) ?: template?.nickname,
                        phone = cleanOptionalField(json.optString("phone", "")) ?: template?.phone,
                        phonePro = cleanOptionalField(json.optString("phonePro", "")) ?: template?.phonePro,
                        email = cleanOptionalField(json.optString("email", "")) ?: template?.email,
                        address = cleanOptionalField(json.optString("address", "")) ?: template?.address,
                        addressPro = cleanOptionalField(json.optString("addressPro", "")) ?: template?.addressPro,
                        birthday = cleanOptionalField(json.optString("birthday", "")) ?: template?.birthday,
                        company = cleanOptionalField(json.optString("company", "")) ?: template?.company,
                        position = cleanOptionalField(json.optString("position", "")) ?: template?.position,
                        latitude = if (json.has("latitude")) json.optDouble("latitude") else null,
                        longitude = if (json.has("longitude")) json.optDouble("longitude") else null,
                        installDate = cleanOptionalField(json.optString("installDate", "")),
                        notes = cleanOptionalField(json.optString("notes", "")) ?: template?.notes
                    )
                    result + templateWarning
                }
            }

            "save_contact_template" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du modèle manquant."
                else {
                    Prefs.saveContactTemplate(
                        context,
                        Prefs.ContactTemplate(
                            name = name.trim(),
                            category = cleanOptionalField(json.optString("category", "")),
                            nickname = cleanOptionalField(json.optString("nickname", "")),
                            phone = cleanOptionalField(json.optString("phone", "")),
                            phonePro = cleanOptionalField(json.optString("phonePro", "")),
                            email = cleanOptionalField(json.optString("email", "")),
                            address = cleanOptionalField(json.optString("address", "")),
                            addressPro = cleanOptionalField(json.optString("addressPro", "")),
                            birthday = cleanOptionalField(json.optString("birthday", "")),
                            company = cleanOptionalField(json.optString("company", "")),
                            position = cleanOptionalField(json.optString("position", "")),
                            notes = cleanOptionalField(json.optString("notes", ""))
                        )
                    )
                    "✅ Modèle de fiche contact « ${name.trim()} » enregistré. Utilisable pour créer un nouveau " +
                        "contact ou mettre à jour un contact existant en le citant (paramètre template)."
                }
            }

            "list_contact_templates" -> {
                val templates = Prefs.getContactTemplates(context)
                if (templates.isEmpty()) "ℹ️ Aucun modèle de fiche contact enregistré pour l'instant."
                else templates.joinToString("\n\n") { t ->
                    val fields = listOfNotNull(
                        t.category?.let { "catégorie : $it" },
                        t.company?.let { "société : $it" },
                        t.position?.let { "poste : $it" },
                        t.phone?.let { "tél : $it" },
                        t.email?.let { "email : $it" }
                    )
                    if (fields.isEmpty()) "**${t.name}**" else "**${t.name}** — ${fields.joinToString(", ")}"
                }
            }

            "delete_contact_template" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du modèle manquant."
                else if (Prefs.deleteContactTemplate(context, name)) "✅ Modèle « ${name.trim()} » supprimé."
                else "❌ Aucun modèle nommé « ${name.trim()} » trouvé."
            }
            "search_contact_profile" -> {
                val query = json.optString("query", "")
                val formatHint = json.optString("format_hint", "")
                if (query.isBlank()) "❌ Aucun terme de recherche fourni."
                else {
                    val result = PeopleController.searchContacts(context, query)
                    // Réaffiche automatiquement la dernière photo attachée à la fiche
                    // trouvée, comme pour une image générée — sinon une pièce jointe
                    // resterait invisible tant qu'on n'irait pas fouiller le vault.
                    PeopleController.getLatestImageAttachment(context, query)?.let { (b64, mime) ->
                        pendingImageBase64 = b64
                        pendingImageMime = mime
                    }
                    withContactPresentationStyleNote(context, result, formatHint)
                }
            }
            "list_contacts_by_category" -> withContactPresentationStyleNote(
                context, PeopleController.listByCategory(context, json.optString("category", "")),
                json.optString("format_hint", "")
            )
            "set_contact_presentation_style" -> {
                val style = json.optString("style", "")
                if (style.isBlank()) "❌ Précise comment tu veux que les fiches contact soient présentées."
                else {
                    Prefs.saveContactPresentationStyle(context, style)
                    "✅ Compris, j'ajoute ça à mes consignes permanentes pour tes fiches contact : « $style ». Dis-moi « reset_contact_presentation_style » (ou demande-le-moi en langage naturel) pour tout effacer et revenir au format par défaut."
                }
            }
            "reset_contact_presentation_style" -> {
                Prefs.resetContactPresentationStyle(context)
                "✅ Style de présentation des fiches contact réinitialisé au format par défaut."
            }
            "set_calendar_presentation_style" -> {
                val style = json.optString("style", "")
                if (style.isBlank()) "❌ Précise comment tu veux que le planning/agenda soit présenté."
                else {
                    Prefs.saveCalendarPresentationStyle(context, style)
                    "✅ Compris, j'ajoute ça à mes consignes permanentes pour le planning/agenda : « $style ». Dis-moi « reset_calendar_presentation_style » (ou demande-le-moi en langage naturel) pour tout effacer et revenir au format par défaut."
                }
            }
            "reset_calendar_presentation_style" -> {
                Prefs.resetCalendarPresentationStyle(context)
                "✅ Style de présentation du planning/agenda réinitialisé au format par défaut."
            }
            "enable_contact_links" -> {
                Prefs.setContactLinksEnabled(context, true)
                "✅ Les numéros de téléphone, emails et adresses affichés dans les fiches sont maintenant cliquables (appel/mail/itinéraire direct)."
            }
            "disable_contact_links" -> {
                Prefs.setContactLinksEnabled(context, false)
                "✅ Retour au texte simple, sans liens cliquables dans les fiches."
            }
            "attach_contact_file" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else {
                    val recentAttachment = ConversationStore.messages.lastOrNull { it.isUser && !it.attachmentPath.isNullOrBlank() }
                    if (recentAttachment == null) {
                        "❌ Aucune photo ou document récemment envoyé dans le chat à attacher. Envoie d'abord le fichier, puis redemande."
                    } else {
                        PeopleController.addAttachment(
                            context, name,
                            recentAttachment.attachmentPath!!,
                            recentAttachment.attachmentName ?: File(recentAttachment.attachmentPath).name
                        )
                    }
                }
            }
            "delete_contact_profile" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else PeopleController.deleteContact(context, name)
            }
            "navigate_to_contact" -> {
                val name = json.optString("name", "")
                if (name.isBlank()) "❌ Nom du contact manquant."
                else PeopleController.navigateToContact(context, name)
            }
            "refresh_all_contacts" -> PeopleController.refreshAllContacts(context)

            // Vault Obsidian réel (notes libres, distinct des fiches contacts ci-dessus) —
            // toujours utiliser ces actions plutôt que de deviner un contenu : le chemin
            // du vault est configurable (⚙ Obsidian) et peut différer de ce qu'on attend.
            "obsidian_status" -> {
                val path = ObsidianController.getVaultRoot(context).absolutePath
                "📂 Vault Obsidian actuel : $path\n\n" + ObsidianController.getVaultStats(context)
            }
            "obsidian_search" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Aucun terme de recherche fourni."
                else ObsidianController.searchNotes(context, query)
            }
            "obsidian_list" -> ObsidianController.listNotes(context, json.optString("folder", ""))
            "obsidian_list_folders" -> ObsidianController.listFolders(context, json.optString("path", ""))
            "obsidian_reset_path" -> ObsidianController.resetVaultPath(context)
            "obsidian_wipe" -> ObsidianController.wipeVault(context)
            "obsidian_create_folder" -> ObsidianController.createFolder(context, json.optString("path", ""))
            "obsidian_delete_folder" -> {
                val path = json.optString("path", "")
                if (path.isBlank()) "❌ Précise le dossier à supprimer."
                else ObsidianController.deleteFolder(context, path)
            }
            "obsidian_rename_folder" -> {
                val path = json.optString("path", "")
                val newPath = json.optString("newPath", "").ifBlank { json.optString("newName", "") }
                if (path.isBlank() || newPath.isBlank()) "❌ Précise le dossier ET son nouveau nom/chemin."
                else ObsidianController.renameFolder(context, path, newPath)
            }
            "obsidian_create_note" -> {
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                val folder = json.optString("folder", "").ifBlank { "Notes Rapides" }
                if (title.isBlank()) "❌ Précise un titre pour la note."
                else ObsidianController.createNote(context, title, content, folder)
            }
            "obsidian_daily_note" -> ObsidianController.createDailyNote(context, json.optString("content", ""))
            "obsidian_append" -> {
                val query = json.optString("query", "")
                val text = json.optString("text", "")
                if (query.isBlank() || text.isBlank()) "❌ Précise la note ciblée ET le texte à ajouter."
                else ObsidianController.appendToNote(context, query, text)
            }
            "obsidian_read" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Précise quelle note lire."
                else ObsidianController.readNote(context, query)
            }
            "obsidian_delete_note" -> {
                val query = json.optString("query", "")
                if (query.isBlank()) "❌ Précise quelle note supprimer."
                else ObsidianController.deleteNote(context, query)
            }
            "obsidian_move_file" -> {
                val query = json.optString("query", "").ifBlank { json.optString("file", "") }
                val folder = json.optString("folder", "").ifBlank { json.optString("destination", "") }
                if (query.isBlank() || folder.isBlank()) "❌ Précise quelle note déplacer ET le dossier de destination."
                else ObsidianController.moveNote(context, query, folder)
            }

            "remember_fact" -> {
                val fact = json.optString("fact", "")
                ObsidianController.rememberFact(context, fact)
            }
            "forget_fact" -> {
                val query = json.optString("query", "").ifBlank { json.optString("fact", "") }
                ObsidianController.forgetFact(context, query)
            }

            // ─── Wiki structuré (pattern "LLM Wiki", voir WikiController) ─────────────
            "wiki_init" -> WikiController.init(context)
            "wiki_page" -> {
                val type = json.optString("type", "")
                val title = json.optString("title", "")
                val pageContent = json.optString("content", "")
                val summary = json.optString("summary", "")
                val tags = mutableListOf<String>()
                json.optJSONArray("tags")?.let { arr -> for (i in 0 until arr.length()) tags.add(arr.optString(i)) }
                WikiController.page(context, type, title, pageContent, summary, tags)
            }
            "wiki_status" -> WikiController.status(context)
            "wiki_lint" -> WikiController.lint(context)

            "wakeword_status" -> WakeWordService.statusReport(context)

            "generate_image" -> {
                val prompt = json.optString("prompt", "")
                val count = json.optInt("count", 1)
                // format : "carre" (défaut), "portrait" ou "paysage" — normalisé côté
                // ImageGenController (accepte aussi les synonymes vertical/horizontal/...).
                // AVANT ce paramètre, la question posée par l'IA ("portrait, paysage ou
                // carré ?", voir SYSTEM_PROMPT) n'avait aucun effet réel : tous les
                // fournisseurs généraient toujours en carré quoi qu'on réponde — cause
                // directe du signalement "impossible de choisir le format de l'image".
                val format = json.optString("format", "carre")
                if (prompt.isBlank()) "❌ Aucune description d'image fournie."
                else if (count > 1) {
                    // Plusieurs images à la suite (ex: "génère-moi 5 images de paysages") : toujours
                    // en arrière-plan, jamais de tentative rapide bloquante — attendre 5x25s dans la
                    // coroutine du chat serait inacceptable. Chaque image a sa propre entrée "pending"
                    // visible immédiatement dans la carte de progression de l'onglet 🎨 Génération,
                    // mises à jour une par une au fil de la génération (voir GenerationService).
                    GenerationService.enqueue(context, "image", prompt, count = count, format = format)
                    "🎨 Génération de ${count.coerceIn(2, 20)} images lancée en arrière-plan, l'une après l'autre — suivable en direct dans l'onglet 🎨 Génération, avec une notification à la fin."
                } else {
                    // On tente l'image en direct (cas rapide, quelques secondes via Gemini/OpenAI)
                    // pour l'afficher tout de suite dans le chat comme avant — MAIS bornée par un
                    // délai limite. Avant ce correctif, l'appel tournait sans limite dans la coroutine
                    // du chat : si un fournisseur traînait (ou si l'utilisateur quittait l'écran, ce
                    // qui annule la coroutine), l'entrée restait bloquée en "pending" pour toujours,
                    // SANS aucune notification — contrairement à generate_video/generate_website qui
                    // passent déjà par GenerationService (service en premier plan avec notification de
                    // progression). Si ce délai est dépassé, on abandonne proprement cette tentative et
                    // on relaie vers GenerationService, qui GARANTIT une notification de progression et
                    // un résultat final (succès ou échec), même app fermée.
                    val fastResult = withTimeoutOrNull(25_000L) { ImageGenController.generateImage(context, prompt, format) }
                    if (fastResult != null) {
                        val recordId = "${System.currentTimeMillis()}_${(0..9999).random()}"
                        Prefs.addGenerationRecord(
                            context,
                            Prefs.GenerationRecord(
                                id = recordId,
                                type = "image",
                                prompt = prompt,
                                status = if (fastResult.base64 != null) "success" else "failed",
                                timestamp = System.currentTimeMillis(),
                                resultPath = fastResult.savedPath,
                                errorMessage = if (fastResult.base64 == null) fastResult.message else null
                            )
                        )
                        pendingImageBase64 = fastResult.base64
                        pendingImageMime = fastResult.mime
                        fastResult.message
                    } else {
                        GenerationService.enqueue(context, "image", prompt, format = format)
                        "🎨 La génération prend plus de temps que prévu (au-delà de 25s) — elle continue en arrière-plan " +
                            "avec une notification de progression. Tu seras prévenu dès que l'image sera prête (ou en cas d'échec), " +
                            "visible aussi dans l'onglet 🎨 Génération."
                    }
                }
            }
            "generate_video" -> {
                val prompt = json.optString("prompt", "")
                if (prompt.isBlank()) "❌ Aucune description de vidéo fournie."
                else {
                    val requestedDuration = if (json.has("duration")) json.optInt("duration", VideoGenController.DEFAULT_DURATION_S) else VideoGenController.DEFAULT_DURATION_S
                    val duration = requestedDuration.coerceIn(VideoGenController.MIN_DURATION_S, VideoGenController.MAX_DURATION_S)
                    GenerationService.enqueue(context, "video", prompt, durationSeconds = duration)
                    "🎬 Génération de la vidéo de ${duration}s lancée en arrière-plan (peut prendre plusieurs minutes). " +
                        "Tu peux continuer à utiliser JARVIS ou fermer l'app — une notification " +
                        "t'avertira dès que c'est prêt (ou en cas d'échec)."
                }
            }
            "generate_website" -> {
                val description = json.optString("description", "").ifBlank { json.optString("prompt", "") }
                if (description.isBlank()) "❌ Aucune description de site fournie."
                else {
                    // images : chemins de photos déjà envoyées par l'utilisateur dans le chat
                    // (ou trouvées via list_files/search_files) à intégrer réellement dans le site.
                    val images = mutableListOf<String>()
                    json.optJSONArray("images")?.let { arr -> for (i in 0 until arr.length()) {
                        val p = arr.optString(i, "")
                        if (p.isNotBlank()) images.add(p)
                    } }
                    // BUG RÉEL CORRIGÉ : l'IA ne connaît JAMAIS le chemin réel d'une photo
                    // envoyée dans le chat — elle ne la reçoit qu'en base64 "vision", jamais
                    // sous forme de chemin texte — donc "images" restait quasi toujours vide
                    // même quand l'utilisateur avait bien envoyé des photos, d'où des sites
                    // générés sans la moindre image. Même repli que attach_contact_file : si
                    // l'IA n'a fourni aucun chemin, on récupère nous-mêmes les photos envoyées
                    // récemment dans CETTE conversation.
                    if (images.isEmpty()) {
                        ConversationStore.messages
                            .filter { it.isUser }
                            .takeLast(20)
                            .flatMap { msg ->
                                val fromAttachments = msg.attachments.filter { it.mimeType.startsWith("image/") }.map { it.path }
                                if (fromAttachments.isNotEmpty()) fromAttachments
                                else listOfNotNull(msg.attachmentPath?.takeIf { it.isNotBlank() })
                            }
                            .distinct()
                            .forEach { images.add(it) }
                    }
                    GenerationService.enqueue(context, "website", description, imagePaths = images.takeIf { it.isNotEmpty() })
                    "🌐 Génération du site lancée en arrière-plan. Une notification t'avertira " +
                        "dès que c'est prêt (ou en cas d'échec)."
                }
            }
            "edit_website" -> {
                val instructions = json.optString("instructions", "").ifBlank { json.optString("changes", "") }
                if (instructions.isBlank()) "❌ Précise la modification à apporter au site."
                else {
                    // Si aucun chemin n'est fourni, on modifie le dernier site généré avec succès.
                    val path = json.optString("path", "").ifBlank {
                        Prefs.getGenerationHistory(context)
                            .firstOrNull { it.type in setOf("website", "website_edit") && it.status == "success" && !it.resultPath.isNullOrBlank() }
                            ?.resultPath
                    }
                    if (path.isNullOrBlank()) "❌ Aucun site généré à modifier pour l'instant. Génère-en un d'abord avec generate_website."
                    else {
                        GenerationService.enqueue(context, "website_edit", instructions, existingPath = path)
                        "✏️ Modification du site lancée en arrière-plan (« $instructions »). Une notification t'avertira dès que c'est prêt."
                    }
                }
            }
            "publish_website_github" -> {
                // Si aucun chemin n'est fourni, publie le dernier site généré avec succès.
                val path = json.optString("path", "").ifBlank {
                    Prefs.getGenerationHistory(context)
                        .firstOrNull { it.type in setOf("website", "website_edit") && it.status == "success" && !it.resultPath.isNullOrBlank() }
                        ?.resultPath
                }
                val siteDir = path?.let { java.io.File(it).parentFile }
                if (siteDir == null || !siteDir.exists()) {
                    "❌ Aucun site généré à publier pour l'instant. Génère-en un d'abord avec generate_website."
                } else {
                    WebsiteGenController.publishToGitHub(
                        context, siteDir,
                        json.optString("repo", ""),
                        json.optString("account", ""),
                        json.optBoolean("private", false)
                    ).message
                }
            }

            "ha_status" -> {
                val domain = json.optString("domain", "")
                val raw = HomeAssistantController.summarize(context, json.optString("filter", ""), domain)
                if (domain.equals("person", ignoreCase = true)) withLocationPresentationStyleNote(context, raw) else raw
            }
            "set_location_presentation_style" -> {
                val style = json.optString("style", "")
                if (style.isBlank()) "❌ Précise comment tu veux que la localisation soit présentée."
                else {
                    Prefs.saveLocationPresentationStyle(context, style)
                    "✅ Compris, j'ajoute ça à mes consignes permanentes pour la localisation : « $style ». Dis-moi « reset_location_presentation_style » (ou demande-le-moi en langage naturel) pour tout effacer et revenir au format par défaut."
                }
            }
            "reset_location_presentation_style" -> {
                Prefs.resetLocationPresentationStyle(context)
                "✅ Style de présentation de la localisation réinitialisé au format par défaut."
            }
            "ha_turn_on" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.turnOn(context, entity.entityId).message
            }
            "ha_turn_off" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.turnOff(context, entity.entityId).message
            }
            "ha_toggle" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.toggle(context, entity.entityId).message
            }
            "ha_rename" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val newName = json.optString("newName", "").ifBlank { json.optString("new_name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else if (newName.isBlank()) "❌ Précise le nouveau nom souhaité."
                else HomeAssistantController.renameEntity(context, entity.entityId, newName).message
            }
            "ha_delete" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.deleteEntity(context, entity.entityId).message
            }
            "ha_rescan" -> HomeAssistantController.summarize(context, json.optString("filter", ""), json.optString("domain", ""))
            "ha_set" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun appareil Home Assistant trouvé pour « $name »."
                else HomeAssistantController.setValue(
                    context, entity.entityId,
                    brightnessPct = if (json.has("brightness")) json.optInt("brightness") else null,
                    colorName = json.optString("color", "").ifBlank { null },
                    temperature = if (json.has("temperature")) json.optDouble("temperature") else null,
                    volumePct = if (json.has("volume")) json.optInt("volume") else null,
                    positionPct = if (json.has("position")) json.optInt("position") else null,
                    speedPct = if (json.has("speed")) json.optInt("speed") else null,
                    hvacMode = json.optString("hvacMode", "").ifBlank { null },
                    presetMode = json.optString("presetMode", "").ifBlank { null },
                    fanMode = json.optString("fanMode", "").ifBlank { null },
                    option = json.optString("option", "").ifBlank { null },
                    numberValue = if (json.has("value")) json.optDouble("value") else null,
                    remoteCommand = json.optString("command", "").ifBlank { null },
                    source = json.optString("source", "").ifBlank { null }
                ).message
            }
            "ha_browse_media" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucun lecteur média Home Assistant trouvé pour « $name »."
                else HomeAssistantController.browseMedia(
                    context, entity.entityId,
                    json.optString("path", "").ifBlank { null },
                    json.optString("mediaType", "").ifBlank { null }
                )
            }
            "ha_call_service" -> {
                val domainSvc = json.optString("domain", "")
                val service = json.optString("service", "")
                if (domainSvc.isBlank() || service.isBlank()) "❌ Précise le domaine et le service Home Assistant à appeler (ex: domain=\"light\", service=\"turn_on\")."
                else {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                    if (name.isNotBlank()) {
                        val entity = HomeAssistantController.findEntity(context, name)
                        if (entity != null) data.put("entity_id", entity.entityId)
                    }
                    HomeAssistantController.callServiceRaw(context, domainSvc, service, data).message
                }
            }
            "ha_list_automations" -> HomeAssistantController.listAutomations(context)
            "ha_create_automation" -> {
                val id = json.optString("id", "").ifBlank { System.currentTimeMillis().toString() }
                val config = json.optJSONObject("config")
                if (config == null) "❌ Configuration d'automatisation manquante (attendu : objet JSON avec alias/trigger/action, comme dans l'éditeur d'automatisations Home Assistant)."
                else HomeAssistantController.createOrUpdateAutomation(context, id, config).message
            }
            "ha_delete_automation" -> {
                val id = json.optString("id", "")
                if (id.isBlank()) "❌ Identifiant d'automatisation manquant (voir ha_list_automations)."
                else HomeAssistantController.deleteAutomation(context, id).message
            }
            "ha_trigger_automation" -> {
                val name = json.optString("device", "").ifBlank { json.optString("name", "") }
                val entity = HomeAssistantController.findEntity(context, name)
                if (entity == null) "❌ Aucune automatisation Home Assistant trouvée pour « $name »."
                else HomeAssistantController.triggerAutomation(context, entity.entityId).message
            }

            "network_scan" -> {
                val devices = NetworkController.scanNetwork(context)
                Prefs.saveScannedDevices(context, devices)
                NetworkController.formatScanResult(devices)
            }
            "network_ping" -> {
                val device = json.optString("device", "").ifBlank { json.optString("name", "") }
                if (device.isBlank()) "❌ Précise le nom ou l'IP de l'appareil à tester."
                else NetworkController.pingDevice(context, device)
            }
            "network_open_web" -> {
                val device = json.optString("device", "").ifBlank { json.optString("name", "") }
                if (device.isBlank()) "❌ Précise le nom ou l'IP de l'appareil dont ouvrir l'interface web."
                else NetworkController.openWebInterface(context, device)
            }

            "wake_on_lan" -> {
                val mac = json.optString("mac", "")
                val deviceName = json.optString("device", "").ifBlank { json.optString("name", "") }
                val resolvedMac = mac.ifBlank {
                    Prefs.getSavedNetworkDevices(context).firstOrNull {
                        it.name.equals(deviceName, ignoreCase = true)
                    }?.mac ?: ""
                }
                if (resolvedMac.isBlank()) "❌ Adresse MAC inconnue. Précise l'adresse MAC ou enregistre d'abord l'appareil dans 🏠 → Réseau local."
                else NetworkController.sendWakeOnLan(context, resolvedMac, deviceName.ifBlank { null })
            }
            "set_remote_access" -> {
                val device = json.optString("device", "").ifBlank { json.optString("name", "") }
                val host = json.optString("host", "")
                if (device.isBlank() || host.isBlank()) "❌ Précise le nom de l'appareil ET son adresse distante (ex: device=\"Imprimante\", host=\"monreseau.ddns.net:6310\")."
                else {
                    Prefs.setDeviceRemoteHost(context, device, host)
                    "✅ Accès distant enregistré pour « $device » : $host. Assure-toi d'avoir bien redirigé le port correspondant sur ta box/routeur vers cet appareil, sinon ça ne fonctionnera pas."
                }
            }

            // Box internet unifiée (Freebox / Livebox / SFR Box / Bbox). Configuration
            // conversationnelle, sans formulaire dans les Réglages : pour la Freebox, appelle
            // directement box_pair_freebox (demande d'autorisation affichée sur son écran
            // physique). Pour les 3 autres fournisseurs, demande le mot de passe admin en
            // conversation puis appelle box_set_password (voir plus bas). Certaines capacités
            // (stockage, redirection de ports) ne sont pas disponibles partout — RouterController
            // le signale honnêtement.
            "box_status" -> RouterController.status(context)
            "box_devices" -> RouterController.listDevices(context)
            "box_wifi_status" -> RouterController.wifiStatus(context)
            "box_wifi_set" -> RouterController.wifiSet(context, json.optBoolean("enable", true))
            "box_reboot" -> RouterController.reboot(context)
            "box_storage" -> RouterController.storageInfo(context)
            "box_port_forward" -> {
                val wanPort = json.optInt("wanPort", json.optInt("port", 8080))
                val lanPort = json.optInt("lanPort", wanPort)
                RouterController.configurePortForward(context, wanPort, lanPort, json.optString("comment", "Site JARVIS"))
            }
            "box_remove_port_forward" -> RouterController.removePortForward(context, json.optInt("wanPort", json.optInt("port", 8080)))

            // box_set_vendor : l'utilisateur précise son fournisseur en conversation (ex: "j'ai
            // une Livebox") — vendor accepte freebox/livebox/sfr/bbox (insensible à la casse,
            // variantes comme "orange"/"bouygues" acceptées). box_set_password : pour Livebox/
            // SFR Box/Bbox uniquement (pas d'écran de confirmation physique sur ces box,
            // contrairement à la Freebox) — appelée juste après avoir demandé le mot de passe
            // admin en conversation.
            "box_set_vendor" -> {
                val raw = json.optString("vendor", "").lowercase().trim()
                val code = when {
                    raw.contains("free") -> "FREEBOX"
                    raw.contains("live") || raw.contains("orange") -> "LIVEBOX"
                    raw.contains("sfr") -> "SFR"
                    raw.contains("bbox") || raw.contains("bouygue") -> "BBOX"
                    else -> null
                }
                if (code == null) "❌ Fournisseur « $raw » non reconnu (attendu : Freebox, Livebox/Orange, SFR Box, Bbox/Bouygues)."
                else {
                    Prefs.saveBoxVendor(context, code)
                    val label = RouterController.vendorLabel(context)
                    if (code == "FREEBOX") "✅ Fournisseur enregistré : $label. Lance box_pair_freebox pour l'appairer (demande sur l'écran de la Freebox)."
                    else "✅ Fournisseur enregistré : $label. Demande maintenant le mot de passe admin de la box, puis appelle box_set_password{password}."
                }
            }
            "box_set_password" -> {
                val password = json.optString("password", "")
                if (password.isBlank()) "❌ Aucun mot de passe fourni."
                else {
                    Prefs.saveBoxPassword(context, password)
                    val vendor = RouterController.vendorLabel(context)
                    "✅ Mot de passe enregistré pour $vendor. Je peux maintenant la piloter (état, Wi-Fi, appareils, redémarrage)."
                }
            }
            // box_pair_freebox : lance l'appairage officiel Freebox OS en arrière-plan — une
            // demande d'autorisation apparaît directement sur l'écran de la Freebox, à valider
            // par l'utilisateur dans les ~90 secondes. Notification à l'issue (accepté/refusé/
            // expiré). Ne bloque jamais la conversation.
            "box_pair_freebox" -> FreeboxController.startPairing(context)

            // Freebox Home (domotique : capteurs, prises, volets Delta/Pop...) — reste
            // spécifique à la Freebox, aucun autre fournisseur n'a d'équivalent matériel.
            "freebox_home_devices" -> FreeboxController.homeDevices(context, json.optString("filter", ""))
            "freebox_home_set" -> {
                val device = json.optString("device", "")
                val hasNum = json.has("value") && !json.isNull("value")
                if (hasNum) {
                    FreeboxController.homeSet(context, device, numValue = json.optDouble("value"))
                } else {
                    FreeboxController.homeSet(context, device, boolValue = json.optBoolean("on", true))
                }
            }

            "start_local_web_server" -> {
                val path = json.optString("path", "").ifBlank {
                    Prefs.getGenerationHistory(context)
                        .firstOrNull { it.type in setOf("website", "website_edit") && it.status == "success" && !it.resultPath.isNullOrBlank() }
                        ?.resultPath
                }
                val siteDir = path?.let { java.io.File(it).parentFile }
                if (siteDir == null || !siteDir.exists()) {
                    "❌ Aucun site généré à héberger pour l'instant. Génère-en un d'abord avec generate_website."
                } else {
                    LocalWebServerController.start(context, siteDir, json.optInt("port", 8080))
                }
            }
            "stop_local_web_server" -> LocalWebServerController.stop(context)
            "local_web_server_status" -> LocalWebServerController.status(context)

            "test_api_keys" -> ApiKeyTestController.testAllConfiguredKeys(context)

            "set_chat_theme" -> {
                val target = json.optString("target", "").lowercase().trim()
                val colorRaw = json.optString("color", "")
                val color = parseColorFlexible(colorRaw)
                if (color == null) {
                    "❌ Couleur « $colorRaw » non reconnue. Donne un nom courant (bleu, rouge, vert, violet, orange, jaune, rose, noir, blanc, gris, cyan...) ou un code hexadécimal (#RRGGBB)."
                } else {
                    when (target) {
                        "fond", "fond_chat", "background", "fond_ecran" -> {
                            Prefs.saveChatBackgroundColor(context, color)
                            "🎨 Fond du chat mis à jour. Le changement sera visible immédiatement (ou au retour sur l'écran Chat)."
                        }
                        "bulle_utilisateur", "bulle_moi", "user_bubble", "mes_bulles" -> {
                            Prefs.saveChatBubbleUserColor(context, color)
                            "🎨 Couleur de tes bulles de message mise à jour."
                        }
                        "bulle_jarvis", "bulle_ia", "ai_bubble" -> {
                            Prefs.saveChatBubbleAiColor(context, color)
                            "🎨 Couleur des bulles de JARVIS mise à jour."
                        }
                        else -> "❌ Cible « $target » non reconnue. Précise fond, bulle_utilisateur ou bulle_jarvis."
                    }
                }
            }
            "reset_chat_theme" -> {
                Prefs.resetChatTheme(context)
                "✅ Thème du chat réinitialisé aux couleurs par défaut."
            }

            "open_file" -> {
                // Si aucun chemin exact n'est fourni, on retrouve le dernier fichier généré
                // avec succès correspondant au type demandé (ex: "le PDF que tu as créé") —
                // avant ce correctif, l'IA n'ayant aucune mémoire persistante du chemin exact
                // au-delà de la fenêtre de contexte de la conversation, elle prétendait à tort
                // ne pas se souvenir avoir créé le fichier, alors qu'il était bien enregistré
                // dans l'historique de génération.
                val path = json.optString("path", "").ifBlank {
                    findRecentGenerationPath(context, json.optString("type", ""))
                }
                if (path.isNullOrBlank()) "❌ Aucun fichier correspondant trouvé. Utilise list_generations pour voir l'historique des fichiers créés, ou précise le chemin exact."
                else FileGenController.openFile(context, path)
            }
            "print_file" -> {
                val path = json.optString("path", "").ifBlank {
                    findRecentGenerationPath(context, json.optString("type", ""))
                }
                if (path.isNullOrBlank()) "❌ Aucun fichier correspondant trouvé à imprimer. Utilise list_generations pour voir l'historique, ou précise le chemin exact."
                else PrintController.printFile(context, path, json.optString("printer", "").ifBlank { null }).message
            }
            // BUG RÉEL CORRIGÉ : "imprime une page de test" faisait auparavant appeler print_file
            // avec un chemin INVENTÉ par le modèle (aucun fichier de test n'existe réellement sur
            // le téléphone), d'où le "fichier introuvable" signalé. Cette action génère un VRAI
            // PDF minimal via FileGenController (même moteur PDF que create_pdf) puis l'imprime —
            // ne dépend donc plus d'un fichier halluciné.
            "print_test_page" -> {
                val pdfResult = FileGenController.createPdf(
                    "Page de test JARVIS",
                    "Ceci est une page de test générée par JARVIS pour vérifier la connexion à l'imprimante.\n\n" +
                        "Générée le : ${java.util.Date()}",
                    "test_impression"
                )
                if (!pdfResult.success || pdfResult.filePath.isNullOrBlank()) {
                    "❌ Impossible de générer la page de test : ${pdfResult.message}"
                } else {
                    PrintController.printFile(context, pdfResult.filePath, json.optString("printer", "").ifBlank { null }).message
                }
            }
            "clear_all_conversations" -> {
                ConversationStore.clearAll(context)
                "✅ Toutes les conversations enregistrées ont été supprimées. Nouvelle conversation vide démarrée."
            }
            "clear_generation_history" -> {
                Prefs.clearGenerationHistory(context)
                "✅ Historique des générations vidé (les fichiers déjà créés restent sur le disque, seule la liste est réinitialisée)."
            }
            "list_generations" -> {
                val typeHint = json.optString("type", "")
                val count = json.optInt("count", 10).coerceIn(1, 50)
                val types = GENERATION_TYPE_ALIASES[typeHint.trim().lowercase()]
                val history = Prefs.getGenerationHistory(context)
                    .filter { types == null || it.type in types }
                    .take(count)
                if (history.isEmpty()) "📂 Aucune génération trouvée dans l'historique" + (if (typeHint.isNotBlank()) " pour « $typeHint »." else ".")
                else {
                    val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRENCH)
                    val sb = StringBuilder("📂 **Historique des générations**" + (if (typeHint.isNotBlank()) " ($typeHint)" else "") + " :\n\n")
                    history.forEach { rec ->
                        val statusIcon = when (rec.status) { "success" -> "✅"; "failed" -> "❌"; else -> "⏳" }
                        sb.append("$statusIcon ${rec.type} — « ${rec.prompt.take(60)} » — ${sdf.format(java.util.Date(rec.timestamp))}\n")
                        if (!rec.resultPath.isNullOrBlank()) sb.append("   📁 ${rec.resultPath}\n")
                    }
                    sb.toString().trim()
                }
            }
            "list_printers" -> PrintController.listPrinters(context)
            "set_printer_remote_host" -> {
                val host = json.optString("host", "")
                if (host.isBlank()) "❌ Adresse distante manquante (ex: host=\"monreseau.ddns.net:6310\")."
                else PrintController.setRemotePrinterHost(context, host)
            }
            "set_default_printer" -> {
                val ip = json.optString("ip", "")
                if (ip.isBlank()) "❌ Adresse IP d'imprimante manquante."
                else PrintController.setDefaultPrinter(context, ip)
            }
            "create_zip" -> {
                val paths = mutableListOf<String>()
                json.optJSONArray("paths")?.let { arr -> for (i in 0 until arr.length()) paths.add(arr.optString(i)) }
                if (paths.isEmpty()) json.optString("path", "").ifBlank { null }?.let { paths.add(it) }
                val name = json.optString("name", "").ifBlank { "archive" }
                val result = FileGenController.createZip(paths, name)
                logFileRecord(context, "zip", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_pdf" -> {
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                val name = json.optString("name", "").ifBlank { title }
                val images = mutableListOf<String>()
                json.optJSONArray("images")?.let { arr -> for (i in 0 until arr.length()) images.add(arr.optString(i)) }
                val result = FileGenController.createPdf(title, content, name, images)
                logFileRecord(context, "pdf", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_docx" -> {
                val title = json.optString("title", "")
                val content = json.optString("content", "")
                val name = json.optString("name", "").ifBlank { title }
                val images = mutableListOf<String>()
                json.optJSONArray("images")?.let { arr -> for (i in 0 until arr.length()) images.add(arr.optString(i)) }
                val result = FileGenController.createDocx(title, content, name, images)
                logFileRecord(context, "docx", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_xlsx" -> {
                val title = json.optString("title", "").ifBlank { json.optString("sheetName", "") }
                val data = json.optString("data", "").ifBlank { json.optString("content", "") }
                val name = json.optString("name", "").ifBlank { title }
                val result = FileGenController.createXlsx(title, data, name)
                logFileRecord(context, "xlsx", name, result.success, result.filePath, result.message)
                result.message
            }
            "create_chart" -> {
                val type = json.optString("type", "bar")
                val title = json.optString("title", "")
                val data = json.optString("data", "")
                val recordId = "${System.currentTimeMillis()}_${(0..9999).random()}"
                Prefs.addGenerationRecord(
                    context,
                    Prefs.GenerationRecord(id = recordId, type = "chart", prompt = title.ifBlank { "Graphique" }, status = "pending", timestamp = System.currentTimeMillis())
                )
                val result = ChartController.generateChart(context, type, title, data)
                Prefs.updateGenerationRecord(context, recordId) { record ->
                    record.copy(
                        status = if (result.success) "success" else "failed",
                        resultPath = result.savedPath,
                        errorMessage = if (!result.success) result.message else null
                    )
                }
                pendingImageBase64 = result.base64
                pendingImageMime = result.mime
                result.message
            }

            else -> "❌ Commande système inconnue : « $action »."
        }
    }

    // Valeurs que l'IA écrit parfois littéralement à la place d'omettre un champ inconnu
    // (au lieu d'un vrai JSON null) — sans ce filtre, elles s'affichaient telles quelles
    // plus tard dans la fiche contact (ex: "📞 Téléphone perso : null").
    private val BLANK_PLACEHOLDER_VALUES = setOf(
        "null", "n/a", "na", "none", "aucun", "aucune", "non renseigné", "non renseigne", "inconnu", "-", "?"
    )

    // Marqueur détecté par ApiClient.sendChat (applyContactFormattingIfNeeded) pour savoir
    // qu'un appel IA dédié de reformatage de fiche contact est nécessaire. Volontairement
    // PAS dans INFORMATIONAL_ACTIONS/summarizeNaturally : cette reformulation générique
    // transforme le résultat en prose orale sans mise en forme, ce qui détruirait la
    // présentation en sections/emojis d'une fiche. Le marqueur ne déclenche donc RIEN
    // par défaut — sans lui, l'affichage brut PeopleController reste utilisé tel quel,
    // rapide et sans risque d'altération de données par un appel IA superflu.
    const val CONTACT_FORMAT_MARKER = "[[CONTACT_FORMAT_INSTRUCTION]]"

    /**
     * Ajoute au résultat brut d'une recherche/liste de contacts une consigne de présentation
     * à appliquer : la consigne PERSISTÉE (set_contact_presentation_style, valable pour
     * toutes les fiches jusqu'à nouvel ordre) et/ou une consigne PONCTUELLE (format_hint,
     * remplie par l'IA quand l'utilisateur demande une présentation différente juste pour
     * cette réponse — ex: "montre-la moi en tableau", "fais plus court cette fois").
     */
    private fun withContactPresentationStyleNote(context: Context, rawResult: String, formatHint: String = ""): String {
        val style = Prefs.getContactPresentationStyle(context)
        if (style.isBlank() && formatHint.isBlank()) return rawResult
        val instructions = buildList {
            if (style.isNotBlank()) add("consigne permanente choisie par l'utilisateur pour TOUTES ses fiches contact : $style")
            if (formatHint.isNotBlank()) add("demande ponctuelle pour cette réponse uniquement (n'affecte pas les prochaines fiches) : $formatHint")
        }.joinToString(" | ")
        return "$rawResult\n\n$CONTACT_FORMAT_MARKER $instructions"
    }

    // Même principe que CONTACT_FORMAT_MARKER mais pour l'affichage de la localisation d'une
    // personne (ha_status{domain:"person"}) — demandé explicitement par l'utilisateur en même
    // temps que la persistance du style des fiches contact ("PEREIL POUR LA LOCALISATION").
    const val LOCATION_FORMAT_MARKER = "[[LOCATION_FORMAT_INSTRUCTION]]"

    /**
     * Ajoute au résultat brut d'une consultation de localisation (ha_status domain="person")
     * une consigne de présentation PERSISTÉE (set_location_presentation_style) si l'utilisateur
     * en a défini une — sinon ne touche à rien. Contrairement aux fiches contact, il n'y a pas
     * de format_hint ponctuel ici (pas de paramètre dédié côté ha_status), seulement la
     * préférence durable.
     */
    private fun withLocationPresentationStyleNote(context: Context, rawResult: String): String {
        val style = Prefs.getLocationPresentationStyle(context)
        if (style.isBlank()) return rawResult
        return "$rawResult\n\n$LOCATION_FORMAT_MARKER consigne permanente choisie par l'utilisateur pour l'affichage de la localisation : $style"
    }

    // Même principe que CONTACT_FORMAT_MARKER/LOCATION_FORMAT_MARKER mais pour le planning/
    // agenda (today_events/upcoming_events/week_events/search_event) -- demande utilisateur
    // explicite avec captures d'écran : "quand je demande un planning... que cela s'affiche
    // toujours comme sur l'image". Contrairement aux fiches contact, le format PAR DÉFAUT
    // (sans aucun style enregistré) est déjà satisfaisant tel quel -- voir
    // CalendarController.getEventsTimeRange, réécrit en Kotlin déterministe groupé par jour
    // -- ce marqueur ne sert donc qu'à une personnalisation VOLONTAIRE au-delà de ce défaut.
    const val CALENDAR_FORMAT_MARKER = "[[CALENDAR_FORMAT_INSTRUCTION]]"

    private fun withCalendarPresentationStyleNote(context: Context, rawResult: String): String {
        val style = Prefs.getCalendarPresentationStyle(context)
        if (style.isBlank()) return rawResult
        return "$rawResult\n\n$CALENDAR_FORMAT_MARKER consigne permanente choisie par l'utilisateur pour l'affichage du planning/agenda : $style"
    }

    // Noms de couleurs français courants → hex, pour set_chat_theme — Color.parseColor()
    // ne connaît que les noms anglais (CSS), inutilisables tels quels pour une commande
    // vocale/chat en français.
    private val FRENCH_COLOR_NAMES = mapOf(
        "bleu" to "#2979FF", "bleu clair" to "#00E5FF", "bleu marine" to "#0D47A1",
        "rouge" to "#FF3B30", "vert" to "#00E676", "vert foncé" to "#1B5E20",
        "violet" to "#B388FF", "mauve" to "#B388FF", "orange" to "#FF9100",
        "jaune" to "#FFC400", "rose" to "#FF4FA0", "noir" to "#0A0A0A",
        "blanc" to "#F5F5F5", "gris" to "#616161", "gris foncé" to "#2C2C2C",
        "cyan" to "#00E5FF", "turquoise" to "#1DE9B6", "marron" to "#6D4C41",
        "beige" to "#D7CCC8", "or" to "#E8B84B", "doré" to "#E8B84B", "dore" to "#E8B84B"
    )

    /** Résout une couleur donnée par l'utilisateur (nom français, nom CSS anglais, ou hex
     *  #RRGGBB/#AARRGGBB) en Int ARGB, ou null si non reconnue. */
    private fun parseColorFlexible(raw: String): Int? {
        val trimmed = raw.trim().lowercase()
        if (trimmed.isBlank()) return null
        FRENCH_COLOR_NAMES[trimmed]?.let {
            return try { android.graphics.Color.parseColor(it) } catch (_: Exception) { null }
        }
        val hexCandidate = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
        return try { android.graphics.Color.parseColor(hexCandidate) } catch (_: Exception) {
            try { android.graphics.Color.parseColor(trimmed) } catch (_: Exception) { null }
        }
    }

    /** Traite un champ texte optionnel venant de l'IA : absence réelle si vide OU si l'IA
     *  a écrit un texte de substitution du type "null"/"N/A" au lieu d'omettre le champ. */
    private fun cleanOptionalField(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.lowercase() in BLANK_PLACEHOLDER_VALUES) return null
        return trimmed
    }

    /** Enregistre un fichier créé (zip/pdf/docx/xlsx) dans l'historique de 🎨 Génération, pour rester cohérent avec les autres types de génération et pouvoir le retrouver plus tard depuis la galerie. */
    private fun logFileRecord(context: Context, kind: String, name: String, success: Boolean, path: String?, message: String) {
        val id = "${System.currentTimeMillis()}_${(0..9999).random()}"
        Prefs.addGenerationRecord(
            context,
            Prefs.GenerationRecord(
                id = id, type = "file_$kind", prompt = name.ifBlank { kind }, status = if (success) "success" else "failed",
                timestamp = System.currentTimeMillis(), resultPath = path, errorMessage = if (!success) message else null
            )
        )
        // BUG RÉEL CORRIGÉ : create_pdf/create_docx/create_xlsx/create_zip écrivaient le fichier
        // avec l'API File/FileOutputStream classique, qui écrit bien sur le disque mais
        // N'INFORME PAS MediaStore (l'index utilisé par l'appli Fichiers, un sélecteur de
        // fichier, etc.) — résultat : le fichier existe réellement sur le téléphone, JARVIS
        // répond correctement "fichier créé", mais il reste invisible dans l'appli Fichiers/
        // Documents tant qu'un scan média n'a pas eu lieu (peut prendre très longtemps, ou ne
        // jamais arriver spontanément pour un dossier peu consulté). MediaScannerConnection.
        // scanFile déclenche cette indexation immédiatement après l'écriture, sans attendre.
        if (success && !path.isNullOrBlank()) {
            notifyMediaScanner(context, path)
        }
    }

    /**
     * Déclenche immédiatement l'indexation MediaStore d'un fichier tout juste écrit sur le
     * stockage partagé (Documents/Pictures/...), pour qu'il apparaisse sans délai dans
     * l'appli Fichiers, un sélecteur de fichier, ou toute appli tierce qui liste via
     * MediaStore plutôt qu'en lisant le système de fichiers brut.
     */
    fun notifyMediaScanner(context: Context, path: String) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
        } catch (e: Exception) {
            // Non bloquant : le fichier existe déjà sur le disque même si l'indexation échoue.
        }
    }

    fun cleanResponse(llmResponse: String): String {
        // Utilise le même parseur à comptage de crochets que parseAndExecute (voir
        // findJarvisCommands) au lieu d'un regex séparé : l'ancien regex non-gourmand
        // laissait un fragment JSON tronqué visible dans le chat dès qu'une commande
        // contenait un tableau (ex: images:[...]), car il ne retirait que jusqu'au
        // premier "]" au lieu du vrai crochet fermant du bloc [JARVIS_CMD:...].
        val matches = findJarvisCommands(llmResponse)
        if (matches.isEmpty()) return llmResponse.trim()
        val sb = StringBuilder()
        var last = 0
        for (m in matches) {
            sb.append(llmResponse, last, m.fullStart)
            last = m.fullEnd
        }
        sb.append(llmResponse, last, llmResponse.length)
        return sb.toString().trim()
    }
}
