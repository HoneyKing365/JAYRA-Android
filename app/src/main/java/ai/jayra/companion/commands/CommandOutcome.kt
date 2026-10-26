package ai.jayra.companion.commands

import org.json.JSONObject

sealed interface CommandOutcome {
    data class Success(val result: JSONObject = JSONObject()) : CommandOutcome
    data class Failure(val error: String) : CommandOutcome
}
