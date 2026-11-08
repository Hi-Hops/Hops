package com.hihops.hops.inquiry.attachment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(prefix = "hops.image-attachments", name = "enabled", havingValue = "true")
class ImageAttachmentConfiguration {
    @Bean(destroyMethod = "close")
    S3Client attachmentS3Client() {
        return S3Client.create();
    }

    @Bean
    S3AttachmentStore s3AttachmentStore(S3Client attachmentS3Client,
            @Value("${hops.image-attachments.bucket}") String bucket) {
        return new S3AttachmentStore(attachmentS3Client, bucket);
    }
}
