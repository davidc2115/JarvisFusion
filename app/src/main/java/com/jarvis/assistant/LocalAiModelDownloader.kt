package com.jarvis.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Portage Jarvis2/ai/ModelDownloader.kt (fusion Phase 4g). Petit utilitaire
 * de telechargement partage par tous les moteurs IA locaux qui recuperent
 * leur modele depuis une URL au premier lancement (au lieu d'etre embarque
 * dans l'APK/le depot Git, ce qui depasserait largement la limite de
 * 100 Mo par fichier de GitHub).
 *
 * Telecharge en streaming vers un fichier temporaire ".part" a cote de la
 * destination finale, puis renomme atomiquement une fois complet -- si le
 * telechargement est interrompu (app tuee, perte reseau), le prochain appel
 * repart de zero plutot que de garder un fichier corrompu a moitie ecrit.
 */
object LocalAiModelDownloader {

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /**
     * Telecharge [url] vers [destFile] si ce dernier n'existe pas deja (ou
     * si sa taille ne correspond pas a [expectedSizeBytes] quand elle est
     * fournie -- utile pour detecter un fichier precedemment tronque).
     */
    suspend fun downloadIfMissing(
        url: String,
        destFile: File,
        headers: Map<String, String> = emptyMap(),
        expectedSizeBytes: Long? = null,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (destFile.exists() && (expectedSizeBytes == null || destFile.length() == expectedSizeBytes)) {
                return@runCatching
            }
            destFile.parentFile?.mkdirs()
            val partFile = File(destFile.parentFile, "${destFile.name}.part")
            if (partFile.exists()) partFile.delete()

            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (name, value) -> requestBuilder.addHeader(name, value) }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException(
                        "Echec telechargement (${response.code}) pour $url" +
                            if (response.code == 401 || response.code == 403) {
                                " -- modele probablement soumis a licence : jeton d'acces manquant ou invalide."
                            } else "",
                    )
                }
                val body = response.body ?: throw IllegalStateException("Reponse vide pour $url")
                val total = body.contentLength()
                var done = 0L
                body.byteStream().use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
            if (!partFile.renameTo(destFile)) {
                partFile.copyTo(destFile, overwrite = true)
                partFile.delete()
            }
        }
    }
}
