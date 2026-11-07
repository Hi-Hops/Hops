package com.hihops.hops.inquiry.attachment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

class S3AttachmentStoreTest {
    @Test
    void 생성된_식별자로_저장하고_기존_객체의_덮어쓰기를_막는다() {
        S3Client client = mock(S3Client.class);
        S3AttachmentStore store = new S3AttachmentStore(client, "test-private-bucket");
        UUID id = UUID.randomUUID();
        store.put(id, new byte[]{1, 2, 3});
        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo("test-private-bucket");
        assertThat(request.getValue().key()).isEqualTo("chat-attachments/" + id);
        assertThat(request.getValue().ifNoneMatch()).isEqualTo("*");
        assertThat(request.getValue().contentType()).isEqualTo("image/png");
        assertThat(request.getValue().acl()).isNull();
    }

    @Test
    void 조회한_크기가_허용_범위를_넘으면_거부한다() throws IOException {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength((long) AttachmentImageSanitizer.MAX_OUTPUT_BYTES + 1).build(),
                AbortableInputStream.create(new ByteArrayInputStream(
                        new byte[AttachmentImageSanitizer.MAX_OUTPUT_BYTES + 1]))));
        S3AttachmentStore store = new S3AttachmentStore(client, "test-private-bucket");
        assertThatThrownBy(() -> store.get(UUID.randomUUID())).isInstanceOf(IOException.class);
    }

    @Test
    void 정상_객체를_조회한다() throws IOException {
        S3Client client = mock(S3Client.class);
        when(client.getObject(any(GetObjectRequest.class))).thenAnswer(ignored -> new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(3L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(new byte[]{1, 2, 3}))));
        S3AttachmentStore store = new S3AttachmentStore(client, "test-private-bucket");
        UUID id = UUID.randomUUID();
        assertThat(store.get(id)).containsExactly(1, 2, 3);
        ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(client).getObject(request.capture());
        assertThat(request.getValue().key()).isEqualTo("chat-attachments/" + id);
    }

    @Test
    void 비어_있거나_너무_큰_이미지는_저장하지_않는다() {
        S3Client client = mock(S3Client.class);
        S3AttachmentStore store = new S3AttachmentStore(client, "test-private-bucket");
        assertThatThrownBy(() -> store.put(UUID.randomUUID(), new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put(UUID.randomUUID(),
                new byte[AttachmentImageSanitizer.MAX_OUTPUT_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(client);
    }
}
