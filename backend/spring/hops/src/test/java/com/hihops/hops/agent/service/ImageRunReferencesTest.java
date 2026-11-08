package com.hihops.hops.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.hihops.hops.agent.dto.AiRunRequest.ImageRef;
import com.hihops.hops.inquiry.domain.Message;
import com.hihops.hops.inquiry.domain.SenderType;

import tools.jackson.databind.json.JsonMapper;

/** 대상 문제를 정한 실행에는 그 문제와 관련된 이미지만 넣는다. */
class ImageRunReferencesTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final UUID owner = UUID.randomUUID();
    private final UUID problemA = UUID.randomUUID();
    private final UUID problemB = UUID.randomUUID();
    private final String digest = "a".repeat(64);

    @Test
    void 대상_문제_실행에는_다른_문제_메시지의_이미지를_넣지_않는다() throws Exception {
        Message forA = message(problemA);
        Message forB = message(problemB);
        UUID imageA = UUID.randomUUID();
        UUID imageB = UUID.randomUUID();
        var references = references(List.of(row(forA, imageA), row(forB, imageB)), List.of());

        var result = references.forMessages(owner, List.of(forA, forB), List.of(problemA, problemB), List.of(problemB), null);

        assertThat(result).containsOnlyKeys(forB.getId());
        assertThat(result.get(forB.getId())).extracting(ImageRef::attachmentId).containsExactly(imageB);
    }

    @Test
    void 문제와_연결되지_않은_메시지의_이미지도_대상_문제_실행에서_뺀다() throws Exception {
        Message unlinked = message();
        var references = references(List.of(row(unlinked, UUID.randomUUID())), List.of());

        var result = references.forMessages(owner, List.of(unlinked), List.of(problemA), List.of(problemA), null);

        assertThat(result).isEmpty();
    }

    @Test
    void 대상을_정하지_않은_실행에는_모든_이미지를_넣는다() throws Exception {
        Message forA = message(problemA);
        Message forB = message(problemB);
        var references = references(List.of(row(forA, UUID.randomUUID()), row(forB, UUID.randomUUID())), List.of());

        var result = references.forMessages(owner, List.of(forA, forB), List.of(problemA, problemB), List.of(), null);

        assertThat(result).containsOnlyKeys(forA.getId(), forB.getId());
    }

    @Test
    void 사용자가_고른_분석_대상은_메시지의_문제보다_우선한다() throws Exception {
        Message forA = message(problemA);
        UUID image = UUID.randomUUID();
        var references = references(List.of(row(forA, image)), List.of(selection(image, problemB)));

        var result = references.forMessages(owner, List.of(forA), List.of(problemA, problemB), List.of(problemB), null);

        assertThat(result.get(forA.getId())).singleElement().satisfies(ref -> {
            assertThat(ref.attachmentId()).isEqualTo(image);
            assertThat(ref.analysisProblemIds()).containsExactly(problemB);
        });
    }

    private ImageRunReferences references(List<ResultSet> images, List<ResultSet> selections) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> rowMapper = invocation.getArgument(2);
            List<Object> mapped = new ArrayList<>();
            for (ResultSet rs : sql.contains("FROM image_attachment_analysis_target") ? selections : images) {
                mapped.add(rowMapper.mapRow(rs, mapped.size()));
            }
            return mapped;
        }).when(jdbc).query(anyString(), anyMap(), any(RowMapper.class));
        return new ImageRunReferences(jdbc, mapper, true);
    }

    private Message message(UUID... problems) {
        Message message = mock(Message.class);
        UUID id = UUID.randomUUID();
        var links = java.util.Arrays.stream(problems).map(problem -> Map.of("problemId", problem, "revision", 1)).toList();
        when(message.getId()).thenReturn(id);
        when(message.getRevision()).thenReturn(1);
        when(message.getSenderType()).thenReturn(SenderType.USER);
        when(message.getPayload()).thenReturn(mapper.writeValueAsString(Map.of("problemLinks", links)));
        return message;
    }

    private ResultSet row(Message message, UUID attachmentId) throws Exception {
        UUID messageId = message.getId();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("message_id", UUID.class)).thenReturn(messageId);
        when(rs.getObject("id", UUID.class)).thenReturn(attachmentId);
        when(rs.getObject("owner_member_id", UUID.class)).thenReturn(owner);
        when(rs.getString("content_sha256")).thenReturn(digest);
        when(rs.getInt("byte_size")).thenReturn(1024);
        return rs;
    }

    private ResultSet selection(UUID attachmentId, UUID problemId) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("attachment_id", UUID.class)).thenReturn(attachmentId);
        when(rs.getObject("problem_id", UUID.class)).thenReturn(problemId);
        when(rs.getBoolean("valid")).thenReturn(true);
        return rs;
    }
}
