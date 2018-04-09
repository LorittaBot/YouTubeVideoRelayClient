package net.pocketdreams.youtubevideorelayclient

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import org.jsoup.Jsoup
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
	val jsonParser = JsonParser()
	val logger = LoggerFactory.getLogger(YouTubeVideoRelayClient::class.java)

	const val UNINITALIZED_VIDEO_ID = "UNINITALIZED_VIDEO_ID"

	var currentProxy: Pair<String, Int>? = null

	val random = SplittableRandom()

	@JvmStatic
	fun main(args: Array<String>) {
		val serverPort = System.getProperty("serverPort").toInt()

		thread {
			while (true) {
				currentProxy = renewProxy(true)
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
		val proxy = currentProxy!!
		val body = try {
			HttpRequest.post("https://socialblade.com/js/class/youtube-video-recent")
					.useProxy(proxy.first, proxy.second)
					.userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/58.0.3029.110 Safari/537.36 Edge/16.16299")
					.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
					.header("Referer", "https://socialblade.com/youtube/user/${channelId}/videos")
					.header("X-Requested-By", "XMLHttpRequest")
					.header("Cookie", "__cfduid=d8eb65afb271767054576d00182218aeb1521412725; PHPSESSXX=uut0g2n2neku9k2shn4gv56der; _ga=GA1.2.1299151256.1521412730; __auc=84fbb7d71623b43f71244ca80d5; cdmblk=0:0.3:0:0:0:0:0:0:0:0:0:0:0:0,0:0.1:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0; cdmtlk=0:1673:0:2765:2754:0:0:0:1627:0:2517:0:584:2870; cdmgeo=br; cdmbaserate=2.1; cdmbaseraterow=1.1; cdmblk2=0:0.3:0:0:0:0:0:0:0:0:0:0:0:0,0:0.1:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0,0:0:0:0:0:0:0:0:0:0:0:0:0:0; __gads=ID=37ed71a5ac32c77d:T=1521412740:S=ALNI_MaOoy_bzC8Bo5uF4xG9Djei6zJWKA; _cb_ls=1; _cb=1zc2ZB_jLZ_CnTw_z; _chartbeat2=.1521412746174.1522517094159.11100100001001.V7b9UhCSjlBAQB5dB9V2p9DzsAoL; cdmint=0; GCLB=CKHhmvX0rdj7Gw; cdmu=1522492920149; __asc=79639a941627d1212b879a2fc8b; _gid=GA1.2.1508594638.1522516761; OX_sd=10; OX_plg=pm; _cb_svref=https%3A%2F%2Fsocialblade.com%2F; _gat_socialblade=1; _gat_curseTracker=1; _chartbeat4=t=DZK8a4DQIclB1c0loBAXZRaJEX7X&E=2&EE=2&x=662&c=0.28&y=3054&w=180")
					.connectTimeout(5000) // Um valor no mínimo "aceitável" para consumo
					.readTimeout(5000)
					.form("channelid", channelId)
					.body()
		} catch (e: HttpRequest.HttpRequestException) {
			logger.error("Erro ao utilizar proxy ${proxy.first}:${proxy.second}", e)
			currentProxy = renewProxy()
			return 900000
		}

		try {
			val payload = jsonParser.parse(body).array

			// Se o payload.size() == 0, ou o canal não tem vídeo ou o canal não existe
			// caso isto seja verdade, nós iremos ignorar os updates deste canal por uma hora
			if (payload.size() == 0) {
				logger.info("Canal $channelId não tem nenhum vídeo ou o canal não existe! Esperando 15 minutos antes de verificar novamente...")
				return 900000
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
			val days = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS)

			val percentage = Math.min((Math.max(TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS) - 3, 0).toDouble()) / 7, 1.0)

			val count = if (channelId == "UC-eeXSRZ8cO-i2NZYrWGDnQ") { // Utilizado apenas para testes, "MrPowerGamerBR"
				0
			} else {
				((900000 * (percentage)).toInt())
			} + random.nextInt(500, 5001) // delay aleatório apenas para evitar problemas

			if (channelMap[channelId] == UNINITALIZED_VIDEO_ID) {
				logger.info("$days dias | ${channelMap.values.count { it != UNINITALIZED_VIDEO_ID }} canais | Canal adicionado: $channelId (Último vídeo enviado: \"$lastVideoTitle\" $videoId) ~ Irei esperar ${count} ms (${(count / 1000) / 60} minutos)")
			}

			if (channelMap[channelId] != UNINITALIZED_VIDEO_ID && channelMap[channelId] != videoId) {
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

			return count
		} catch (e: Exception) {
			logger.error("Erro ao pegar informações do canal $channelId - $body", e)
			currentProxy = renewProxy()
			return 900000
		}
	}

	fun renewProxy(random: Boolean = false): Pair<String, Int>? {
		val url = "http://www.gatherproxy.com/proxylist/country/?c=United%20States"

		logger.info("Renovando o proxy ($url) para conectar no Social Blade! Random? $random")

		val document = Jsoup.connect(url)
				.get()

		val classes = document.getElementsByTag("script")

		val firstProxy = if (random) {
			val filtered = classes.filter { it.html().contains("insertPrx") }
			filtered[this.random.nextInt(0, filtered.size)]
		} else {
			classes.firstOrNull { it.html().contains("insertPrx") }
		}

		if (firstProxy != null) {
			val jsonPayload = firstProxy.html().substring(13, firstProxy.html().length - 2)
			val json = JsonParser().parse(jsonPayload)

			val ip = json["PROXY_IP"].string
			val port = Integer.parseInt(json["PROXY_PORT"].string, 16)

			if (currentProxy != null && ip == currentProxy!!.first && port == currentProxy!!.second) { // Se é igual, nós iremos pegar um proxy aleatório da lista
				return renewProxy(true)
			}

			logger.info(json.toString())

			logger.info("Agora irei utilizar o proxy ${json["PROXY_IP"].string}:${Integer.parseInt(json["PROXY_PORT"].string, 16).toInt()}!")

			return Pair(json["PROXY_IP"].string, port)
		}

		logger.info("Oh no, nenhum proxy encontrado!")
		return null
	}
}