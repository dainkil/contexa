package com.contexa.server.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.contexa.server.contract.Caller;
import com.contexa.server.contract.Direction;
import com.contexa.server.contract.EdgeRef;
import com.contexa.server.contract.GraphCandidates;
import com.contexa.server.contract.NodeRef;
import com.contexa.server.contract.NodeType;
import com.contexa.server.gateway.ContextGateway;
import com.contexa.server.gateway.TaskFilter;
import com.contexa.server.identity.IdentityAccess;
import com.contexa.server.identity.NotAllowedException;
import com.contexa.server.identity.UnauthenticatedException;
import com.contexa.server.review.ReviewImpact;

import tools.jackson.databind.ObjectMapper;

/**
 * 외부 AI 에이전트용 출입구(MCP 서버). 안내 데스크의 조회 함수를 도구로 1:1 연다.
 *
 * <p>MCP(Model Context Protocol)의 HTTP 방식 중 가장 단순한 형태를 직접 구현했다:
 * 에이전트가 POST /mcp로 JSON-RPC 요청을 하나 보내면 JSON 응답 하나를 돌려준다.
 * 서버가 먼저 말을 거는 일이 없으므로 연결을 유지하지 않는다(GET /mcp는 405).
 *
 * <ul>
 * <li>에이전트는 OWNER가 발급한 에이전트 토큰으로 들어온다("Authorization: Bearer 토큰").
 * 토큰이 발급된 프로젝트만, 토큰의 범위(외부 공유 문서만 / 구성원 공개 문서까지)만큼만 읽는다.</li>
 * <li>읽기 도구는 화면이 쓰는 것과 같은 안내 데스크를 거친다. 그래서 같은 질문에 같은 답, 같은 확인 상태가 나온다.</li>
 * <li>쓰기는 propose_candidates 하나뿐이다. 낸 것은 모두 "AI 후보"로 저장되고 사람이 검토함에서 확인한다.
 * 에이전트는 확인·정정·상태 변경을 할 수 없다. 그런 도구는 없다.</li>
 * <li>브라우저의 다른 사이트가 이 주소를 부르지 못하게, Origin 헤더가 붙은 요청은 허용 목록에 있을 때만 받는다.</li>
 * </ul>
 */
@RestController
public class McpController {

	private static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26");

	private record Tool(String name, String description, Map<String, Object> inputSchema,
			BiFunction<Caller, Map<String, Object>, Object> handler) {
	}

	private final ContextGateway gateway;
	private final ReviewImpact review;
	private final IdentityAccess identity;
	private final ObjectMapper mapper;
	private final Set<String> allowedOrigins;
	private final Map<String, Tool> tools = new LinkedHashMap<>();

	public McpController(ContextGateway gateway, ReviewImpact review, IdentityAccess identity, ObjectMapper mapper,
			@Value("${contexa.mcp.allowed-origins:}") List<String> allowedOrigins) {
		this.gateway = gateway;
		this.review = review;
		this.identity = identity;
		this.mapper = mapper;
		this.allowedOrigins = Set.copyOf(allowedOrigins);
		registerTools();
	}

	@GetMapping("/mcp")
	public ResponseEntity<Void> noStream() {
		return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header(HttpHeaders.ALLOW, "POST").build();
	}

	@PostMapping("/mcp")
	public ResponseEntity<Object> handle(
			@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestHeader(value = HttpHeaders.ORIGIN, required = false) String origin,
			@RequestHeader(value = "MCP-Protocol-Version", required = false) String protocolVersion,
			@RequestBody Object body) {
		if (origin != null && !allowedOrigins.contains(origin)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Caller caller;
		try {
			caller = identity.authenticateAgent(CallerResolver.bearer(authorization));
		} catch (UnauthenticatedException | NotAllowedException e) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
					.build();
		}
		if (protocolVersion != null && !PROTOCOL_VERSIONS.contains(protocolVersion)) {
			return ResponseEntity.badRequest().body(error(null, -32600, "지원하지 않는 프로토콜 버전: " + protocolVersion));
		}
		if (!(body instanceof Map<?, ?> message) || !"2.0".equals(message.get("jsonrpc"))
				|| !(message.get("method") instanceof String method)) {
			return ResponseEntity.badRequest().body(error(null, -32600, "JSON-RPC 2.0 요청 하나를 보내야 한다"));
		}
		Object id = message.get("id");
		if (id == null) {
			// 알림(답을 기다리지 않는 메시지). 예: notifications/initialized
			return ResponseEntity.accepted().build();
		}
		Map<String, Object> params = asObject(message.get("params"));
		return switch (method) {
			case "initialize" -> ResponseEntity.ok(result(id, initialize(params)));
			case "ping" -> ResponseEntity.ok(result(id, Map.of()));
			case "tools/list" -> ResponseEntity.ok(result(id, Map.of("tools", describeTools())));
			case "tools/call" -> ResponseEntity.ok(call(id, caller, params));
			default -> ResponseEntity.ok(error(id, -32601, "모르는 메서드: " + method));
		};
	}

	// ---- 프로토콜 ----

	private Map<String, Object> initialize(Map<String, Object> params) {
		// 에이전트가 요청한 버전을 지원하면 그대로, 아니면 서버가 아는 가장 최신 버전으로 답한다
		String requested = String.valueOf(params.get("protocolVersion"));
		String version = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
		return Map.of(
				"protocolVersion", version,
				"capabilities", Map.of("tools", Map.of()),
				"serverInfo", Map.of("name", "contexa", "version", "0.1.0"),
				"instructions", """
						Contexa는 프로젝트의 회의 기록에서 나온 결정·조건·할 일·인물과 그 관계를 담고 있다. \
						모든 카드와 관계에는 확인 상태가 있다: CONFIRMED는 사람이 확인한 것, CANDIDATE는 AI 후보다. \
						AI 후보를 확정된 사실처럼 다루지 말 것. 답변서의 open에는 보류·경합·미충족 조건처럼 \
						아직 정리되지 않은 것이 담긴다. propose_candidates로 낸 것은 후보로만 저장되고 사람이 검토한다.""");
	}

	private List<Map<String, Object>> describeTools() {
		List<Map<String, Object>> described = new ArrayList<>();
		for (Tool tool : tools.values()) {
			described.add(Map.of("name", tool.name(), "description", tool.description(),
					"inputSchema", tool.inputSchema()));
		}
		return described;
	}

	private Map<String, Object> call(Object id, Caller caller, Map<String, Object> params) {
		Tool tool = params.get("name") instanceof String name ? tools.get(name) : null;
		if (tool == null) {
			return error(id, -32602, "모르는 도구: " + params.get("name"));
		}
		try {
			Object value = tool.handler().apply(caller, asObject(params.get("arguments")));
			return result(id, Map.of(
					"content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(value))),
					"structuredContent", value,
					"isError", false));
		} catch (RuntimeException e) {
			// 도구를 실행하다 난 문제는 프로토콜 오류가 아니라 도구의 결과로 알린다. 에이전트가 읽고 고쳐서 다시 부를 수 있다
			return result(id, Map.of(
					"content", List.of(Map.of("type", "text", "text", String.valueOf(e.getMessage()))),
					"isError", true));
		}
	}

	private static Map<String, Object> result(Object id, Object result) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("result", result);
		return response;
	}

	private static Map<String, Object> error(Object id, int code, String message) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("jsonrpc", "2.0");
		response.put("id", id);
		response.put("error", Map.of("code", code, "message", message));
		return response;
	}

	// ---- 도구 ----

	private void registerTools() {
		Map<String, Object> nodeType = Map.of("type", "string",
				"enum", List.of("TOPIC", "DECISION_ITEM", "DECISION_EVENT", "CONDITION", "TASK", "ENTITY", "SPAN"));
		Map<String, Object> uuid = Map.of("type", "string", "format", "uuid");
		Map<String, Object> nodeRef = schema(Map.of("type", nodeType, "id", uuid,
				"revision", Map.of("type", "integer", "minimum", 1)), "type", "id", "revision");

		add("current_state", "지금 무엇이 결정됐고 무엇이 남았나. 결정 항목마다 지금 유효한 결정만 담긴다. "
				+ "topicId를 주면 그 안건만, 생략하면 프로젝트 전체.",
				schema(Map.of("topicId", uuid)),
				(caller, args) -> gateway.currentState(caller, optionalUuid(args, "topicId")));
		add("decision_history", "결정 항목 하나가 언제, 어떻게 바뀌었나. 제안과 대체된 결정까지 일어난 순서로 담긴다.",
				schema(Map.of("decisionItemId", uuid), "decisionItemId"),
				(caller, args) -> gateway.decisionHistory(caller, requireUuid(args, "decisionItemId")));
		add("trace", "한 카드에서 관계를 따라간다. direction이 BASIS면 이것이 무엇에 달려 있는지(조건, 근거 발언), "
				+ "IMPACT면 이것을 고치면 무엇이 영향받는지.",
				schema(Map.of("type", nodeType, "id", uuid,
						"direction", Map.of("type", "string", "enum", List.of("BASIS", "IMPACT"), "default", "BASIS"),
						"depth", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 2)), "type", "id"),
				(caller, args) -> gateway.trace(caller,
						new NodeRef(NodeType.valueOf(requireString(args, "type")), requireUuid(args, "id"), 1),
						Direction.valueOf(optionalString(args, "direction", "BASIS")), optionalInt(args, "depth", 2)));
		add("search_evidence", "이 내용이 어느 발언에 있나. 질문과 가까운 발언의 원문과, 그 발언에 기대고 있는 카드를 돌려준다.",
				schema(Map.of("query", Map.of("type", "string"),
						"k", Map.of("type", "integer", "minimum", 1, "maximum", 20, "default", 8)), "query"),
				(caller, args) -> gateway.searchEvidence(caller, requireString(args, "query"),
						optionalInt(args, "k", 8)));
		add("tasks", "누가 무엇을 해야 하나. 할 일과 담당, 걸린 조건, 먼저 끝나야 하는 할 일. "
				+ "assigneeEntityId나 status(PLANNED, IN_PROGRESS, ON_HOLD, DONE, CANCELLED)로 좁힐 수 있다.",
				schema(Map.of("assigneeEntityId", uuid, "status", Map.of("type", "string"))),
				(caller, args) -> gateway.tasks(caller, new TaskFilter(false, optionalUuid(args, "assigneeEntityId"),
						optionalString(args, "status", null))));
		add("resolve_entity", "이 이름 표기가 누구(어느 기관)인가. 해당할 만한 대상을 모두 돌려준다. "
				+ "여럿이면 하나로 고르지 말고 사람에게 확인을 요청할 것.",
				schema(Map.of("surface", Map.of("type", "string"),
						"lang", Map.of("type", "string", "enum", List.of("ko", "en", "ar"))), "surface"),
				(caller, args) -> gateway.resolveEntity(caller, requireString(args, "surface"),
						optionalString(args, "lang", null)));
		add("verify_current", "앞서 읽은 카드·발언·관계가 그사이 바뀌었나. 읽었을 때의 버전과 함께 주면 바뀐 것만 돌려준다. "
				+ "오래 걸리는 작업 끝에 답을 내기 전에 부른다.",
				schema(Map.of("nodes", Map.of("type", "array", "items", nodeRef),
						"edges", Map.of("type", "array", "items", schema(Map.of("id", uuid,
								"version", Map.of("type", "integer", "minimum", 1)), "id", "version")))),
				(caller, args) -> Map.of("stale", gateway.verifyCurrent(caller,
						convertList(args.get("nodes"), NodeRef.class), convertList(args.get("edges"), EdgeRef.class))));
		add("propose_candidates", "카드 후보와 관계 후보를 제안한다. 낸 것은 모두 AI 후보로 저장되고 사람이 검토함에서 확인한다. "
				+ "관계마다 근거 발언(evidenceSpanIds)이 하나 이상 있어야 하고, 실제로 있는 발언이어야 한다. "
				+ "nodes의 항목은 {key, type, fields}, edges의 항목은 {rel, srcKey 또는 srcRef, dstKey 또는 dstRef, evidenceSpanIds}.",
				schema(Map.of("nodes", Map.of("type", "array", "items", Map.of("type", "object")),
						"edges", Map.of("type", "array", "items", Map.of("type", "object")))),
				(caller, args) -> Map.of("reviewIds",
						review.submitCandidates(caller, mapper.convertValue(args, GraphCandidates.class))));
	}

	private void add(String name, String description, Map<String, Object> inputSchema,
			BiFunction<Caller, Map<String, Object>, Object> handler) {
		tools.put(name, new Tool(name, description, inputSchema, handler));
	}

	private static Map<String, Object> schema(Map<String, Object> properties, String... required) {
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		schema.put("required", List.of(required));
		return schema;
	}

	// ---- 인자 읽기 ----

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asObject(Object value) {
		return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
	}

	private <T> List<T> convertList(Object value, Class<T> type) {
		if (!(value instanceof List<?> items)) {
			return List.of();
		}
		List<T> converted = new ArrayList<>();
		items.forEach(item -> converted.add(mapper.convertValue(item, type)));
		return converted;
	}

	private static String requireString(Map<String, Object> args, String name) {
		if (!(args.get(name) instanceof String text) || text.isBlank()) {
			throw new IllegalArgumentException(name + " 값이 필요하다");
		}
		return text.strip();
	}

	private static String optionalString(Map<String, Object> args, String name, String fallback) {
		return args.get(name) instanceof String text && !text.isBlank() ? text.strip() : fallback;
	}

	private static UUID requireUuid(Map<String, Object> args, String name) {
		try {
			return UUID.fromString(requireString(args, name));
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException(name + " 값이 올바른 id가 아니다");
		}
	}

	private static UUID optionalUuid(Map<String, Object> args, String name) {
		return args.get(name) == null ? null : requireUuid(args, name);
	}

	private static int optionalInt(Map<String, Object> args, String name, int fallback) {
		return args.get(name) instanceof Number number ? number.intValue() : fallback;
	}
}
