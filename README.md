YouTubeVideoRelayClient
-------------
<p align="center">
<a href="https://mrpowergamerbr.com/"><img src="https://img.shields.io/badge/website-mrpowergamerbr-blue.svg"></a>
<a href="https://discord.gg/3rXgN8x"><img src="https://img.shields.io/badge/discord-loritta-yellow.svg"></a>
<a href="https://loritta.website"><img src="https://img.shields.io/badge/website-loritta-blue.svg"></a>
</p>

*Será que é realmente "Client"? Ainda não tenho certeza 😛.*

YouTubeVideoRelayClient serve para verificar novos vídeos de um (ou vários) canais do YouTube, ele utiliza a API (não pública 👀) do Social Blade para verificar novos vídeos, criado para substituir o antigo sistema de verificações de novos vídeos da [Loritta Morenitta](https://github.com/LorittaBot/Loritta), mas pode ser usado para outros aplicativos também.

### Porque a API do Social Blade em vez de usar a API do YouTube ou fazer scrapping da página do canal?

#### API do YouTube
* Tem rate limit (isto é possível "burlar" criando um API key rotation)
* Demora MUITO para atualizar, levando até 30 minutos para ela atualizar os novos vídeos de canais grandes!


#### Scrapping da página do canal

* Demora MUITO para atualizar e é muito inconsistente, as vezes "desaparecendo" vídeos que estavam postados a mais de 6 horas apenas para reaparecer depois!

### Como funciona?

`java -jar YouTubeVideoRelayClient-1.0-SNAPSHOT-jar-with-dependencies.jar -DserverPort=PortaDoSocketServer`

Sendo a `PortaDoSocketServer` a porta do socket server interno do YouTubeVideoRelayClient

Após ele estar ativo e rodando, você pode enviar um JSON payload para o YouTubeVideoRelayClient com os IDs dos canais, exemplo:

```
fun sendToRelayShard(list: List<String>, port: Int) {
	val obj = JsonObject()
	obj["channelIds"] = GSON.toJsonTree(list)
	val s = Socket("127.0.0.1", port)
	val toServer = OutputStreamWriter(s.getOutputStream(), "UTF-8")
	val fromServer = BufferedReader(InputStreamReader(s.getInputStream(), "UTF-8"))

	toServer.write(obj.toString() + "\n")
	toServer.flush()

	val response = fromServer.readLine()
	s.close()
	fromServer.close()
}
```

**Exemplo de Payload:** `{ "channelIds": [ "UC-eeXSRZ8cO-i2NZYrWGDnQ" ] }`

Após tudo estar certo, você irá ver no console do YouTubeVideoRelayClient que ele conseguiu identificar o canal e estará mandando atualizações do canal. (dependendo do envio do último vídeo, ele irá ter um delay menor (ou maior! No máximo 15 minutos) para enviar novos vídeos do canal para outro servidor socket (Atualmente para `127.0.0.1:10699`)

```
[00:55:14.062] [ForkJoinPool.commonPool-worker-7/INFO] n.p.y.YouTubeVideoRelayClient: 6 dias | 528 | Canal adicionado: UCVV6mkF0hGjdV-wre_Hf4qA ~ Irei esperar 771478 ms (12 minutos)
```

Ao chegar um novo vídeo, ele irá enviar um payload com algumas informações do novo vídeo, junto com o ID do canal que causou a atualização.

**Exemplo de Payload:** `{ "channelId": "UC-eeXSRZ8cO-i2NZYrWGDnQ", "videoId": "cK9AWUvqD-I", "title": "CORE FALANDO 🅿️UTARIA?!?!?! 😱 [INÉDITO] [ATUALIZADO 2018]" }`

E é só isto! Sim, ainda falta colocar bastante coisas para serem customizáveis e melhorar o código, mas você está livre para criar forks e modificações em cima do meu código. (desde que você deixe os créditos 😊)
