package com.hihops.hops.inquiry.service;

/** 문의자의 현재 관찰 조회와 응답에서 공통으로 적용하는 대상 조건. */
final class ImageObservationVisibility {
    private ImageObservationVisibility() { }

    static final String FROM_WHERE = """
            FROM image_observation o
            JOIN inquiry i ON i.id = o.inquiry_id
            JOIN image_attachment a ON a.id = o.attachment_id AND a.message_id = o.source_message_id
            JOIN message source ON source.id = o.source_message_id
            JOIN message answer ON answer.id = o.answer_message_id
            JOIN inquiry_problem p ON p.id = o.problem_id
            JOIN agent_run r ON r.id = o.run_id
            WHERE o.inquiry_id = :inquiry AND i.requester_member_id = :actor AND i.deleted_at IS NULL
              AND o.question_bundle_ref IS NOT NULL AND a.owner_member_id = :actor AND a.attachment_status = 'BOUND'
              AND source.inquiry_id = :inquiry AND source.revision = o.source_revision
              AND source.sender_type = 'USER' AND source.message_status = 'COMPLETED' AND source.deleted_at IS NULL
              AND answer.inquiry_id = :inquiry AND answer.sender_type = 'AI'
              AND answer.message_status = 'COMPLETED' AND answer.deleted_at IS NULL
              AND answer.payload->>'answerRunId' = o.run_id::text
              AND position(o.observation_text in answer.content) > 0
              AND COALESCE(answer.payload->'questionBundles', '[]'::jsonb) @> jsonb_build_array(
                  jsonb_build_object('problemId', o.problem_id::text, 'bundleRef', o.question_bundle_ref))
              AND p.inquiry_id = :inquiry AND p.deleted_at IS NULL
              AND r.inquiry_id = :inquiry AND r.task = 'INQUIRY_ANALYSIS' AND r.run_status = 'SUCCEEDED'
            """;
}
