package com.hihops.hops.inquiry.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.hihops.hops.agent.domain.AgentRun;
import com.hihops.hops.agent.service.AgentResultRejectedException;
import com.hihops.hops.inquiry.domain.Inquiry;
import com.hihops.hops.inquiry.domain.InquiryProblem;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.ProblemStatus;
import com.hihops.hops.inquiry.domain.RequestIntent;
import com.hihops.hops.inquiry.domain.StandardRequest;
import com.hihops.hops.inquiry.domain.WorkType;
import com.hihops.hops.inquiry.repository.InquiryProblemRepository;
import com.hihops.hops.inquiry.repository.MessageRepository;
import com.hihops.hops.inquiry.repository.StandardRequestRepository;
import com.hihops.hops.itsystem.domain.ItSystem;
import com.hihops.hops.itsystem.repository.ItSystemRepository;
import com.hihops.hops.ticket.dto.StandardRequestResponse.RequestDetailsResponse;
import com.hihops.hops.ticket.repository.TicketRepository;
import com.hihops.hops.ticket.service.TicketKnowledgeReader;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static com.hihops.hops.inquiry.service.GuideResultWriter.ids;
import static com.hihops.hops.inquiry.service.GuideResultWriter.statements;
import static com.hihops.hops.inquiry.service.GuideResultWriter.text;
import static com.hihops.hops.inquiry.service.GuideResultWriter.uuid;

/**
 * 상담 결과의 문제 제안({@code problemProposals})과 질문({@code questions})을 문의 잠금·결과 반영 거래 안에서 저장한다
 * (docs/DATA.md 11.3, contracts/agents/README.md 상담 답변과 저장 메시지 연결, TARGET_CONTRACT 4절).
 *
 * <p>저장하는 것: 신규 문제({@code new:} 참조)의 {@code inquiry_problem}, 문제별 {@code standard_request} 스냅샷,
 * 질문 묶음({@code payload.questionBundles})과 문제별 질문 수({@code clarify_count}, 최대 5), 사실이 인용한 사용자 메시지와
 * 질문 문구의 문제 연결({@code payload.problemLinks}). AI 결과는 제안일 뿐이라 입력·현재 DB 상태와 다시 대조하고,
 * 어긋나면 {@code INVALID_OUTPUT}으로 거부해 답변도 질문 수도 저장하지 않는다.
 *
 * <p>건드리지 않는 문제: 안내({@link GuideResultWriter})와 티켓 생성 도구({@code TicketCreationService})가 이미 저장한 문제,
 * 티켓이 있거나 해결·확인 대기인 기존 문제(AI가 입력의 기존 문제를 그대로 되풀이해 싣는다). 질문 없이 시스템도 정해진
 * 문제처럼 저장할 상태를 정할 수 없는 제안은 저장하지 않고 호출한 쪽이 미적용으로 남긴다.
 */
@Component
@RequiredArgsConstructor
public class ProblemQuestionWriter {

    private static final Logger log = LoggerFactory.getLogger(ProblemQuestionWriter.class);
    private static final String MESSAGE = "문제·질문 제안을 현재 문의 상태와 대조할 수 없습니다";
    private static final Pattern NEW_REF = Pattern.compile("new:[A-Za-z0-9_-]+");
    private static final int MAX_TITLE_CODE_POINTS = 200;
    private static final int MAX_BUNDLE_REF_LENGTH = 200;
    private static final int MAX_REQUESTED_FIELDS = 10;
    private static final List<ProblemStatus> REOPENABLE = List.of(ProblemStatus.CLARIFYING, ProblemStatus.SYSTEM_PENDING);

    private final InquiryProblemRepository problems;
    private final StandardRequestRepository requests;
    private final MessageRepository messages;
    private final ItSystemRepository systems;
    private final TicketRepository tickets;
    private final TicketKnowledgeReader knowledge;
    private final JdbcTemplate jdbc;
    private final MessageProblemLinker linker;
    private final JsonMapper mapper;

    /**
     * @param handledRefs 이번 결과에서 이미 저장됐거나 다른 저장 경로(안내·티켓)가 맡아 더 저장할 것이 없는 문제 참조
     * @param questions   이번에 새로 저장한 질문 묶음 수
     * @param repeated    같은 (문제, bundleRef)가 이미 저장돼 있어 세지 않고 건너뛴 질문 수
     */
    public record Applied(Set<String> handledRefs, int questions, int repeated) {
        public static final Applied NONE = new Applied(Set.of(), 0, 0);
    }

    /**
     * @param guideRef 이 결과의 안내가 저장한 문제 참조. 안내가 없으면 null
     * @throws AgentResultRejectedException 제안이 입력·현재 상태와 맞지 않을 때 (INVALID_OUTPUT)
     */
    public Applied apply(AgentRun run, Inquiry inquiry, UUID answerId, JsonNode input, JsonNode result,
            @Nullable String guideRef) {
        if (!hasItems(result.path("problemProposals")) && !hasItems(result.path("questions"))) {
            return Applied.NONE;
        }
        try {
            return save(run, inquiry, answerId, input, result, guideRef);
        } catch (AgentResultRejectedException rejected) {
            // 검증이 직접 던진 거부(require·orElseThrow). 사유 코드와 우리 코드의 위치를 남긴다
            log.warn("문제·질문 제안을 거부합니다: runId={}, {} at {}", run.getId(), rejected.getMessage(), appFrame(rejected));
            throw rejected;
        } catch (IllegalStateException exception) {
            // 5회 한도 초과 같은 저장 상태 위반. 형식 오류와 구분해 남긴다
            log.warn("문제·질문 제안을 거부합니다: runId={}, INVALID_STATE ({} at {})", run.getId(),
                    exception.getClass().getSimpleName(), appFrame(exception));
            throw invalid("INVALID_STATE");
        } catch (IllegalArgumentException | JacksonException exception) {
            // UUID·enum·JSON 형식 오류. 예외 문구에는 AI 결과의 값이 들어갈 수 있어 종류와 위치만 남긴다
            log.warn("문제·질문 제안을 거부합니다: runId={}, INVALID_FORMAT ({} at {})", run.getId(),
                    exception.getClass().getSimpleName(), appFrame(exception));
            throw invalid("INVALID_FORMAT");
        }
    }

    // 검증 도우미(이 클래스의 require·invalid, GuideResultWriter에서 빌려 쓰는 text·uuid·ids·statements)는 위치가 아니다
    private static boolean isHelper(StackTraceElement frame) {
        String method = frame.getMethodName();
        if ("require".equals(method) || "invalid".equals(method)) return true;
        return frame.getClassName().endsWith("GuideResultWriter")
                && List.of("text", "uuid", "ids", "statements").contains(method);
    }

    /**
     * 스택에서 이 프로젝트 코드의 첫 프레임. JDK 내부(UUID.fromString 등)나 검증 도우미가 아니라
     * 어느 검증이 거부했는지 보이는 위치를 찾는다.
     */
    static String appFrame(Throwable throwable) {
        for (StackTraceElement frame : throwable.getStackTrace()) {
            if (frame.getClassName().startsWith("com.hihops.hops") && !isHelper(frame)) {
                return frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "."
                        + frame.getMethodName() + ":" + frame.getLineNumber();
            }
        }
        return "unknown";
    }

    private Applied save(AgentRun run, Inquiry inquiry, UUID answerId, JsonNode input, JsonNode result,
            @Nullable String guideRef) {
        var proposals = result.path("problemProposals");
        // 목록 필드가 없거나 null인 결과는 해당 제안이 없는 것으로 본다. 있는데 배열이 아니면 형식 위반이다.
        require(absent(proposals) || proposals.isArray());
        require(absent(result.path("questions")) || result.path("questions").isArray());
        String answer = text(result.path("answer"));

        // 안내·티켓 경로가 문제를 맡은 참조. 같은 문제에 질문을 함께 제안할 수 없다(계약).
        Set<String> owned = new HashSet<>();
        if (guideRef != null) owned.add(guideRef);
        for (var ticket : result.path("ticketProposals")) owned.add(text(ticket.path("problemRef")));
        owned.addAll(jdbc.queryForList("SELECT problem_ref FROM agent_ticket_request WHERE run_id = ?", String.class,
                run.getId()));

        Map<String, JsonNode> questionByRef = new LinkedHashMap<>();
        for (var question : result.path("questions")) {
            String ref = text(question.path("problemRef"));
            require(questionByRef.put(ref, question) == null && !owned.contains(ref), "DUPLICATE_OR_OWNED_QUESTION");
        }
        Set<String> proposalRefs = new LinkedHashSet<>();
        for (var proposal : proposals) require(proposalRefs.add(text(proposal.path("problemRef"))));
        require(proposalRefs.containsAll(questionByRef.keySet()), "QUESTION_WITHOUT_PROPOSAL");

        var context = new Context(run, inquiry, input, result, answer, userMessageIds(input.path("messages")),
                itemIds(input.path("systems")), itemIds(input.path("problems")), itemIds(result.path("evidence")));
        ObjectNode answerPayload = (ObjectNode) mapper.readTree(messages.findById(answerId).orElseThrow().getPayload());
        Set<String> handled = new LinkedHashSet<>();
        int storedQuestions = 0;
        int repeatedQuestions = 0;
        for (var proposal : proposals) {
            String ref = proposal.path("problemRef").asString();
            if (owned.contains(ref)) {
                handled.add(ref);
                continue;
            }
            InquiryProblem existing = null;
            if (!NEW_REF.matcher(ref).matches()) {
                existing = existing(context, ref);
                if (!reopenable(existing)) {
                    // 티켓·확인 대기 중인 문제에는 질문할 수 없다
                    require(!questionByRef.containsKey(ref), "QUESTION_FOR_CLOSED_PROBLEM");
                    handled.add(ref);
                    continue;
                }
            }
            JsonNode question = questionByRef.get(ref);
            ProblemStatus outcome = outcomeOf(proposal, question != null);
            if (outcome == null) {
                continue; // 저장할 상태를 정할 수 없다. 미적용으로 남긴다
            }
            InquiryProblem problem = store(context, proposal, existing, outcome);
            if (question != null) {
                if (recordQuestion(context, answerPayload, problem, question)) storedQuestions++;
                else repeatedQuestions++;
            }
            handled.add(ref);
        }
        if (storedQuestions > 0) {
            messages.findById(answerId).orElseThrow().recordQuestionBundles(mapper.writeValueAsString(answerPayload));
        }
        // 질문은 모두 대응하는 문제 제안이 저장돼야 한다. 대응하지 못한 질문이 남으면 거부한다.
        require(handled.containsAll(questionByRef.keySet()));
        return new Applied(Set.copyOf(handled), storedQuestions, repeatedQuestions);
    }

    private record Context(AgentRun run, Inquiry inquiry, JsonNode input, JsonNode result, String answer,
            Set<UUID> userMessageIds, Set<UUID> systemIds, Set<UUID> inputProblemIds, Set<UUID> evidenceIds) {
    }

    private InquiryProblem existing(Context context, String ref) {
        UUID id = uuid(mapper.valueToTree(ref));
        require(context.inputProblemIds().contains(id), "UNKNOWN_PROBLEM_REF");
        InquiryProblem problem = problems.findById(id).orElseThrow(() -> invalid("PROBLEM_NOT_FOUND"));
        require(!problem.isDeleted() && problem.getInquiry().getId().equals(context.inquiry().getId()));
        return problem;
    }

    // 아직 분석 중(질문 응답 대기·시스템 확인 대기)이고 티켓 생성 작업이 없는 기존 문제만 다시 저장한다
    private boolean reopenable(InquiryProblem problem) {
        return REOPENABLE.contains(problem.getProblemStatus()) && problem.getCreationOutcome() == null
                && tickets.findByInquiryProblem_Id(problem.getId()).isEmpty();
    }

    // 질문이 있으면 CLARIFYING, 범위 밖이면 OUT_OF_SCOPE, 시스템을 몰라 질문도 못 하면 SYSTEM_PENDING. 그 밖에는 정하지 않는다.
    private static @Nullable ProblemStatus outcomeOf(JsonNode proposal, boolean asked) {
        if (asked) return ProblemStatus.CLARIFYING;
        if ("OUT_OF_SCOPE".equals(proposal.path("intent").asString())) return ProblemStatus.OUT_OF_SCOPE;
        if (proposal.path("reportedSystemCandidateId").isNull()) return ProblemStatus.SYSTEM_PENDING;
        return null;
    }

    private InquiryProblem store(Context context, JsonNode proposal, @Nullable InquiryProblem existing,
            ProblemStatus outcome) {
        String summary = text(proposal.path("summary"));
        RequestIntent intent = RequestIntent.valueOf(text(proposal.path("intent")));
        WorkType workType = WorkType.valueOf(text(proposal.path("processingType")));
        ItSystem system = null;
        if (!proposal.path("reportedSystemCandidateId").isNull()) {
            UUID systemId = uuid(proposal.path("reportedSystemCandidateId"));
            require(context.systemIds().contains(systemId), "UNKNOWN_SYSTEM");
            system = systems.findForRead(systemId).orElseThrow(() -> invalid("SYSTEM_NOT_FOUND"));
        }
        statements(proposal.path("facts"), context.userMessageIds(), context.evidenceIds(), true);
        statements(proposal.path("assumptions"), context.userMessageIds(), context.evidenceIds(), false);
        require(proposal.path("unknowns").isArray());
        proposal.path("unknowns").forEach(GuideResultWriter::text);
        require(proposal.path("details").isObject());
        require(mapper.treeToValue(proposal.path("details"), RequestDetailsResponse.class) != null);

        Set<UUID> sources = new LinkedHashSet<>();
        Set<UUID> cited = new LinkedHashSet<>();
        for (String field : List.of("facts", "assumptions")) {
            for (var statement : proposal.path(field)) {
                sources.addAll(ids(statement.path("sourceMessageIds")));
                cited.addAll(ids(statement.path("knowledgeEvidenceIds")));
            }
        }

        Inquiry inquiry = context.inquiry();
        InquiryProblem problem = existing;
        if (problem == null) {
            String title = summary.substring(0, summary.offsetByCodePoints(0,
                    Math.min(MAX_TITLE_CODE_POINTS, summary.codePointCount(0, summary.length()))));
            problem = problems.saveAndFlush(new InquiryProblem(inquiry, problems.findMaxProblemNo(inquiry.getId()) + 1,
                    title, null, outcome, context.run()));
        } else if (outcome != ProblemStatus.CLARIFYING) {
            problem.recordAnalysisOutcome(outcome, context.run());
        }

        require(!requests.existsByInquiryProblem_IdAndInquiryVersionAndAgentRun_Id(problem.getId(),
                context.run().getInputVersion(), context.run().getId()));
        var standard = new StandardRequest(problem, context.run().getInputVersion(), context.run(), intent, workType,
                system, null, null, summary);
        standard.recordAnalysis(proposal.path("facts").toString(), proposal.path("assumptions").toString(),
                proposal.path("unknowns").toString(), proposal.path("details").toString(),
                mapper.writeValueAsString(citedKnowledge(context, cited, inquiry)));
        requests.saveAndFlush(standard);
        for (UUID source : sources) linker.link(inquiry, source, problem.getId());
        return problem;
    }

    // 사실·가정이 인용한 근거 중 문의자에게 공개 가능한 직원용 근거만 요청서에 남긴다. 공개할 수 없으면 제안을 거부한다.
    private List<?> citedKnowledge(Context context, Set<UUID> cited, Inquiry inquiry) {
        if (cited.isEmpty()) return List.of();
        var selected = mapper.createArrayNode();
        for (var evidence : context.result().path("evidence")) {
            if (cited.contains(uuid(evidence.path("id")))) selected.add(evidence);
        }
        var visible = knowledge.visible(selected.toString(), inquiry.getRequester());
        require(visible.size() == cited.size() && visible.stream().allMatch(item -> "EMPLOYEE".equals(item.accessScope())));
        return visible;
    }

    /** 질문 묶음 하나를 AI 메시지 payload에 더하고 질문 수를 올린다. 이미 저장한 묶음이면 세지 않고 false. */
    private boolean recordQuestion(Context context, ObjectNode answerPayload, InquiryProblem problem, JsonNode question) {
        String text = text(question.path("text"));
        String bundleRef = text(question.path("bundleRef"));
        require(bundleRef.length() <= MAX_BUNDLE_REF_LENGTH, "BUNDLE_REF_TOO_LONG");
        int offset = context.answer().indexOf(text);
        // 질문 문구는 사용자에게 보이는 answer 안에 있어야 한다
        require(offset >= 0, "QUESTION_NOT_IN_ANSWER");
        var fields = question.path("requestedFields");
        require(fields.isArray() && fields.size() <= MAX_REQUESTED_FIELDS, "REQUESTED_FIELDS_INVALID");
        Set<String> requested = new LinkedHashSet<>();
        for (var field : fields) require(requested.add(text(field)));

        String probe = mapper.writeValueAsString(Map.of("questionBundles",
                List.of(Map.of("problemId", problem.getId().toString(), "bundleRef", bundleRef))));
        if (messages.existsQuestionBundle(context.inquiry().getId(), probe)) {
            return false;
        }
        problem.recordQuestion(context.run()); // 5회 한도는 도메인이 검사한다(넘으면 IllegalStateException)

        ObjectNode bundle = array(answerPayload, "questionBundles").addObject();
        bundle.put("problemId", problem.getId().toString()).put("bundleRef", bundleRef);
        ArrayNode requestedFields = bundle.putArray("requestedFields");
        requested.forEach(requestedFields::add);
        int start = context.answer().codePointCount(0, offset);
        ObjectNode link = array(answerPayload, "problemLinks").addObject()
                .put("problemId", problem.getId().toString()).put("revision", 1);
        link.putArray("spans").addObject().put("start", start).put("end", start + text.codePointCount(0, text.length()));
        return true;
    }

    private static ArrayNode array(ObjectNode node, String key) {
        return node.path(key).isArray() ? (ArrayNode) node.get(key) : node.putArray(key);
    }

    private static boolean absent(JsonNode value) {
        return value.isMissingNode() || value.isNull();
    }

    private static boolean hasItems(JsonNode value) {
        return value.isArray() && !value.isEmpty();
    }

    private static Set<UUID> userMessageIds(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false)
                .filter(item -> "USER".equals(item.path("sender").asString()))
                .map(item -> uuid(item.path("id"))).collect(java.util.stream.Collectors.toSet());
    }

    private static Set<UUID> itemIds(JsonNode values) {
        return StreamSupport.stream(values.spliterator(), false).map(item -> uuid(item.path("id")))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void require(boolean valid) {
        if (!valid) throw invalid();
    }

    private static void require(boolean valid, String reason) {
        if (!valid) throw invalid(reason);
    }

    // reason은 AI 결과의 값이 아닌 고정 사유 코드만 쓴다. 로그에 그대로 남는다
    private static AgentResultRejectedException invalid(String reason) {
        return new AgentResultRejectedException("INVALID_OUTPUT", MESSAGE + ": " + reason);
    }

    private static AgentResultRejectedException invalid() {
        return new AgentResultRejectedException("INVALID_OUTPUT", MESSAGE);
    }
}
