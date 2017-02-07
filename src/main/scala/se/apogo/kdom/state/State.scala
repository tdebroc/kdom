package se.apogo.kdom.state

import java.time.Instant
import java.util.UUID

import org.slf4j.{Logger, LoggerFactory}
import se.apogo.kdom.{Game, Player}

import scala.collection.concurrent.TrieMap

case class NewGame(uuid: UUID, created: Instant, updated: Instant, numberOfPlayers: Int, joinedPlayers: Set[Player]) {
  def hasEnoughPlayers: Boolean = numberOfPlayers == joinedPlayers.size
}

case class GameState(uuid: UUID, created: Instant, updated: Instant, game: Game)

object State {
  val logger: Logger = LoggerFactory.getLogger(getClass)

  private val gamesById: scala.collection.concurrent.Map[UUID, GameState] = new TrieMap[UUID, GameState]()
  private val newGamesById: scala.collection.concurrent.Map[UUID, NewGame] = new TrieMap[UUID, NewGame]()

  def findGame(uuid: UUID): Option[GameState] = gamesById.get(uuid)

  def createGame(numberOfPlayers: Int): NewGame = {
    newGamesById.synchronized {
      val now = Instant.now
      val uuid = UUID.randomUUID()
      val newGame = NewGame(uuid, now, now, numberOfPlayers, Set.empty)
      newGamesById += (uuid -> newGame)
      newGame
    }
  }

  private def startGame(newGame: NewGame): GameState = {
    require(newGame.hasEnoughPlayers)
    val updatedGameState: GameState = gamesById.synchronized {
      val game = Game.newGame(newGame.joinedPlayers, System.currentTimeMillis())
      val gameState = GameState(newGame.uuid, newGame.created, Instant.now, game)
      gamesById += (newGame.uuid -> gameState)
      gameState
    }

    notifyPlayers(updatedGameState.game)

    updatedGameState
  }

  def updateGame(uuid: UUID, game: Game): GameState = {
    val updatedGameState = gamesById.synchronized {
      require(gamesById.contains(uuid))

      val updatedGameState = gamesById(uuid).copy(game = game, updated = Instant.now)

      gamesById.replace(uuid, updatedGameState)

      updatedGameState
    }

    notifyPlayers(updatedGameState.game)

    updatedGameState
  }

  def joinGame(gameId: UUID, playerName: String, callbackUrl: Option[String]): Option[Player] = {
    newGamesById.synchronized {
      for {
        newGame <- newGamesById.get(gameId)
        if !newGame.hasEnoughPlayers
        player  = Player(playerName, callbackUrl)
      } yield {
        val updatedNewGame = newGame.copy(joinedPlayers = newGame.joinedPlayers + player, updated = Instant.now)
        newGamesById.replace(newGame.uuid, updatedNewGame)
        if (updatedNewGame.hasEnoughPlayers) {
          startGame(updatedNewGame)
        }
        player
      }
    }
  }

  def games: Seq[GameState] = {
    gamesById.synchronized {
      gamesById.values.toSeq.sortBy(_.created)
    }
  }

  def newGames: Seq[NewGame] = {
    newGamesById.synchronized {
      newGamesById.values.filterNot(_.hasEnoughPlayers).toSeq.sortBy(_.created)
    }
  }

  def notifyPlayers(game: Game): Unit = {
    game.players.foreach(notify)
  }

  def notify(player: Player): Unit = {
    import io.shaka.http.Http.http
    import io.shaka.http.Request.{GET, POST}

    for (url <- player.callbackUrl) {
      try {
        http(GET(url))
      } catch {
        case e: Exception => {
          logger.info(s"Could not notify player ${player.name} by calling ${url}: ${e.getMessage}")
        }
      } finally {}
    }
  }
}
