package net.pocketdreams.youtubevideorelayclient

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.*
import com.google.gson.JsonObject
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.collections.count
import kotlin.collections.set
import kotlin.concurrent.thread

object YouTubeVideoRelayClient {
	// Lista de canais (Channel ID + Video ID) a serem verificados
	val channelMap = ConcurrentHashMap<String, String?>()
	val publishedAt = ConcurrentHashMap<String, Long>()

	val logger = LoggerFactory.getLogger(YouTubeVideoRelayClient::class.java)

	const val UNINITALIZED_VIDEO_ID = "UNINITALIZED_VIDEO_ID"

	val random = SplittableRandom()

	@JvmStatic
	fun main(args: Array<String>) {
		val serverPort = System.getProperty("serverPort").toInt()

		thread {
			while (true) {
				Thread.sleep(57_600_000)
			}
		}

		thread {
			val socket = SocketServer(serverPort)
			socket.start()
		}
	}

	fun createVideoRelay(channelId: String) {
		if (channelMap.containsKey(channelId)) // Já está verificando o canal atual!
			return

		channelMap.put(channelId, UNINITALIZED_VIDEO_ID)

		logger.info("Adicionando canal $channelId para ser verificado...")

		launch {
			while (true) {
				try {
					// Em vez de criar tudo no escopo atual, nós movemos tudo para um escopo diferente para o Java conseguir realizar GC corretamente
					// isto evita OutOfMemoryExceptions ao rodar o YouTubeVideoRelayClient com pouca memória
					val count = checkChannel(channelId)
					delay(count)
				} catch (e: Exception) {
					delay(900000)
				}
			}
		}
	}

	fun checkChannel(channelId: String): Int {
		val body = try {
			HttpRequest.get("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId&time=${random.nextInt(0, 10000)}") // avoid cache
					.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/65.0.3325.181 Safari/537.36")
					.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8")
					.header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
					.header("Cookie", "VISITOR_INFO1_LIVE=SxWHetJ0MQ0; PREF=f1=50000000&f4=4000000")
					.header("Cache-Control", "no-cache")
					.header("Pragma", "no-cache")
					.header("Upgrade-Insecure-Requests", "1")
					.header("x-client-data", "CIS2yQEIprbJAQjEtskBCKmdygEIqKPKAQ==")
					.connectTimeout(5000) // Um valor no mínimo "aceitável" para consumo
					.readTimeout(5000)
					.body()
		} catch (e: HttpRequest.HttpRequestException) {
			logger.error("Erro ao verificar canal $channelId", e)
			return 900000
		}

		try {
			val payload = Jsoup.parse(body, "", Parser.xmlParser());

			val entries = payload.getElementsByTag("entry")
			// Se o payload.size() == 0, ou o canal não tem vídeo ou o canal não existe
			// caso isto seja verdade, nós iremos ignorar os updates deste canal por uma hora
			if (entries.isEmpty()) {
				logger.info("Canal $channelId não tem nenhum vídeo ou o canal não existe! Esperando 15 minutos antes de verificar novamente...")
				return 900000
			}

			val lastVideo = entries.first()

			val videoId = lastVideo.getElementsByTag("yt:videoId").first().html()
			val lastVideoTitle = lastVideo.getElementsByTag("title").first().html()
			val published =lastVideo.getElementsByTag("published").first().html()

			val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
			val createdAt = formatter.parse(published)

			val millis = createdAt.time
			val now = System.currentTimeMillis()
			val diff = now - millis
			val days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS)

			val percentage = Math.min((Math.max(TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS) - 3, 0).toDouble()) / 7, 1.0)

			val count = if (channelId == "UC-eeXSRZ8cO-i2NZYrWGDnQ") { // Utilizado apenas para testes, "MrPowerGamerBR"
				0
			} else {
				((900000 * (percentage)).toInt())
			} + random.nextInt(10000, 25001) // delay aleatório apenas para evitar problemas

			if (channelMap[channelId] == UNINITALIZED_VIDEO_ID) {
				logger.info("$days dias | ${channelMap.values.count { it != UNINITALIZED_VIDEO_ID }} canais | Canal adicionado: $channelId (Último vídeo enviado: \"$lastVideoTitle\" $videoId) ~ Irei esperar ${count} ms (${(count / 1000) / 60} minutos)")
			}

			if (channelMap[channelId] != UNINITALIZED_VIDEO_ID && channelMap[channelId] != videoId && millis > publishedAt[channelMap[channelId]]!!) {
				// Anunciar novo vídeo!
				logger.info("$days dias | ${channelMap.values.count { it != UNINITALIZED_VIDEO_ID }} canais | Anunciando novo vídeo de \"$lastVideoTitle\" $videoId - Vídeo velho: ${channelMap[channelId]}")

				val s = Socket("127.0.0.1", 10699)
				val toServer = OutputStreamWriter(s.getOutputStream(), "UTF-8")
				val fromServer = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))

				val jsonObject = JsonObject()
				jsonObject["videoId"] = videoId
				jsonObject["title"] = lastVideoTitle
				jsonObject["channelId"] = channelId

				toServer.write(jsonObject.toString() + "\n")
				toServer.flush()

				val response = fromServer.readLine()
				s.close()
				fromServer.close()
			}

			channelMap[channelId] = videoId
			publishedAt[videoId] = millis

			return count
		} catch (e: Exception) {
			logger.error("Erro ao pegar informações do canal $channelId - $body", e)
			return 900000
		}
	}
}