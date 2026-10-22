// 생성 파일. Java 21용 Mock 전송 DTO. JSON 경계의 Schema 검증은 별도다.
package hops.contracts.mock;
import java.util.List;
public final class HopsContracts {
    private HopsContracts() {}
    public record Department(String id, String code, String name, Boolean isRoutable) {}
    public record CurrentUser(String id, String employeeNo, String name, Department department, List<String> roles) {}
    public record LoginRequest(String loginId, String password) {}
    public record LoginResponse(String accessToken, String expiresAt, CurrentUser user) {}
    public record ApiErrorBody(String code, String message, String requestId, Boolean retryable) {}
    public record MessageRevision(Integer revisionNo, String content, String createdAt) {}
    public record ChatMessage(String id, String inquiryId, Integer revision, Integer sequenceNo, String sender, String content, String createdAt, List<MessageRevision> revisions) {}
    public record Problem(String id, String summary, String category, String status, Integer questionCount) {}
    public record RunSummary(String id, Integer inquiryVersion, String status, ApiErrorBody error) {}
    public record TicketCard(String id, String inquiryId, String problemId, String workItemId, String ticketNumber, String title, Department department, String processingStatus, String routingStatus, String createdAt) {}
    public record AssignmentHistory(Department department, String assignedAt, String endedAt) {}
    public record TicketDetail(String id, String inquiryId, String problemId, String workItemId, String ticketNumber, String title, Department department, String processingStatus, String routingStatus, String createdAt, String description, String diagnosisSummary, List<AssignmentHistory> assignmentHistory) {}
    public record CreationFailure(String workItemId, String problemId, String status, String errorCode) {}
    public record InquiryResult(String id, Integer version, String status, List<Problem> problems, List<TicketCard> tickets, List<CreationFailure> creationFailures, RunSummary currentRun) {}
    public record InquirySummary(String id, String title, String status, String createdAt) {}
    public record TicketPage(List<TicketCard> items, Integer page, Integer size, Integer totalElements) {}
    public record InquiryPage(List<InquirySummary> items, Integer page, Integer size, Integer totalElements) {}
    public record MessagePage(List<ChatMessage> items, Integer nextAfterSequenceNo) {}
    public record TicketListQuery(Integer page, Integer size, String processingStatus) {}
    public record InquiryListQuery(Integer page, Integer size) {}
    public record MessageListQuery(Integer afterSequenceNo, Integer size) {}
    public record InquiryCreateCommand(String requestId, String content) {}
    public record MessageSendCommand(String requestId, String inquiryId, Integer expectedInquiryVersion, String content) {}
    public record MessageReviseCommand(String requestId, String inquiryId, Integer expectedInquiryVersion, String content, String messageId) {}
    public record InquiryTarget(String type, String id) implements SubscriptionTarget {}
    public record DepartmentTicketsTarget(String type, String id) implements SubscriptionTarget {}
    public record TicketTarget(String type, String id) implements SubscriptionTarget {}
    public sealed interface SubscriptionTarget permits InquiryTarget, DepartmentTicketsTarget, TicketTarget {}
    public record SubscriptionCommand(String requestId, SubscriptionTarget target) {}
    public record StoredAck(Boolean ok, String requestId, String inquiryId, Integer inquiryVersion, String messageId, Integer revision, String runId, String effect) implements CommandAck {}
    public record ErrorAck(Boolean ok, String requestId, ApiErrorBody error) implements CommandAck, SubscriptionAck {}
    public record SubscriptionAccepted(Boolean ok, String requestId, SubscriptionTarget target) implements SubscriptionAck {}
    public sealed interface CommandAck permits StoredAck, ErrorAck {}
    public sealed interface SubscriptionAck permits SubscriptionAccepted, ErrorAck {}
    public record AgentProgressPayload(Integer stepNo, String stepType, String displaySummary, String status) {}
    public record AnswerDeltaPayload(String messageId, Integer seq, String text) {}
    public record AgentProgressEvent(String eventId, String inquiryId, Integer inquiryVersion, String runId, AgentProgressPayload payload) {}
    public record AnswerDeltaEvent(String eventId, String inquiryId, Integer inquiryVersion, String runId, AnswerDeltaPayload payload) {}
    public record MessageStoredEvent(String eventId, String inquiryId, Integer inquiryVersion, String messageId, String requestId) {}
    public record StateChangedEvent(String eventId, SubscriptionTarget target, Integer inquiryVersion) {}
    public record ActorContext(String userId, String departmentId, List<String> roles, String accessScopeRef) {}
    public record AiDepartment(String id, String code, String name, Boolean isRoutable, List<String> responsibilities) {}
    public record Evidence(String id, String kind, String sourceId, String sourceVersion, String summary, String observedAt) {}
    public record WorkItem(String ref, String problemRef, String workScope, String status, String ticketId) {}
    public record AiRequest(String schemaVersion, String runId, String inquiryId, Integer inquiryVersion, ActorContext actor, String deadlineAt, List<ChatMessage> messages, List<Problem> existingProblems, List<WorkItem> existingWorkItems, List<AiDepartment> departments, List<Evidence> evidence) {}
    public record AiProblem(String ref, String summary, String category, Integer questionCount, String action, String question, List<String> evidenceIds) {}
    public record TicketProposal(String ref, String problemRef, String workScope, String title, String description, String category, String departmentCode, String purpose, String diagnosisSummary, String handoffReason, String uncertainty, List<String> evidenceIds) {}
    public record AiSuccess(String schemaVersion, String runId, String inquiryId, Integer inquiryVersion, String status, String answer, List<AiProblem> problems, List<TicketProposal> ticketProposals, Void error) implements AiResult {}
    public record AiFailure(String schemaVersion, String runId, String inquiryId, Integer inquiryVersion, String status, ApiErrorBody error) implements AiResult {}
    public sealed interface AiResult permits AiSuccess, AiFailure {}
    public record AiAccepted(String schemaVersion, String runId, String inquiryId, Integer inquiryVersion, String status) {}
    public record AiProgress(String schemaVersion, String runId, String inquiryId, Integer inquiryVersion, AgentProgressPayload payload) {}
    public record AiDelta(String schemaVersion, String runId, String inquiryId, Integer inquiryVersion, AnswerDeltaPayload payload) {}
}
