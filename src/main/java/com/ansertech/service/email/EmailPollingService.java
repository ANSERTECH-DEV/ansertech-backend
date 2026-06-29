package com.ansertech.service.email;

import java.util.List;

public interface EmailPollingService {
    List<RawEmailData> fetchUnreadEmails(int maxResults) throws Exception;
    void markAsRead(String messageId) throws Exception;
    byte[] fetchAttachment(String messageId, String attachmentId) throws Exception;

    record RawEmailData(
            String externalId,
            String senderEmail,
            String senderName,
            String subject,
            String body,
            java.time.LocalDateTime receivedAt,
            boolean hasAttachments,
            List<AttachmentInfo> attachments,
            List<String> ccAddresses
    ) {}

    record AttachmentInfo(
            String id,
            String name,
            String mimeType,
            long size
    ) {}
}
