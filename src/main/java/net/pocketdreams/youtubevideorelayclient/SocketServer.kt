package net.pocketdreams.youtubevideorelayclient

import com.github.salomonbrys.kotson.array
import com.github.salomonbrys.kotson.obj
import com.github.salomonbrys.kotson.set
import com.github.salomonbrys.kotson.string
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket

class SocketServer(val socketPort: Int) {
	companion object {
		val jsonParser = JsonParser()
	}

	fun start() {
		val listener = ServerSocket(socketPort)
		try {
			while (true) {
				val socket = listener.accept()
				try {
					val fromClient = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
					val reply = fromClient.readLine()
					val jsonObject = jsonParser.parse(reply).obj

					val channelIds = jsonObject["channelIds"].array

					channelIds.forEach {
						YouTubeVideoRelayClient.createVideoRelay(it.string)
					}

					val response = JsonObject()
					response["type"] = "noop"

					val out = PrintWriter(socket.getOutputStream(), true)
					out.println(response.toString() + "\n")
					out.flush()
					fromClient.close()
				} finally {
					socket.close()
				}
			}
		} finally {
			listener.close()
		}
	}
}