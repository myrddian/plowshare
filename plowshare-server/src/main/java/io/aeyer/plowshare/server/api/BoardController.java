package io.aeyer.plowshare.server.api;

import io.aeyer.plowshare.server.board.Board;
import io.aeyer.plowshare.server.board.BoardTopic;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The operator's budget grant; broader board browsing is the subsequent wire stage. */
@RestController
public class BoardController {
    private final Board board;
    public BoardController(Board board) { this.board = board; }
    public record Topup(Integer maxModelCalls) { }

    @PostMapping("/v1/board/topics/{id}/topup")
    public BoardTopic topup(@PathVariable String id, @RequestBody Topup request) {
        return board.topup(id, request.maxModelCalls());
    }
}
