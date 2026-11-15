package com.contexa.server.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.ContextPacket;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.EdgeRef;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.contract.StaleRef;
import com.contexa.server.entity.NameCard;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.gateway.TaskFilter;

/**
 * 정문: 결정 정보를 읽는 HTTP 주소. 요청을 안내 데스크의 함수 호출로 옮기기만 한다.
 * 카드나 줄을 직접 읽지 않는다. 읽기는 항상 안내 데스크를 거친다.
 * 요청자({@link Caller})는 {@link CallerResolver}가 토큰에서 만든다.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/context")
public class ContextController {

	private final ContextGateway gateway;

	public ContextController(ContextGateway gateway) {
		this.gateway = gateway;
	}

	/** 한 카드에서 줄을 따라간다. 예: /context/trace?type=TASK&id=...&direction=BASIS&depth=2 */
	@GetMapping("/trace")
	public ContextPacket trace(Caller caller, @RequestParam NodeType type, @RequestParam UUID id,
			@RequestParam(defaultValue = "BASIS") Direction direction, @RequestParam(defaultValue = "2") int depth) {
		return gateway.trace(caller, new NodeRef(type, id, 1), direction, depth);
	}

	/** 지금 무엇이 결정됐고 무엇이 남았나. topicId를 주면 그 안건만 본다. */
	@GetMapping("/state")
	public ContextPacket state(Caller caller, @RequestParam(required = false) UUID topicId) {
		return gateway.currentState(caller, topicId);
	}

	/** 결정 항목 하나가 언제, 어떻게 바뀌었나. */
	@GetMapping("/history/{decisionItemId}")
	public ContextPacket history(Caller caller, @PathVariable UUID decisionItemId) {
		return gateway.decisionHistory(caller, decisionItemId);
	}

	/** 이 내용이 어느 발언에 있나. 찾을 내용은 주소가 아니라 본문으로 보낸다. */
	@PostMapping("/search")
	public ContextPacket search(Caller caller, @RequestBody SearchRequest request) {
		return gateway.searchEvidence(caller, request.query(), request.k() == null ? 8 : request.k());
	}

	/** 누가 무엇을 해야 하나. mine=true면 내게 맡겨진 할 일만. */
	@GetMapping("/tasks")
	public ContextPacket tasks(Caller caller, @RequestParam(defaultValue = "false") boolean mine,
			@RequestParam(required = false) UUID assignee, @RequestParam(required = false) String status) {
		return gateway.tasks(caller, new TaskFilter(mine, assignee, status));
	}

	/** 이 이름 표기가 누구(어느 기관)인가. 후보가 여럿이면 모두 돌려준다. */
	@GetMapping("/entities")
	public ContextPacket entities(Caller caller, @RequestParam String surface,
			@RequestParam(required = false) String lang) {
		return gateway.resolveEntity(caller, surface, lang);
	}

	/** 이름 카드: 인물·기관 한 명과 언어별 표기 전부. */
	@GetMapping("/entities/{entityId}")
	public NameCard nameCard(Caller caller, @PathVariable UUID entityId) {
		return gateway.nameCard(caller, entityId);
	}

	/** 참고했던 카드와 줄이 그사이 바뀌었는지. 바뀐 것만 돌려준다. */
	@PostMapping("/verify")
	public List<StaleRef> verify(Caller caller, @RequestBody VerifyRequest request) {
		return gateway.verifyCurrent(caller, request.nodes(), request.edges());
	}

	public record VerifyRequest(List<NodeRef> nodes, List<EdgeRef> edges) {
	}

	public record SearchRequest(String query, Integer k) {
	}
}
