package net.pocketdreams.youtubevideorelayclient

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object YouTubeVideoRelayClient {
	// Lista de canais (Channel ID + Video ID) a serem verificados
	val channelMap = ConcurrentHashMap<String, String?>()
	// Lista de canais a serem ignorados (por ID inválido, sistema inválido, etc)
	val ignoreChannelIds = ConcurrentHashMap.newKeySet<String>()
	val jsonParser = JsonParser()
	val logger = LoggerFactory.getLogger(YouTubeVideoRelayClient::class.java)

	const val UNINITALIZED_VIDEO_ID = "UNINITALIZED_VIDEO_ID"

	@JvmStatic
	fun main(args: Array<String>) {
		val serverPort = System.getProperty("serverPort").toInt()

		thread {
			val socket = SocketServer(serverPort)
			socket.start()
		}

		thread {
			while (true) {
				Thread.sleep(2500)
			}
		}
	}

	fun createVideoRelay(channelId: String) {
		if (channelMap.containsKey(channelId)) // Já está verificando o canal atual!
			return

		if (ignoreChannelIds.contains(channelId))
			return

		channelMap.put(channelId, UNINITALIZED_VIDEO_ID)

		launch {
			while (true) {
				try {
					// Em vez de criar tudo no escopo atual, nós movemos tudo para um escopo diferente para o Java conseguir realizar GC corretamente
					// isto evita OutOfMemoryExceptions ao rodar o YouTubeVideoRelayClient com pouca memória
					val count = checkChannel(channelId)
					delay(count)
				} catch (e: Exception) {
					logger.error("Erro ao pegar informação do canal $channelId!", e)
				}
			}
		}
	}

	fun checkChannel(channelId: String): Int {
		var body = HttpRequest.get("https://socialblade.com/js/class/youtube-video-recent")
				.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:61.0) Gecko/20100101 Firefox/61.0")
				.form("channelid", channelId)
				.body()

		val payload = jsonParser.parse(body).array

		// Se o payload.size() == 0, ou o canal não tem vídeo ou o canal não existe
		// caso isto seja verdade, nós iremos ignorar os updates deste canal por uma hora
		if (payload.size() == 0) {
			return 3600000
		}

		val lastVideo = payload[0].obj

		val videoId = lastVideo["videoId"].string
		val lastVideoTitle = lastVideo["title"].string
		val createdAt = lastVideo["created_at"].string
		// 900000ms - 15 minutos
		val split = createdAt.split("-")

		val calendar = Calendar.getInstance()
		calendar[Calendar.YEAR] = split[0].toInt()
		calendar[Calendar.MONTH] = split[1].toInt() - 1
		calendar[Calendar.DAY_OF_MONTH] = split[2].toInt()

		val millis = calendar.timeInMillis
		val now = System.currentTimeMillis()
		val diff = now - millis
		val days = Math.max(TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS) - 3, 0)

		val percentage = Math.min((days.toDouble()) / 7, 1.0)

		val count = if (channelId == "UC-eeXSRZ8cO-i2NZYrWGDnQ") { // Utilizado apenas para testes, "MrPowerGamerBR"
			0
		} else {
			((900000 * (percentage)).toInt())
		} + 50 // .05s de delay apenas para evitar problemas

		if (channelMap[channelId] == UNINITALIZED_VIDEO_ID) {
			logger.info("$days dias | ${channelMap.values.count { it != UNINITALIZED_VIDEO_ID }} | Canal adicionado: $channelId ~ Irei esperar ${count} ms (${(count / 1000) / 60} minutos)")
		}

		if (channelMap[channelId] != UNINITALIZED_VIDEO_ID && channelMap[channelId] != videoId) {
			// Anunciar novo vídeo!
			logger.info("$days dias | Anunciando novo vídeo de \"$lastVideoTitle\" $videoId - Vídeo velho: ${channelMap[channelId]}")

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

		return count
	}
}