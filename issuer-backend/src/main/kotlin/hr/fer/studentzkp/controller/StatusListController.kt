package hr.fer.studentzkp.controller

import hr.fer.studentzkp.service.StatusListService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// IETF Token Status List (draft-ietf-oauth-status-list) publisher.
// Phase 1 ships the unsigned JSON representation; Phase 2 wraps it in a JWT
// signed by the issuer key so verifiers can detect tampering.
@RestController
class StatusListController(
    private val statusListService: StatusListService,
) {

    @GetMapping("/statuslist/uni-2026.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun statusList(): Map<String, Any> = statusListService.currentList()
}
