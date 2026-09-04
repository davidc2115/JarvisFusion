package com.jarvis.assistant

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CalendarController {

    private val FRENCH_WEEKDAYS = mapOf(
        "lundi" to Calendar.MONDAY, "mardi" to Calendar.TUESDAY, "mercredi" to Calendar.WEDNESDAY,
        "jeudi" to Calendar.THURSDAY, "vendredi" to Calendar.FRIDAY, "samedi" to Calendar.SATURDAY,
        "dimanche" to Calendar.SUNDAY
    )

    /**
     * Résout une date en langage naturel FRANÇAIS (ou un format explicite) en un [Calendar]
     * calculé à partir de l'horloge ACTUELLE de l'appareil — jamais laissé au LLM, qui n'a
     * aucune connaissance fiable de "aujourd'hui" ni ne sait faire un calcul d'epoch
     * millisecondes exact. Même principe que getEventsForWeek (voir son commentaire), appliqué
     * ici à create_event pour que "demain à 14h" soit TOUJOURS calculé correctement.
     *
     * Formats acceptés pour [dateStr] (insensible à la casse/accents) :
     *  - vide, "aujourd'hui", "auj" → aujourd'hui
     *  - "demain" → +1 jour ; "après-demain" → +2 jours
     *  - "hier" → -1 jour ; "avant-hier" → -2 jours
     *  - un jour de la semaine ("lundi", "mardi"...) → sa PROCHAINE occurrence (jamais
     *    aujourd'hui même si on est déjà ce jour-là, pour éviter l'ambiguïté "ce lundi" vs
     *    "lundi prochain")
     *  - "JJ/MM" ou "JJ/MM/AAAA" → date explicite (année courante si omise)
     *  - "AAAA-MM-JJ" (ISO) → date explicite
     *  - non reconnu → reste sur aujourd'hui (comportement de repli sûr, jamais une exception)
     */
    fun resolveDate(dateStr: String): Calendar {
        val cal = Calendar.getInstance()
        val d = dateStr.trim().lowercase()
            .replace("é", "e").replace("è", "e").replace("ê", "e").replace("'", "")
        when {
            d.isBlank() || d == "aujourdhui" || d == "auj" -> Unit
            d == "demain" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            d == "apres-demain" || d == "apresdemain" -> cal.add(Calendar.DAY_OF_YEAR, 2)
            d == "hier" -> cal.add(Calendar.DAY_OF_YEAR, -1)
            d == "avant-hier" || d == "avanthier" -> cal.add(Calendar.DAY_OF_YEAR, -2)
            FRENCH_WEEKDAYS.containsKey(d) -> {
                val target = FRENCH_WEEKDAYS.getValue(d)
                do { cal.add(Calendar.DAY_OF_YEAR, 1) } while (cal.get(Calendar.DAY_OF_WEEK) != target)
            }
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(d) -> {
                val parts = d.split("-").map { it.toInt() }
                cal.set(parts[0], parts[1] - 1, parts[2])
            }
            Regex("^\\d{1,2}/\\d{1,2}(/\\d{2,4})?$").matches(d) -> {
                val parts = d.split("/")
                val day = parts[0].toInt()
                val month = parts[1].toInt()
                val year = if (parts.size > 2) {
                    val yr = parts[2].toInt()
                    if (yr < 100) 2000 + yr else yr
                } else cal.get(Calendar.YEAR)
                cal.set(year, month - 1, day)
            }
            else -> Unit
        }
        return cal
    }

    /**
     * Applique une heure en langage naturel/format libre à [cal] (déjà positionné sur le bon
     * jour par [resolveDate]) — accepte "14:30", "14h30", "14h", "14". Vide → heure par défaut
     * fournie par l'appelant (typiquement 9h, une heure raisonnable pour un événement du jour
     * sans heure précisée).
     */
    fun resolveTime(timeStr: String, cal: Calendar, defaultHour: Int = 9, defaultMinute: Int = 0) {
        val t = timeStr.trim().lowercase().replace("h", ":").trim(':')
        val parts = t.split(":").filter { it.isNotBlank() }
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: defaultHour
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: defaultMinute
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    fun getTodayEvents(context: Context, calendarRef: String? = null): String {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val endOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val title = "📅 **Événements prévus aujourd'hui**" + calendarLabelSuffix(context, calendarRef)
        return getEventsTimeRange(context, startOfDay, endOfDay, title, calendarRef)
    }

    // BUG RÉEL CORRIGÉ (signalement utilisateur : "demain", "à partir de demain" mal
    // compris) : avant ce paramètre, la seule façon d'interroger l'agenda était aujourd'hui
    // (today_events), une plage de N jours à partir de MAINTENANT (upcoming_events), ou une
    // semaine calendaire entière (week_events) -- aucune action ne permettait de cibler "demain"
    // seul ni "à partir de demain" : l'IA n'avait aucun moyen fiable de répondre à ces
    // demandes sans calculer elle-même une date (ce qu'on évite volontairement partout ailleurs
    // dans ce fichier, voir getEventsForWeek). [offsetDays] décale simplement le début de la
    // plage en JOURS CALENDAIRES ENTIERS (0 = aujourd'hui à partir de maintenant, 1 = demain
    // à partir de 00h00, 2 = après-demain...), toujours calculé côté Kotlin depuis l'horloge
    // réelle de l'appareil.
    fun getUpcomingEvents(context: Context, days: Int = 7, calendarRef: String? = null, offsetDays: Int = 0): String {
        val start = if (offsetDays == 0) {
            Calendar.getInstance().timeInMillis
        } else {
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, offsetDays)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }
        val end = Calendar.getInstance().apply {
            if (offsetDays != 0) {
                add(Calendar.DAY_OF_YEAR, offsetDays)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            add(Calendar.DAY_OF_YEAR, days)
        }.timeInMillis

        val period = when {
            offsetDays == 0 -> "des $days prochains jours"
            offsetDays == 1 && days == 1 -> "prévus demain"
            offsetDays == 2 && days == 1 -> "prévus après-demain"
            offsetDays >= 1 && days == 1 -> "prévus dans $offsetDays jours"
            else -> {
                val fromLabel = SimpleDateFormat("dd/MM", Locale.FRENCH).format(Date(start))
                "des $days jours à partir du $fromLabel"
            }
        }
        val title = "📅 **Événements $period**" + calendarLabelSuffix(context, calendarRef)
        return getEventsTimeRange(context, start, end, title, calendarRef)
    }

    /**
     * Événements d'une semaine entière (lundi 00:00 à dimanche 23:59), calculée à partir de
     * l'horloge réelle de l'appareil — PAS à partir d'une date que le LLM devrait deviner
     * (le SYSTEM_PROMPT ne lui communique pas la date du jour, donc tout calcul de plage
     * fait côté LLM ne serait pas fiable). C'est la cause réelle du bug "semaine dernière /
     * semaine prochaine renvoie toujours cette semaine" : seul upcoming_events{days} existait,
     * qui ne regarde QUE vers l'avant depuis maintenant — aucune action ne permettait de
     * reculer dans le temps ou de cibler une semaine calendaire précise.
     *
     * @param weekOffset 0 = semaine en cours, -1 = semaine dernière, 1 = semaine prochaine, etc.
     */
    fun getEventsForWeek(context: Context, weekOffset: Int = 0, calendarRef: String? = null): String {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            add(Calendar.WEEK_OF_YEAR, weekOffset)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        val end = cal.apply {
            add(Calendar.DAY_OF_YEAR, 6)
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis

        val sdf = SimpleDateFormat("dd/MM", Locale.FRENCH)
        val label = when {
            weekOffset == 0 -> "cette semaine"
            weekOffset == -1 -> "la semaine dernière"
            weekOffset == 1 -> "la semaine prochaine"
            weekOffset < 0 -> "il y a ${-weekOffset} semaines"
            else -> "dans $weekOffset semaines"
        }
        val title = "📅 **Événements de $label (${sdf.format(Date(start))} – ${sdf.format(Date(end))})**" + calendarLabelSuffix(context, calendarRef)
        return getEventsTimeRange(context, start, end, title, calendarRef)
    }

    private fun calendarLabelSuffix(context: Context, calendarRef: String?): String {
        // Reflete aussi le calendrier par defaut ("mon planning", voir setDefaultCalendar)
        // quand aucun calendarRef explicite n'est fourni -- pour que l'utilisateur voie
        // TOUJOURS clairement quel planning precis est affiche, meme quand ce choix vient
        // d'une preference memorisee plutot que de sa demande du moment.
        val id = if (!calendarRef.isNullOrBlank()) findCalendarId(context, calendarRef) else Prefs.getDefaultCalendarId(context)
        if (id == null) return ""
        val name = buildCalendarNameMap(context)[id] ?: return ""
        return " — $name"
    }

    /** Construit une table ID de calendrier -> "Nom (compte)" pour annoter les événements. */
    private fun buildCalendarNameMap(context: Context): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "?"
                    val account = c.getString(2) ?: ""
                    val nickname = Prefs.getCalendarNickname(context, id)
                    map[id] = nickname.ifBlank { "$name ($account)" }
                }
            }
        } catch (_: Exception) { /* table vide en cas d'erreur, pas bloquant */ }
        return map
    }

    /**
     * Interroge les occurrences d'événements dans une plage de dates.
     *
     * IMPORTANT : on utilise la table [CalendarContract.Instances] et NON
     * [CalendarContract.Events]. Events ne stocke qu'UNE seule ligne par
     * événement récurrent (RRULE), avec le DTSTART de sa toute première
     * occurrence — un filtre "DTSTART entre début et fin de journée" sur
     * Events fait donc disparaître quasiment tous les événements récurrents
     * (réunion hebdomadaire, rappel quotidien, etc.) sauf le jour exact où
     * la série a commencé. Instances développe correctement les récurrences
     * en occurrences réelles pour la plage demandée — c'est la cause la
     * plus probable des "événements incohérents" (récurrents manquants ou
     * mal datés).
     */
    private fun getEventsTimeRange(context: Context, startMillis: Long, endMillis: Long, title: String, calendarRef: String? = null): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val calendarNames = buildCalendarNameMap(context)

        var filterCalendarId: Long? = null
        if (!calendarRef.isNullOrBlank()) {
            filterCalendarId = findCalendarId(context, calendarRef)
            if (filterCalendarId == null) {
                return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles, puis donne-lui un surnom avec name_calendar si besoin."
            }
        } else {
            // Calendrier par defaut memorise via set_default_calendar ("mon planning") --
            // essaye AVANT le repli generique "tous les calendriers Google" juste en dessous,
            // pour que "mon planning"/"aujourd'hui" sans autre precision affiche directement
            // CE calendrier precis des qu'il a ete defini une fois.
            filterCalendarId = Prefs.getDefaultCalendarId(context)
        }

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_ID
        )

        var selection = "1 = 1"
        val selectionArgsList = mutableListOf<String>()
        if (filterCalendarId != null) {
            selection += " AND ${CalendarContract.Instances.CALENDAR_ID} = ?"
            selectionArgsList.add(filterCalendarId.toString())
        } else {
            // Aucun calendrier precise explicitement : on se limite par defaut aux calendriers
            // Google (voir getGoogleCalendarIds) pour ne JAMAIS faire remonter un calendrier
            // LOCAL du fabricant (Xiaomi/MIUI...) parmi "aujourd'hui"/"cette semaine" - demande
            // explicite : JARVIS ne doit utiliser QUE Google Agenda. Si aucun calendrier Google
            // n'est configure sur l'appareil, on retombe sur tous les calendriers pour ne pas
            // rendre la fonction totalement inutilisable.
            val googleIds = getGoogleCalendarIds(context)
            if (googleIds.isNotEmpty()) {
                selection += " AND ${CalendarContract.Instances.CALENDAR_ID} IN (${googleIds.joinToString(",")})"
            }
        }

        return try {
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, startMillis)
            ContentUris.appendId(uriBuilder, endMillis)

            val cursor: Cursor? = context.contentResolver.query(
                uriBuilder.build(),
                projection,
                selection,
                selectionArgsList.toTypedArray(),
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "$title :\n\n  aucun événement trouvé."

                // Présentation groupée par jour (🔹 séparateur, uniquement quand le jour
                // change), heure bien visible, localisation cliquable via le préfixe 🏠 déjà
                // utilisé pour les fiches contact -- demande utilisateur explicite (captures
                // d'écran à l'appui) : un planning doit TOUJOURS s'afficher ainsi, jamais en
                // prose. Contrairement à l'ancien format (liste numérotée plate), ce
                // formatage est du Kotlin déterministe, pas une consigne que l'IA doit se
                // rappeler de respecter à chaque tour -- voir aussi le retrait de
                // today_events/upcoming_events/search_event de INFORMATIONAL_ACTIONS
                // (JarvisCommandParser) pour que ce texte ne soit plus jamais réécrit en
                // réponse orale sans mise en forme.
                //
                // UNE LIGNE PAR ÉVÉNEMENT (demande utilisateur : "pour que l'affichage soit
                // plus rapide à lire") -- avant ce format, chaque événement prenait jusqu'à 4
                // lignes (heure+titre, calendrier, localisation, ligne vide), obligeant à
                // scroller/scanner beaucoup pour un planning chargé. L'ID reste présent (le
                // modèle en a besoin pour update_event/delete_event en suivi) mais poussé en
                // fin de ligne sous une forme courte "#12345" plutôt que "(ID: 12345)", pour
                // ne pas interrompre visuellement la lecture heure → titre → lieu. La ligne
                // vide ne sépare plus que deux JOURS différents, jamais deux événements du
                // même jour.
                data class Row(val eventId: Long, val eventTitle: String, val dtStart: Long, val location: String, val calendarId: Long)
                val rows = mutableListOf<Row>()
                while (c.moveToNext()) {
                    rows.add(Row(c.getLong(0), c.getString(1) ?: "Sans titre", c.getLong(2), c.getString(3) ?: "", c.getLong(4)))
                }
                val distinctCalendarCount = rows.map { it.calendarId }.distinct().size
                val dayFmt = SimpleDateFormat("dd/MM", Locale.FRENCH)
                val timeFmt = SimpleDateFormat("HH'h'mm", Locale.FRENCH)

                val sb = StringBuilder("$title\n\n")
                var lastDay: String? = null
                rows.forEach { row ->
                    val day = dayFmt.format(Date(row.dtStart))
                    if (day != lastDay) {
                        if (lastDay != null) sb.append("\n")
                        sb.append("🔹 $day\n")
                        lastDay = day
                    }
                    val timeStr = timeFmt.format(Date(row.dtStart))
                    sb.append("🕐 $timeStr — ${row.eventTitle}")
                    if (row.location.isNotBlank()) {
                        val locationPrefixed = if (row.location.trimStart().startsWith("🏠")) row.location else "🏠 ${row.location}"
                        sb.append(" 📍 $locationPrefixed")
                    }
                    if (distinctCalendarCount > 1) {
                        val calendarName = calendarNames[row.calendarId] ?: "Calendrier inconnu"
                        sb.append(" · $calendarName")
                    }
                    sb.append(" (#${row.eventId})\n")
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de l'accès à l'agenda."
        } catch (e: Exception) {
            "❌ Erreur lors de la lecture de l'agenda : ${e.message}"
        }
    }

    fun createEvent(
        context: Context,
        title: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        description: String = "",
        location: String = "",
        calendarRef: String? = null
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        val calendarId = resolveCalendarId(context, calendarRef)
            ?: return if (calendarRef.isNullOrBlank())
                "❌ Aucun calendrier disponible sur cet appareil pour ajouter l'événement."
            else
                "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles, ou omets le paramètre calendar pour utiliser le calendrier par défaut."

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val sdf = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRENCH)
                // Rappelle explicitement le calendrier ciblé — évite qu'un événement parte
                // silencieusement sur le mauvais calendrier (ex: un calendrier local du
                // fabricant plutôt que Google) sans que l'utilisateur puisse s'en rendre compte.
                val calendarName = buildCalendarNameMap(context)[calendarId] ?: "calendrier par défaut"
                "✅ Événement **$title** créé avec succès pour le ${sdf.format(Date(startTimeMillis))} ! (calendrier : $calendarName)"
            } else {
                "❌ Impossible de créer l'événement."
            }
        } catch (e: Exception) {
            "❌ Échec de la création de l'événement : ${e.message}"
        }
    }

    fun deleteEvent(context: Context, eventId: Long): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        return try {
            val existedBefore = getEventDetails(context, eventId) != null
            val rows = context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            when {
                rows > 0 -> "🗑️ Événement supprimé."
                !existedBefore -> "❌ Événement introuvable (ID $eventId) — relance search_event/today_events pour récupérer un ID à jour."
                else -> diagnoseWriteFailure(context, eventId, "suppression")
            }
        } catch (e: Exception) {
            "❌ Erreur lors de la suppression : ${e.message}"
        }
    }

    /**
     * L'événement existe bien (vérifié juste avant) mais l'update()/delete() a affecté
     * 0 ligne : sur certains Android personnalisés (MIUI/Xiaomi notamment), la modification
     * d'un événement par une appli tierce peut être silencieusement bloquée par une
     * restriction système supplémentaire (distincte de la permission WRITE_CALENDAR standard,
     * déjà accordée ici) plutôt que de lever une exception — d'où "0 ligne modifiée" sans
     * erreur exploitable. On identifie le calendrier concerné pour donner une piste concrète.
     */
    private fun diagnoseWriteFailure(context: Context, eventId: Long, action: String): String {
        val calendarId = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.CALENDAR_ID),
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } catch (_: Exception) { null }
        val isGoogle = calendarId != null && getGoogleCalendarIds(context).contains(calendarId)
        val calendarName = calendarId?.let { buildCalendarNameMap(context)[it] } ?: "inconnu"
        return if (isGoogle) {
            "⚠️ La $action a été refusée par le système alors que l'événement existe (calendrier : $calendarName). " +
                "Sur certains téléphones (Xiaomi/MIUI notamment), il faut activer manuellement : Paramètres > " +
                "Applications > JARVIS > Autorisations supplémentaires > activer « Modifier l'agenda » / « Autostart », " +
                "en plus de la permission agenda standard déjà accordée."
        } else {
            "⚠️ La $action a échoué : cet événement vit sur le calendrier local « $calendarName » (pas un compte Google), " +
                "que MIUI/le fabricant protège souvent contre l'écriture par des applis tierces. Recrée-le plutôt sur un " +
                "calendrier Google (list_calendars pour voir les calendriers disponibles)."
        }
    }

    /**
     * Modifie un événement existant : renommer, changer les horaires,
     * le lieu ou la description. Seuls les champs fournis (non nuls)
     * sont modifiés, les autres restent inchangés.
     */
    fun updateEvent(
        context: Context,
        eventId: Long,
        newTitle: String? = null,
        newStartTimeMillis: Long? = null,
        newEndTimeMillis: Long? = null,
        newDescription: String? = null,
        newLocation: String? = null
    ): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de modification de l'agenda non accordée."
        }

        return try {
            val values = ContentValues().apply {
                newTitle?.let { put(CalendarContract.Events.TITLE, it) }
                newStartTimeMillis?.let { put(CalendarContract.Events.DTSTART, it) }
                newEndTimeMillis?.let { put(CalendarContract.Events.DTEND, it) }
                newDescription?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                newLocation?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            if (values.size() == 0) return "❌ Aucune modification à appliquer."

            val existedBefore = getEventDetails(context, eventId) != null
            val rows = context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString())
            )
            when {
                rows > 0 -> "✏️ Événement mis à jour avec succès."
                !existedBefore -> "❌ Événement introuvable (ID $eventId) — relance search_event/today_events pour récupérer un ID à jour."
                else -> diagnoseWriteFailure(context, eventId, "modification")
            }
        } catch (e: Exception) {
            "❌ Erreur lors de la modification : ${e.message}"
        }
    }

    // Termes génériques décrivant la FONCTIONNALITÉ elle-même plutôt qu'un vrai événement
    // recherché -- même bug que côté contacts (signalement utilisateur : "pareil pour
    // planning") : l'IA extrait parfois littéralement un mot comme "planning" ou "rendez-vous"
    // de la phrase de l'utilisateur au lieu du VRAI titre d'événement recherché, ce qui
    // matchait alors (quasi) tous les événements dont le titre contient ce mot générique.
    private val GENERIC_EVENT_QUERIES = setOf(
        "planning", "calendrier", "agenda", "evenement", "evenements", "rendez-vous", "rdv"
    )

    fun searchEvents(context: Context, query: String, calendarRef: String? = null): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }
        val normalizedQuery = query.trim().lowercase()
            .replace("é", "e").replace("è", "e").replace("ê", "e")
            .replace("à", "a").replace("â", "a")
        if (normalizedQuery in GENERIC_EVENT_QUERIES) {
            return "❌ « $query » est trop général pour cibler un événement précis — utilise plutôt today_events/upcoming_events/week_events pour voir le planning, ou donne le VRAI titre (même partiel) de l'événement recherché."
        }

        var filterCalendarId: Long? = null
        if (!calendarRef.isNullOrBlank()) {
            filterCalendarId = findCalendarId(context, calendarRef)
            if (filterCalendarId == null) {
                return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles."
            }
        } else {
            // Meme repli que getEventsTimeRange : calendrier par defaut memorise si defini.
            filterCalendarId = Prefs.getDefaultCalendarId(context)
        }

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION
        )

        return try {
            var selection = "${CalendarContract.Events.TITLE} LIKE ? AND ${CalendarContract.Events.DELETED} = 0"
            val argsList = mutableListOf("%$query%")
            if (filterCalendarId != null) {
                selection += " AND ${CalendarContract.Events.CALENDAR_ID} = ?"
                argsList.add(filterCalendarId.toString())
            } else {
                // Meme regle par defaut que today_events/upcoming_events : uniquement Google
                // Agenda tant que l'utilisateur ne demande pas explicitement un autre calendrier.
                val googleIds = getGoogleCalendarIds(context)
                if (googleIds.isNotEmpty()) {
                    selection += " AND ${CalendarContract.Events.CALENDAR_ID} IN (${googleIds.joinToString(",")})"
                }
            }
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                argsList.toTypedArray(),
                "${CalendarContract.Events.DTSTART} DESC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "🔍 Aucun événement trouvé pour « $query »."

                // Une ligne par résultat (même raisonnement que getEventsTimeRange ci-dessus --
                // demande utilisateur : affichage plus rapide à lire), ID compact en fin de ligne.
                val sb = StringBuilder("🔍 **Résultats de recherche dans l'agenda pour « $query »** :\n\n")
                val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRENCH)
                var idx = 0

                while (c.moveToNext() && idx < 10) {
                    val eventId = c.getLong(0)
                    val title = c.getString(1) ?: "Sans titre"
                    val date = c.getLong(2)
                    val location = c.getString(3) ?: ""

                    sb.append("${idx + 1}. $title — ${sdf.format(Date(date))}")
                    if (location.isNotBlank()) {
                        // Même préfixe 🏠 que les fiches contact : rend l'adresse cliquable
                        // (voir la règle "adresses postales toujours cliquables" du prompt).
                        val locationPrefixed = if (location.trimStart().startsWith("🏠")) location else "🏠 $location"
                        sb.append(" 📍 $locationPrefixed")
                    }
                    sb.append(" (#$eventId)\n")
                    idx++
                }
                sb.toString().trimEnd()
            } ?: "❌ Échec de la recherche dans l'agenda."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    /** Vérifie EMPIRIQUEMENT si un calendrier a de vraies occurrences dans les ~90 prochains
     *  jours, en interrogeant directement Instances -- plutôt que de se fier uniquement aux
     *  colonnes Calendars.SYNC_EVENTS/VISIBLE (voir getCalendarList). Signalement utilisateur
     *  répété : "il les détecte mais me dit non synchronisé/introuvable/masqué alors qu'ils ne
     *  le sont pas sur le smartphone" -- ces deux colonnes sont notoirement PEU FIABLES sur
     *  certains Android personnalisés (MIUI/Xiaomi notamment, déjà rencontré ailleurs dans ce
     *  fichier) et certains comptes non-Google : elles peuvent rester à 0 alors que le
     *  calendrier s'affiche normalement dans l'appli d'agenda et contient bien des événements
     *  réels et lisibles. Se fier à un test réel évite de désinformer l'utilisateur sur un
     *  calendrier qui, en pratique, fonctionne très bien. */
    private fun hasRealUpcomingInstances(context: Context, calendarId: Long): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val horizon = now + 90L * 24 * 60 * 60 * 1000
            val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(uriBuilder, now - 30L * 24 * 60 * 60 * 1000) // 30 j en arrière aussi, au cas où
            ContentUris.appendId(uriBuilder, horizon)
            context.contentResolver.query(
                uriBuilder.build(),
                arrayOf(CalendarContract.Instances.EVENT_ID),
                "${CalendarContract.Instances.CALENDAR_ID} = ?",
                arrayOf(calendarId.toString()),
                null
            )?.use { c -> c.count > 0 } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun getCalendarList(context: Context): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission de lecture de l'agenda non accordée."
        }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.SYNC_EVENTS,
            CalendarContract.Calendars.VISIBLE
        )

        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC"
            )

            cursor?.use { c ->
                if (c.count == 0) return "📅 Aucun calendrier disponible."

                val sb = StringBuilder("📅 **Calendriers disponibles** :\n\n")
                var hasRealSyncIssue = false
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: "Inconnu"
                    val account = c.getString(2) ?: "?"
                    val syncEvents = c.getInt(4) != 0
                    val visible = c.getInt(5) != 0
                    val nickname = Prefs.getCalendarNickname(context, id)
                    val nicknameStr = if (nickname.isNotBlank()) " — surnom : « $nickname »" else ""
                    sb.append("• **$name** (compte : $account, ID: $id)$nicknameStr\n")
                    // SYNC_EVENTS/VISIBLE à 0 côté Android n'est qu'un DRAPEAU, pas une preuve --
                    // avant d'afficher un avertissement, on vérifie s'il existe malgré tout de
                    // VRAIES occurrences lisibles (hasRealUpcomingInstances ci-dessus). Si oui,
                    // le drapeau est visiblement périmé/peu fiable sur cet appareil : on le dit
                    // clairement au lieu de laisser croire à un vrai problème de synchronisation.
                    if (!syncEvents || !visible) {
                        val reallyWorks = hasRealUpcomingInstances(context, id)
                        if (reallyWorks) {
                            sb.append("   🟡 Android signale ce calendrier comme désynchronisé/masqué, mais JARVIS y trouve bien de vrais événements à venir — ce signalement est probablement obsolète sur cet appareil, ignore-le : les événements sont lisibles normalement.\n")
                        } else if (!syncEvents) {
                            hasRealSyncIssue = true
                            sb.append("   ⚠️ Synchronisation désactivée pour ce calendrier ET aucun événement à venir trouvé — JARVIS ne peut voir aucun de ses événements tant que ce n'est pas corrigé (voir note ci-dessous).\n")
                        } else {
                            sb.append("   ⚠️ Calendrier masqué (non visible) et aucun événement à venir trouvé — vérifie qu'il est bien coché dans ton appli d'agenda.\n")
                        }
                    }
                }
                if (hasRealSyncIssue) {
                    sb.append(
                        "\n🔧 Pour activer un calendrier marqué « synchronisation désactivée » : ouvre l'appli " +
                            "Google Agenda (ou l'appli concernée) → Paramètres → sélectionne ce calendrier " +
                            "précis (pas juste le compte) → active « Synchroniser » — c'est un réglage séparé " +
                            "de la simple visibilité, propre à Android, rien à voir avec un bug de JARVIS. " +
                            "Une fois activé, les événements deviennent immédiatement lisibles par JARVIS. " +
                            "Tu peux aussi demander à JARVIS de forcer l'activation directement avec " +
                            "sync_calendar, sans passer par l'appli d'agenda.\n"
                    )
                }
                sb.append(
                    "\n💡 Pour distinguer deux calendriers similaires, donne-leur un surnom avec " +
                        "l'action name_calendar — tu peux référencer le calendrier par son nom affiché, " +
                        "son compte (email), ou son ID (ex : « appelle le calendrier de untel@gmail.com 'Perso' »). " +
                        "Ensuite utilise ce surnom comme paramètre calendar dans today_events/upcoming_events/search_event " +
                        "pour n'afficher que ce planning précis."
                )
                sb.toString()
            } ?: "❌ Erreur lors de la récupération des calendriers."
        } catch (e: Exception) {
            "❌ Erreur : ${e.message}"
        }
    }

    data class EventDetails(
        val id: Long,
        val title: String,
        val startMillis: Long,
        val location: String,
        val description: String
    )

    /** Récupère les détails complets d'un événement (utilisé notamment pour créer une fiche client depuis un RDV). */
    fun getEventDetails(context: Context, eventId: Long): EventDetails? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                "${CalendarContract.Events._ID} = ?",
                arrayOf(eventId.toString()),
                null
            )?.use { c ->
                if (!c.moveToFirst()) return null
                EventDetails(
                    id = eventId,
                    title = c.getString(1) ?: "Sans titre",
                    startMillis = c.getLong(2),
                    location = c.getString(3) ?: "",
                    description = c.getString(4) ?: ""
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Efface tous les surnoms de calendrier (name_calendar) enregistrés. Ils sont stockés
     * dans les préférences de l'app, indépendamment du vault Obsidian — un "réinitialiser
     * Obsidian" ne les efface donc jamais, il faut passer par ici explicitement.
     */
    fun resetCalendarNicknames(context: Context): String {
        val count = Prefs.clearAllCalendarNicknames(context)
        return if (count == 0) "ℹ️ Aucun surnom de calendrier n'était enregistré."
        else "✅ $count surnom(s) de calendrier effacé(s). Les calendriers seront à nouveau identifiés par leur nom/compte d'origine."
    }

    /**
     * Active ou réactive la synchronisation ET la visibilité d'un calendrier directement
     * via le ContentProvider Android (CalendarContract.Calendars.SYNC_EVENTS / VISIBLE),
     * sans passer par l'appli Google Agenda. L'app possède déjà la permission WRITE_CALENDAR.
     * Cible typique : un planning partagé (Skello, calendrier d'équipe...) dont le compte
     * synchronise bien avec le téléphone, mais dont CE calendrier précis a SYNC_EVENTS=0 —
     * cause la plus fréquente d'un planning "invisible pour JARVIS mais visible sur le site
     * Skello / dans Google Agenda web". Écrire directement ce champ évite à l'utilisateur de
     * devoir chercher le bon réglage caché dans les paramètres de l'appli d'agenda.
     */
    fun syncCalendar(context: Context, calendarRef: String, enable: Boolean): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "❌ Permission d'écriture de l'agenda non accordée — active-la dans les paramètres de l'app."
        }
        val id = findCalendarId(context, calendarRef)
            ?: return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les calendriers disponibles."

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.SYNC_EVENTS, if (enable) 1 else 0)
                put(CalendarContract.Calendars.VISIBLE, if (enable) 1 else 0)
            }
            val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, id)
            val rows = context.contentResolver.update(uri, values, null, null)
            val name = buildCalendarNameMap(context)[id] ?: calendarRef
            if (rows > 0) {
                if (enable) {
                    "✅ Synchronisation activée pour « $name ». Si les événements n'apparaissent pas immédiatement, " +
                        "cela peut prendre quelques minutes le temps qu'Android resynchronise ce calendrier avec le serveur — " +
                        "sinon relance simplement today_events ou upcoming_events dans une minute."
                } else {
                    "✅ Synchronisation désactivée pour « $name »."
                }
            } else {
                "⚠️ Aucune ligne modifiée — soit le calendrier était déjà dans cet état, soit le compte associé " +
                    "(${buildCalendarNameMap(context)[id]}) refuse l'écriture directe (certains comptes gérés type " +
                    "Exchange/Google readonly-sync bloquent ce champ ; dans ce cas, seule l'appli Google Agenda peut le modifier)."
            }
        } catch (e: Exception) {
            "❌ Erreur lors de la modification du calendrier : ${e.message}"
        }
    }

    /**
     * Attribue un surnom mémorisable à un calendrier (ex: "Perso", "Boulot"), pour le
     * distinguer facilement. [calendarRef] accepte un ID numérique, un surnom déjà
     * existant, ou un nom affiché / nom de compte (recherche partielle, insensible à
     * la casse) — pas besoin de connaître l'ID à l'avance.
     */
    fun nameCalendar(context: Context, calendarRef: String, nickname: String): String {
        val id = findCalendarId(context, calendarRef)
            ?: return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les noms/comptes disponibles."
        Prefs.saveCalendarNickname(context, id, nickname)
        val currentName = buildCalendarNameMap(context)[id] ?: calendarRef
        return "✅ Le calendrier « $currentName » s'appellera désormais « $nickname »."
    }

    /**
     * Mémorise [calendarRef] (ID, surnom, nom affiché ou compte -- même résolution que
     * name_calendar/sync_calendar) comme "MON planning" par défaut : today_events/
     * upcoming_events/week_events/search_event l'utiliseront automatiquement dès que
     * l'utilisateur ne précise aucun calendrier explicite dans sa demande (voir
     * Prefs.getDefaultCalendarId, consommé dans getEventsTimeRange/searchEvents ci-dessus).
     * Demande utilisateur : "quand je lui demande mon planning il m'affiche un planning
     * spécifique, comme un surnom" -- persiste ce choix une bonne fois pour toutes, sans
     * avoir à repréciser le calendrier à chaque demande.
     */
    fun setDefaultCalendar(context: Context, calendarRef: String): String {
        val id = findCalendarId(context, calendarRef)
            ?: return "❌ Calendrier « $calendarRef » introuvable. Utilise list_calendars pour voir les noms/comptes disponibles, puis donne-lui un surnom avec name_calendar si besoin."
        Prefs.setDefaultCalendarId(context, id)
        val name = buildCalendarNameMap(context)[id] ?: calendarRef
        return "✅ « $name » est maintenant TON planning par défaut — dis simplement « mon planning » ou « aujourd'hui » et JARVIS l'utilisera automatiquement, sans avoir à le repréciser à chaque fois."
    }

    /** Retire le calendrier par défaut mémorisé (voir setDefaultCalendar) -- retombe alors
     *  sur le repli habituel (tous les calendriers Google configurés). */
    fun resetDefaultCalendar(context: Context): String {
        val had = Prefs.getDefaultCalendarId(context) != null
        Prefs.setDefaultCalendarId(context, null)
        return if (had) "✅ Planning par défaut retiré — JARVIS reviendra à tous tes calendriers Google par défaut."
        else "ℹ️ Aucun planning par défaut n'était défini."
    }

    /**
     * Résout un identifiant de calendrier à partir d'un surnom, d'un nom affiché, d'un
     * compte, ou d'un ID numérique direct. Retourne null si [calendarRef] est fourni
     * mais ne correspond à AUCUN calendrier — ne retombe JAMAIS silencieusement sur un
     * autre calendrier, pour éviter d'afficher les événements du mauvais planning sans
     * prévenir.
     */
    private fun findCalendarId(context: Context, calendarRef: String): Long? {
        calendarRef.toLongOrNull()?.let { id ->
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) return id }
        }

        Prefs.findCalendarIdByNickname(context, calendarRef)?.let { return it }

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: ""
                val account = c.getString(2) ?: ""
                if (name.contains(calendarRef, ignoreCase = true) || account.contains(calendarRef, ignoreCase = true)) {
                    return c.getLong(0)
                }
            }
        }
        return null
    }

    /** Comme [findCalendarId], mais une référence vide/absente renvoie le calendrier par défaut de l'appareil. */
    private fun resolveCalendarId(context: Context, calendarRef: String?): Long? {
        if (calendarRef.isNullOrBlank()) return getDefaultCalendarId(context)
        return findCalendarId(context, calendarRef)
    }

    /**
     * BUG RÉEL CORRIGÉ : sans filtre ni tri, cette requête renvoyait le TOUT PREMIER calendrier
     * de la table Calendars — souvent un calendrier LOCAL créé par l'appli d'agenda du
     * fabricant (ex: Xiaomi/MIUI, un calendrier account_type="LOCAL" propre à l'appareil)
     * plutôt que le calendrier du compte Google. C'est exactement ce qui a été signalé : les
     * événements créés par JARVIS apparaissaient dans l'appli Agenda Xiaomi mais jamais dans
     * Google Agenda — un calendrier "LOCAL" n'est PAS synchronisé vers les serveurs Google par
     * définition, aucune app (JARVIS ou autre) ne peut le rendre visible ailleurs après coup.
     *
     * On choisit maintenant explicitement, par ordre de préférence :
     *  1. Le calendrier Google DE L'UTILISATEUR (account_type="com.google" ET
     *     owner_account == account_name — exclut un calendrier Google partagé/abonné qui
     *     n'est pas le sien), avec la synchronisation active.
     *  2. N'importe quel calendrier Google synchronisé, à défaut du 1er cas.
     *  3. N'importe quel calendrier synchronisé (autre compte : Outlook, Samsung...).
     *  4. Le tout premier calendrier trouvé, en dernier repli (comportement historique,
     *     garantit que la création d'événement continue de fonctionner même sans aucun
     *     compte cloud configuré).
     */
    /**
     * Renvoie les IDs de tous les calendriers rattaches a un compte Google (account_type ==
     * "com.google"), synchronises ou non - utilise pour restreindre par defaut la LECTURE
     * (today_events/upcoming_events/search_event) et exclure tout calendrier LOCAL du
     * fabricant (Xiaomi/MIUI...) tant que l'utilisateur ne cible pas explicitement un autre
     * calendrier via le parametre "calendar". Voir aussi getDefaultCalendarId (choix du
     * calendrier de destination pour la CREATION d'evenement), qui applique une preference
     * similaire.
     */
    private fun getGoogleCalendarIds(context: Context): List<Long> {
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == "com.google") ids.add(c.getLong(0))
                }
            }
        } catch (_: Exception) { /* liste vide en cas d'erreur, pas bloquant */ }
        return ids
    }

    private fun getDefaultCalendarId(context: Context): Long? {
        data class Candidate(val id: Long, val accountType: String, val isOwn: Boolean, val syncEvents: Boolean)
        val candidates = mutableListOf<Candidate>()
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.Calendars.ACCOUNT_NAME,
                CalendarContract.Calendars.OWNER_ACCOUNT,
                CalendarContract.Calendars.SYNC_EVENTS
            ),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val accountName = c.getString(2) ?: ""
                val owner = c.getString(3) ?: ""
                candidates.add(
                    Candidate(
                        id = c.getLong(0),
                        accountType = c.getString(1) ?: "",
                        isOwn = owner.isNotBlank() && owner == accountName,
                        syncEvents = c.getInt(4) != 0
                    )
                )
            }
        }
        if (candidates.isEmpty()) return null

        return candidates.firstOrNull { it.accountType == "com.google" && it.isOwn && it.syncEvents }?.id
            ?: candidates.firstOrNull { it.accountType == "com.google" && it.syncEvents }?.id
            ?: candidates.firstOrNull { it.syncEvents }?.id
            ?: candidates.first().id
    }
}
